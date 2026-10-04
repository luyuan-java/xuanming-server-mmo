package com.game.guild.store;

import com.game.common.deadline.Deadline;
import com.game.guild.rules.GuildReject;
import com.game.guild.rules.GuildTableRules.ApplicationRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * 帮会的权威存储（mmorpg go/guild internal/data 的 guild_repo.go 写 / 读 MySQL 部分 + guild_manage_repo.go 的 Java 版；
 * 规格 guild-spec §1、§3）。缓存、排行 ZSET、推送不在这里：写方法把提交后要做的事放进结果（{@link Invalidation}、推送收件人），
 * 由服务在提交之后去做。
 *
 * <p><b>纪律</b>（guild_manage_repo.go:9-57）：
 * <ul>
 *   <li>授权只看锁内 MySQL：所有权限判定在事务里对 {@code FOR UPDATE} 锁住的行做，缓存只用来定位操作者属于哪个帮、以及展示；</li>
 *   <li>锁序固定 G guild → S guild_player_state → M guild_member → A guild_application（→ 4.5 / 4.6 的 Q → O → C → P）；
 *       同表多行按主键<b>无符号</b>升序逐行取锁；锁定读 / UPDATE / DELETE 只做完整主键等值的点操作；插或删成员行的事务都先持有该玩家的
 *       状态行锁；唯一二级索引的查重插入者（建帮、审批通过、补建状态行）先持全局插入守卫 S(0)；</li>
 *   <li>提交之后才失效缓存、才推送（服务负责）。</li>
 * </ul>
 *
 * <p><b>结局</b>：业务拒绝与写冲突（14021）以 {@link TxOutcome} 返回（{@link TxOutcome.Reject} 已回滚，{@link TxOutcome.CommitThenReject}
 * 已提交删申请行）；SQL 故障 / 请求预算用完抛 {@link com.game.common.deadline.Deadline.DependencyException}，哨兵行 / 状态行缺失与
 * 写入自检失败抛 {@link GuildStoreException}，入参违约抛 {@link IllegalArgumentException}——三者上层一律定性为信封 1003。
 *
 * <p>id 一律无符号 64 位（0 不是合法玩家 / 帮会）；时刻一律由调用方传入（服务唯一的「现在」，guild_manage_logic.go:200-202）。
 * 线程安全；全部方法阻塞（JDBC），只在 guild-worker 线程上调用；每条语句挂在请求预算（事务里是子预算）上。
 */
public interface GuildStore {

    // ================================================================ 入参

    /**
     * 按帮会等级查长老上限（基线 OfficerCapFunc，guild_manage_repo.go:469-471）：空 = GuildLevel 缺该等级行，存储回
     * {@link GuildReject#LEVEL_CONFIG_MISSING}（故障），绝不默认放行。服务传 {@code level -> GuildTableRules.officerCap(tables, level)}。
     */
    @FunctionalInterface
    interface OfficerCaps {
        OptionalInt maxOfficers(int level);
    }

    /**
     * 事务内合服闸门（基线 FenceFunc + checkFence，economy_repo.go:159-160、:323-335）：入参是事务内 {@code FOR UPDATE} 读到的
     * guild.zone_id。返回 true = 合服中；抛任何异常 = 闸门读不出来，按合服中处理（fail-closed）——两者都回
     * {@link GuildReject#ZONE_MERGING}。4.4 的服务传 {@link #OPEN}（D4：MergeFence.NONE）。
     */
    @FunctionalInterface
    interface ZoneFence {
        boolean merging(int zoneId) throws Exception;

        ZoneFence OPEN = zoneId -> false;
    }

    // ================================================================ 结果

    /** 建帮成功。失效 guild(G) 与建帮者的映射（guild_repo.go:388）；不推送（帮里只有建帮者，guild_logic.go:289）。 */
    record Created(long guildId, long leaderId, int zoneId) {
        public Invalidation invalidation() {
            return Invalidation.of(GuildTxOp.CREATE, guildId, leaderId);
        }
    }

    /**
     * 任免结果。{@code changed == false} 是幂等无操作（目标已是该角色）：不写库、不失效、不推送（guild_manage_repo.go:1328-1336、:1368-1371）。
     *
     * @param guild 事务内权威快照（已含本次写），回包用
     */
    record RoleChanged(GuildData guild, boolean changed, long actorId, long targetId) {
        public Invalidation invalidation() {
            return changed ? Invalidation.of(GuildTxOp.SET_ROLE, guild.guildId()) : Invalidation.none(GuildTxOp.SET_ROLE);
        }

        /** ROLE_CHANGED(4) 的收件人：仅 changed 时，快照除帮主（actor）（guild_manage_logic.go:396-399）。 */
        public List<Long> pushRecipients() {
            return changed ? guild.memberIdsExcept(actorId) : List.of();
        }
    }

    /** 踢人结果；快照已不含被踢者。 */
    record Kicked(GuildData guild, long actorId, long targetId) {
        /** 失效 guild(G) 与被踢者的映射（guild_manage_repo.go:1448）。 */
        public Invalidation invalidation() {
            return Invalidation.of(GuildTxOp.KICK, guild.guildId(), targetId);
        }

        /** MEMBER_KICKED(3) 的收件人：快照除操作者，<b>再加被踢者本人</b>（guild_manage_logic.go:426-429）。 */
        public List<Long> pushRecipients() {
            List<Long> out = new ArrayList<>(guild.memberIdsExcept(actorId));
            out.add(targetId);
            return out;
        }
    }

    /** 转让结果；快照里新帮主 role=3、原帮主降为长老或成员。 */
    record Transferred(GuildData guild, long oldLeaderId, long newLeaderId) {
        /** 失效 guild(G)（guild_manage_repo.go:1589）。 */
        public Invalidation invalidation() {
            return Invalidation.of(GuildTxOp.TRANSFER, guild.guildId());
        }

        /** LEADER_TRANSFERRED(5) 的收件人：快照除原帮主（guild_manage_logic.go:455）。 */
        public List<Long> pushRecipients() {
            return guild.memberIdsExcept(oldLeaderId);
        }
    }

    /** 退帮结果；快照已不含退帮者。 */
    record Left(GuildData guild, long playerId) {
        /** 失效 guild(G) 与退帮者的映射（guild_manage_repo.go:1654）。 */
        public Invalidation invalidation() {
            return Invalidation.of(GuildTxOp.LEAVE, guild.guildId(), playerId);
        }

        /** MEMBER_LEFT(2) 的收件人：快照剩余全体（退帮者本就不在快照里，guild_logic.go:463-464）。 */
        public List<Long> pushRecipients() {
            return guild.memberIds();
        }
    }

    /**
     * 申请结果。{@code inserted == false} = 同帮重复申请，只刷新了有效期（刷新即成功，不推送）。申请不失效任何缓存
     * （pending_application_count 不进缓存，guild_manage_repo.go:1804）。
     *
     * @param reviewerIds 仅 inserted 时有值：该帮长老与帮主（事务内读，player_id 升序），APPLICATION_RECEIVED(7) 的收件人
     *                    （还要过 60 s 推送冷却，guild_manage_logic.go:500-503）
     */
    record Applied(boolean inserted, List<Long> reviewerIds) {
        public Applied {
            reviewerIds = List.copyOf(reviewerIds);
        }
    }

    /** 审批结果（通过与拒绝都回快照，guild_manage_logic.go:761）。 */
    record Reviewed(GuildData guild, boolean approved, long reviewerId, long applicantId) {
        /** 只有通过才失效 guild(G) 与申请人的映射；拒绝只动了申请行（guild_manage_repo.go:2220-2223）。 */
        public Invalidation invalidation() {
            return approved ? Invalidation.of(GuildTxOp.REVIEW, guild.guildId(), applicantId)
                    : Invalidation.none(GuildTxOp.REVIEW);
        }

        /**
         * 通过 → MEMBER_JOINED(1)，快照（含新成员）除审批人；拒绝 → APPLICATION_REJECTED(8)，只推申请人
         * （guild_manage_logic.go:754-760）。
         */
        public List<Long> pushRecipients() {
            return approved ? guild.memberIdsExcept(reviewerId) : List.of(applicantId);
        }
    }

    /**
     * 解散结果（基线 DisbandResult，guild_manage_repo.go:493-501）。
     *
     * @param zoneId    删除事务内 {@code FOR UPDATE} 读到的归属区——清榜唯一可信的输入（缓存里的 zone 合服后会陈旧一个 TTL）
     * @param memberIds 解散前的全部成员，player_id 无符号升序；既是推送收件人来源，也是要失效的映射键
     */
    record Disbanded(long guildId, int zoneId, long actorId, List<Long> memberIds) {
        public Disbanded {
            memberIds = List.copyOf(memberIds);
        }

        /** 失效 guild(G) 与全体成员的映射（guild_manage_repo.go:2351）。 */
        public Invalidation invalidation() {
            return Invalidation.of(GuildTxOp.DISBAND, guildId, memberIds);
        }

        /** DISBANDED(6) 的收件人：解散前全体成员除帮主（只提交过申请的人不通知，guild_logic.go:510-518）。 */
        public List<Long> pushRecipients() {
            List<Long> out = new ArrayList<>(memberIds.size());
            for (long m : memberIds) {
                if (m != actorId) {
                    out.add(m);
                }
            }
            return out;
        }
    }

    /** 改公告结果。 */
    record AnnouncementUpdated(GuildData guild, long actorId) {
        /** <b>无条件</b>失效 guild(G)（写同样的文本也算一次成功，guild_manage_repo.go:2427）。 */
        public Invalidation invalidation() {
            return Invalidation.of(GuildTxOp.ANNOUNCEMENT, guild.guildId());
        }

        /** ANNOUNCEMENT_CHANGED(11) 的收件人：快照除操作者（guild_logic.go:553-554）。 */
        public List<Long> pushRecipients() {
            return guild.memberIdsExcept(actorId);
        }
    }

    /** 本人视角的一条待审申请（ListMyApplications）。 */
    record ApplicationRow(long guildId, long applyMs, long expireMs) {
    }

    /** 帮会视角的一条待审申请（ListApplicants）。 */
    record ApplicantRow(long playerId, long applyMs, long expireMs) {
    }

    /** 排行重建的一行权威数据（G10）。 */
    record GuildScore(long guildId, int zoneId, long score) {
    }

    // ================================================================ 写（事务）

    /**
     * 建帮（guild_repo.go:305-390；spec §3.2）。{@code guild} 是服务内存构造的帮会：恰好一名成员（建帮者，role=3），
     * {@code createTimeMs} 同时是入帮与状态行时刻；{@code name} 是展示名，{@code name_norm} 由存储防御性重算。
     *
     * <p>拒绝：{@link GuildReject#ALREADY_IN_GUILD}（插成员行撞任何 1062）、{@link GuildReject#NAME_TAKEN}（只有撞 uk_guild；
     * 撞 guild 主键等其它 1062 是故障）、{@link GuildReject#WRITE_CONFLICT}。哨兵行缺失 → {@link GuildStoreException}。
     */
    TxOutcome<Created> createGuild(GuildData guild, Deadline deadline);

    /**
     * 任免（只能设 0 / 1；guild_manage_repo.go:1288-1373；spec §3.8）。{@code actor != target} 且 role 可分配，否则
     * {@link IllegalArgumentException}。拒绝：GUILD_GONE / NOT_MEMBER / TARGET_NOT_MEMBER / RANK_TOO_LOW（操作者不是帮主，或目标是帮主）/
     * OFFICER_LIMIT / LEVEL_CONFIG_MISSING / WRITE_CONFLICT。
     */
    TxOutcome<RoleChanged> setMemberRole(long guildId, long actorId, long targetId, int role, OfficerCaps caps,
                                         Deadline deadline);

    /**
     * 踢人（guild_manage_repo.go:1375-1464；spec §3.9）。事务外先普通读目标是不是本帮成员：不是就按锁内同一优先级答复
     * （GUILD_GONE → NOT_MEMBER → TARGET_NOT_MEMBER），不建状态行、不开事务。拒绝：上述三项 / RANK_TOO_LOW / WRITE_CONFLICT。
     */
    TxOutcome<Kicked> kickMember(long guildId, long actorId, long targetId, long nowMs, Deadline deadline);

    /**
     * 转让帮主（guild_manage_repo.go:1504-1591；spec §3.10）。拒绝：GUILD_GONE / NOT_MEMBER / TARGET_NOT_MEMBER / RANK_TOO_LOW /
     * LEADER_MISMATCH（guild.leader_id 不是操作者，故障）/ LEVEL_CONFIG_MISSING / WRITE_CONFLICT；写后帮主人数不是 1 →
     * {@link GuildStoreException}（回滚）。
     */
    TxOutcome<Transferred> transferLeader(long guildId, long actorId, long targetId, OfficerCaps caps, Deadline deadline);

    /**
     * 退帮（guild_manage_repo.go:1593-1656；spec §3.5）。拒绝：GUILD_GONE / NOT_MEMBER（服务据此做幂等复核）/ LEADER_CANT_LEAVE
     * （role 是帮主，或 leader_id 是自己）/ WRITE_CONFLICT。
     */
    TxOutcome<Left> leaveGuild(long guildId, long playerId, long nowMs, Deadline deadline);

    /**
     * 申请入帮（guild_manage_repo.go:1658-1901；spec §3.11）。{@code requiredZone} = 申请人归属区（0 = 不校验）。
     * 同帮重复申请刷新有效期并成功（排在判满与两个上限之前）。提交之后尽力清本帮至多 10 条过期申请（失败只记 Info）。
     * 拒绝：GUILD_GONE / ZONE_MISMATCH / ALREADY_IN_GUILD / GUILD_FULL / APPLICATION_LIMIT / QUEUE_FULL / WRITE_CONFLICT。
     *
     * @param rules 三个字段都必须 &gt; 0（0 = 配表没读到），否则 {@link IllegalArgumentException}
     */
    TxOutcome<Applied> applyToGuild(long guildId, long playerId, int requiredZone, long nowMs, ApplicationRules rules,
                                    Deadline deadline);

    /**
     * 撤回申请（guild_manage_repo.go:1925-1967；spec §3.12）：删掉一条未过期的行 → Ok(null)；行不在 → Reject(APPLICATION_NOT_FOUND)；
     * 行在但已过期 → 删掉并提交、CommitThenReject(APPLICATION_NOT_FOUND)。不取任何守卫，不失效缓存，从不推送。
     */
    TxOutcome<Void> cancelApplication(long guildId, long playerId, long nowMs, Deadline deadline);

    /**
     * 审批（guild_manage_repo.go:2044-2225；spec §3.15）。通过时 {@code applicantZone} 必须 &gt; 0（服务先查到申请人归属区）。
     * 通过前事务外先普通读申请行：不在就按 GUILD_GONE → NOT_MEMBER → RANK_TOO_LOW → APPLICATION_NOT_FOUND 答复，不建状态行、不取守卫。
     * 过期 / 跨区 / 申请人已入他帮（1062）→ 删申请并提交、CommitThenReject(APPLICATION_NOT_FOUND)；帮满 → GUILD_FULL（回滚，申请保留）。
     */
    TxOutcome<Reviewed> reviewApplication(long guildId, long actorId, long applicantId, boolean approve, int applicantZone,
                                          long nowMs, Deadline deadline);

    /**
     * 解散（guild_manage_repo.go:2227-2384；spec §3.6；子预算 2500 ms）。授权只看 guild.leader_id（不看 role）：不是 → RANK_TOO_LOW
     * （服务改回 14005）；锁住 guild 行后按行里的 zone_id 调 {@code fence} → ZONE_MERGING。删全体成员行（先于删申请）、本帮申请与成员们在
     * 别帮的申请、4.5 / 4.6 钩子、最后删 guild 行。拒绝：GUILD_GONE / RANK_TOO_LOW / ZONE_MERGING / WRITE_CONFLICT。
     */
    TxOutcome<Disbanded> disbandGuild(long guildId, long actorId, long nowMs, ZoneFence fence, Deadline deadline);

    /**
     * 改公告（guild_manage_repo.go:2386-2429；spec §3.7）。长度由服务校验。拒绝：GUILD_GONE / ANNOUNCEMENT_FORBIDDEN（不在该帮或
     * 档位不到长老）/ WRITE_CONFLICT。
     */
    TxOutcome<AnnouncementUpdated> updateAnnouncement(long guildId, long playerId, String announcement, Deadline deadline);

    // ================================================================ 读（自动提交，不加锁）

    /** 读一份完整快照（loadGuild：G9 + M11）；帮会不存在返回空。缓存回源与 ResolvePlayerGuild 的绕缓存直读用它。 */
    Optional<GuildData> loadGuild(long guildId, Deadline deadline);

    /** 玩家所在帮会（M5，经 uk_guild_member 点查）；不在任何帮返回 0。映射缓存回源与 VerifyPlayerGuildID 用它。 */
    long playerGuildId(long playerId, Deadline deadline);

    /** 非锁定读权威 role（MemberRole，M4）：只给只读 RPC（列待审）授权与预读答复用，写路径必须在事务里复核。空 = 不是成员。 */
    OptionalInt memberRole(long guildId, long playerId, Deadline deadline);

    /** 帮会归属区的非锁定读（authoritativeZoneID，G8）；空 = 帮会不存在。清榜在帮会行仍在时用它取权威 zone。 */
    OptionalInt guildZoneId(long guildId, Deadline deadline);

    /** 按 I1 统计帮会有效待审数（A13），供 GuildInfo.pending_application_count 现算（不进缓存）。 */
    long countLiveApplications(long guildId, long nowMs, Deadline deadline);

    /** 本人未过期申请，apply_ms 降序、guild_id 升序，至多 limit 条（A14）；limit ≤ 0 返回空（不是「不限量」）。 */
    List<ApplicationRow> listMyApplications(long playerId, long nowMs, int limit, Deadline deadline);

    /** 帮会有效申请（I1 过滤），apply_ms 升序、player_id 升序，至多 limit 条（A15）；limit ≤ 0 返回空。 */
    List<ApplicantRow> listApplicants(long guildId, long nowMs, int limit, Deadline deadline);

    /** 全表 (guild_id, zone_id, score)（G10）：排行重建的唯一数据源（score 是权威分，ZSET 由它重建）。 */
    List<GuildScore> allGuildScores(Deadline deadline);

    /**
     * 同 {@link #allGuildScores}，逐行交给 {@code sink}（启动期重建排行用：rank 的 {@code RankSource.scan}）。sink 抛的异常原样上抛，
     * SQL 故障抛 {@link com.game.common.deadline.Deadline.DependencyException}。
     */
    void scanGuildScores(Deadline deadline, Consumer<GuildScore> sink);

    /** 单个帮会的 (guild_id, zone_id, score)；不存在返回空（Java 增项：入榜 / 校对单帮用，与 G10 同列）。 */
    Optional<GuildScore> guildScore(long guildId, Deadline deadline);
}
