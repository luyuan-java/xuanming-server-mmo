package com.game.guild.store;

import com.game.common.deadline.Deadline;
import com.game.guild.rules.GuildLimits;
import com.game.guild.rules.GuildNames;
import com.game.guild.rules.GuildReject;
import com.game.guild.rules.GuildRoles;
import com.game.guild.rules.GuildTableRules.ApplicationRules;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link GuildStore} 的 MySQL 实现（语句逐字对齐 mmorpg guild_repo.go / guild_manage_repo.go，编号见 guild-spec §1.9 的 SQL 全目录）。
 *
 * <p><b>锁序</b>（guild_manage_repo.go:9-57；§1.5）：表间全序 G guild → S guild_player_state → M guild_member → A guild_application
 * （→ 4.5 / 4.6 的 Q → O → C → P，经 {@link GuildTxHooks} 接入）。两个登记过的例外，各自带不成环论证：
 * <ul>
 *   <li><b>建帮的新 G 行放在最后一条写</b>（guild_repo.go:308-327）：新 guild_id 提交前别人无从得知，能等它的只有同名插入者，
 *       而同名插入者也排在全局插入守卫 S(0) 之后；</li>
 *   <li><b>审批通过在锁住 A(G,p) 之后才插 M(G,p)</b>（guild_manage_repo.go:2065-2081）：过期 / 跨区 / 1062 分支都要「删掉这条申请并提交」。</li>
 * </ul>
 * 同表多行按主键<b>无符号</b>升序逐行取锁（{@link Long#compareUnsigned}——有符号比较会让 ≥ 2^63 的 id 与 MySQL 主键顺序相反，推演失效）。
 * 锁定读 / UPDATE / DELETE 只做完整主键等值的点操作：按二级条件找行先普通读（RC 语句级快照，不加锁）取候选主键、排序后逐行点锁 / 点删，
 * WHERE 带原条件作提交点复核，影响 0 行即跳过。guild_member 的锁定 SELECT / UPDATE 一律 {@code FORCE INDEX (PRIMARY)}
 * （单表 DELETE 不收索引提示，由 EXPLAIN 回归钉住它走 PRIMARY）。插或删成员行的事务（建帮、审批通过、踢人、退帮、解散）都先持有该玩家的
 * 状态行锁 S(p)；唯一二级索引的查重插入者（建帮、审批通过、补建状态行）在事务内先点锁全局插入守卫 S(0)（哨兵行 player_id = 0），
 * 其余事务一概不取它。
 *
 * <p><b>状态行</b>（{@link #ensurePlayerStateRows}）：事务外先普通读挑出已存在的行；缺的在守卫 S(0) 下的 RC 短事务里复读、按升序
 * INSERT IGNORE。不对已存在的行直接 INSERT IGNORE——撞上已存在主键会取 S 锁，解散持着全体成员的状态行时会白等。
 *
 * <p>连接池必须是会话级 READ COMMITTED、{@code innodb_lock_wait_timeout=1}、{@code useAffectedRows=true}（申请 IODKU 的自检依赖
 * 「实际改动行数」；§7.5）。线程安全；全部方法阻塞。
 */
public final class JdbcGuildStore implements GuildStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcGuildStore.class);

    /** 全局插入守卫 = guild_player_state 里 player_id = 0 的哨兵行（guild_manage_repo.go:831-834）。0 永远不是真实玩家。 */
    public static final long GLOBAL_INSERT_GUARD_PLAYER_ID = 0L;

    /** guild 表帮名唯一索引名（pbmysql 按 "uk_" + 表名命名；guild_repo.go:536-538）。 */
    static final String GUILD_NAME_UNIQUE_KEY = "uk_guild";

    // ---------------------------------------------------------------- G：guild
    /** G1：锁序位置 1。无行 → GUILD_GONE。 */
    static final String LOCK_GUILD = "SELECT level, leader_id, zone_id, max_members FROM guild WHERE guild_id = ? FOR UPDATE";
    /** G2：解散的最后一条写。0 行 → GUILD_GONE；&gt; 1 → 内部错误。 */
    static final String DELETE_GUILD = "DELETE FROM guild WHERE guild_id = ?";
    /** G3：建帮事务里最后一条写（登记例外）。 */
    static final String INSERT_GUILD = "INSERT INTO guild (guild_id, name, name_norm, leader_id, level, announcement,"
            + " create_time_ms, max_members, zone_id, score, funds) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)";
    /** G4：转让改 leader_id（恰好 1 行；会维护 idx_guild_1 的二级项，域内没有语句经它加锁）。 */
    static final String UPDATE_LEADER = "UPDATE guild SET leader_id = ? WHERE guild_id = ?";
    /** G5：改公告（不检查 RowsAffected：写同样的文本是 0 行）。 */
    static final String UPDATE_ANNOUNCEMENT = "UPDATE guild SET announcement = ? WHERE guild_id = ?";
    /** G8：归属区的非锁定读（authoritativeZoneID / readOperatorRole）。 */
    static final String SELECT_GUILD_ZONE = "SELECT zone_id FROM guild WHERE guild_id = ?";
    /** G9：loadGuild 的帮会行。 */
    static final String SELECT_GUILD = "SELECT guild_id, name, leader_id, level, COALESCE(announcement, ''), create_time_ms,"
            + " max_members, zone_id, score, funds FROM guild WHERE guild_id = ?";
    /** G10：排行重建的全表普通读。 */
    static final String SELECT_ALL_SCORES = "SELECT guild_id, zone_id, score FROM guild";
    /** G10 的单帮版（Java 增项）。 */
    static final String SELECT_GUILD_SCORE = "SELECT guild_id, zone_id, score FROM guild WHERE guild_id = ?";

    // ---------------------------------------------------------------- S：guild_player_state
    /** S1：补建状态行 / 哨兵行。 */
    static final String ENSURE_PLAYER_STATE = "INSERT IGNORE INTO guild_player_state (player_id, updated_ms) VALUES (?, ?)";
    /** S2：玩家守卫与全局插入守卫（id = 0）的点锁。 */
    static final String LOCK_PLAYER_STATE = "SELECT player_id FROM guild_player_state WHERE player_id = ? FOR UPDATE";
    /** S3：查缺行的普通读（IN 按 100 分块，由调用方拼占位符）。 */
    static final String SELECT_EXISTING_PLAYER_STATES_HEAD = "SELECT player_id FROM guild_player_state WHERE player_id IN (";

    // ---------------------------------------------------------------- M：guild_member
    /** M1：成员行唯一的锁定读（FORCE INDEX (PRIMARY)：WHERE 同时钉死了 uk_guild_member，不强制可能走 uk = 先二级后主键）。 */
    public static final String LOCK_MEMBER_ROLE =
            "SELECT role FROM guild_member FORCE INDEX (PRIMARY) WHERE guild_id = ? AND player_id = ? FOR UPDATE";
    /** M2：持 guild 行锁时数成员（判满）。 */
    static final String COUNT_MEMBERS = "SELECT COUNT(*) FROM guild_member WHERE guild_id = ?";
    /** M3：持 guild 行锁时按职位计数（长老上限 / 转让后断言帮主数）。 */
    static final String COUNT_MEMBERS_BY_ROLE = "SELECT COUNT(*) FROM guild_member WHERE guild_id = ? AND role = ?";
    /** M4：非锁定读 role（MemberRole）。 */
    static final String SELECT_MEMBER_ROLE = "SELECT role FROM guild_member WHERE guild_id = ? AND player_id = ?";
    /** M5：玩家所在帮会（经 uk 点查）。 */
    static final String SELECT_MEMBER_GUILD = "SELECT guild_id FROM guild_member WHERE player_id = ?";
    /** M6：审批人名单（长老与帮主，player_id 升序；参数 1, 3）。 */
    static final String SELECT_REVIEWERS =
            "SELECT player_id FROM guild_member WHERE guild_id = ? AND role IN (?, ?) ORDER BY player_id";
    /** M7：插成员行（建帮 role 3 / 审批通过 role 0）；帮贡两列写 0。 */
    static final String INSERT_MEMBER = "INSERT INTO guild_member (guild_id, player_id, role, join_time_ms, last_active_ms,"
            + " contribution_total, contribution_balance) VALUES (?, ?, ?, ?, ?, 0, 0)";
    /** M8：改 role（role 不在任何二级索引里；FORCE INDEX 只为搜索阶段不走 uk）。 */
    static final String UPDATE_MEMBER_ROLE =
            "UPDATE guild_member FORCE INDEX (PRIMARY) SET role = ? WHERE guild_id = ? AND player_id = ?";
    /** M9：删成员行（恰好 1 行；行已在同一事务里经 M1 锁住）。 */
    static final String DELETE_MEMBER = "DELETE FROM guild_member WHERE guild_id = ? AND player_id = ?";
    /** M10：成员 id（解散的事务外预读与事务内成员全集）。 */
    static final String SELECT_MEMBER_IDS = "SELECT player_id FROM guild_member WHERE guild_id = ? ORDER BY player_id";
    /** M11：loadGuild 的成员行。 */
    static final String SELECT_MEMBERS = "SELECT player_id, role, join_time_ms, last_active_ms, contribution_total,"
            + " contribution_balance FROM guild_member WHERE guild_id = ? ORDER BY player_id";

    // ---------------------------------------------------------------- A：guild_application
    /** A1：锁申请行（完整主键 FOR UPDATE，不带复核条件）；也是带复核点删之前的点锁（TiDB 规则 V2）。 */
    static final String LOCK_APPLICATION =
            "SELECT expire_ms FROM guild_application WHERE guild_id = ? AND player_id = ? FOR UPDATE";
    /** A2：新申请。IODKU 让查重直接取 X（不走普通 INSERT 的 S → X 升级）；RowsAffected 必须为 1。 */
    static final String INSERT_APPLICATION = "INSERT INTO guild_application (guild_id, player_id, apply_ms, expire_ms)"
            + " VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE apply_ms = apply_ms";
    /** A3：同帮重复申请刷新（不断言：同一毫秒双击是 0 行）。 */
    static final String REFRESH_APPLICATION =
            "UPDATE guild_application SET apply_ms = ?, expire_ms = ? WHERE guild_id = ? AND player_id = ?";
    /** A4：完整主键点删（0 行跳过）。 */
    static final String DELETE_APPLICATION = "DELETE FROM guild_application WHERE guild_id = ? AND player_id = ?";
    /** A5：删过期行（{@code expire_ms <= ?} 是提交点复核：候选读之后被刷新续期的行不删）；之前先做 A1。 */
    static final String DELETE_EXPIRED_APPLICATION =
            "DELETE FROM guild_application WHERE guild_id = ? AND player_id = ? AND expire_ms <= ?";
    /** A6：撤回只删未过期的那一行；之前先做 A1。 */
    static final String CANCEL_APPLICATION =
            "DELETE FROM guild_application WHERE guild_id = ? AND player_id = ? AND expire_ms > ?";
    /** A7：审批通过前的预读（不加锁）。 */
    static final String SELECT_APPLICATION_EXISTS = "SELECT 1 FROM guild_application WHERE guild_id = ? AND player_id = ?";
    /** A8：I2 / I3 候选（IN 按 100 分块）。 */
    static final String SELECT_APPLICATION_KEYS_OF_PLAYERS_HEAD =
            "SELECT guild_id, player_id FROM guild_application WHERE player_id IN (";
    /** A9：解散时本帮申请候选。 */
    static final String SELECT_APPLICANTS_OF_GUILD =
            "SELECT player_id FROM guild_application WHERE guild_id = ? ORDER BY player_id";
    /** A10：申请事务内本人过期行候选。 */
    static final String SELECT_EXPIRED_APPLICATIONS_OF_PLAYER =
            "SELECT guild_id FROM guild_application WHERE player_id = ? AND expire_ms <= ? ORDER BY guild_id";
    /** A11：提交后清本帮过期行的候选（LIMIT 由调用方传 {@value GuildLimits#PURGE_EXPIRED_APPLICATIONS_PER_APPLY}）。 */
    static final String SELECT_EXPIRED_APPLICANTS_OF_GUILD =
            "SELECT player_id FROM guild_application WHERE guild_id = ? AND expire_ms <= ? ORDER BY player_id LIMIT ?";
    /** A12：每人待审上限的计数（普通读：在 S(p) 下不会漏数，只可能偏大）。 */
    static final String COUNT_PENDING_APPLICATIONS_OF_PLAYER =
            "SELECT COUNT(*) FROM guild_application WHERE player_id = ? AND expire_ms > ?";
    /** A13：按 I1 数帮会有效申请（未过期且申请人没有成员行）。 */
    static final String COUNT_LIVE_APPLICATIONS_OF_GUILD = "SELECT COUNT(*) FROM guild_application a"
            + " LEFT JOIN guild_member m ON m.player_id = a.player_id"
            + " WHERE a.guild_id = ? AND a.expire_ms > ? AND m.player_id IS NULL";
    /** A14：本人申请列表。 */
    static final String LIST_MY_APPLICATIONS = "SELECT guild_id, apply_ms, expire_ms FROM guild_application"
            + " WHERE player_id = ? AND expire_ms > ? ORDER BY apply_ms DESC, guild_id ASC LIMIT ?";
    /** A15：帮会待审名单（I1 过滤）。 */
    static final String LIST_APPLICANTS = "SELECT a.player_id, a.apply_ms, a.expire_ms FROM guild_application a"
            + " LEFT JOIN guild_member m ON m.player_id = a.player_id"
            + " WHERE a.guild_id = ? AND a.expire_ms > ? AND m.player_id IS NULL"
            + " ORDER BY a.apply_ms ASC, a.player_id ASC LIMIT ?";

    private final GuildTx tx;
    private final GuildTxHooks hooks;

    /**
     * @param tx    事务基座（连接、语句超时上限、指标钩子都在它里面）
     * @param hooks 4.5 / 4.6 的事务钩子；4.4 传 {@link GuildTxHooks#NONE}
     */
    public JdbcGuildStore(GuildTx tx, GuildTxHooks hooks) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    // ================================================================ 建帮

    @Override
    public TxOutcome<Created> createGuild(GuildData guild, Deadline deadline) {
        if (guild.members().size() != 1) {
            throw new IllegalArgumentException("建帮 " + Long.toUnsignedString(guild.guildId()) + ": 必须恰好一名建帮成员，实际 "
                    + guild.members().size());
        }
        GuildData.Member leader = guild.members().getFirst();
        requirePlayer(leader.playerId(), "建帮者");
        if (guild.guildId() == 0) {
            throw new IllegalArgumentException("建帮: guild_id 不能是 0");
        }
        // 防御性重算（guild_repo.go:337-341）：服务已校验过，这里保证写库的唯一键值一定存在
        String nameNorm = GuildNames.nameNorm(guild.name());
        if (nameNorm == null) {
            throw new IllegalArgumentException("建帮 " + Long.toUnsignedString(guild.guildId()) + ": 帮名无法规范化");
        }
        // 建帮者的状态行必须在事务外建好（对不存在的行加锁读再插入会互等）
        GuildReject ensure = ensurePlayerStateRows(GuildTxOp.CREATE, guild.createTimeMs(), deadline,
                List.of(leader.playerId()));
        if (ensure != null) {
            return TxOutcome.reject(ensure);
        }
        return tx.run(GuildTxOp.CREATE, deadline, t -> {
            // 全局插入守卫：事务第一把锁，状态行表内 0 最小，排在建帮者的状态行之前
            lockGlobalInsertGuard(t);
            // 与「审批通过」抢同一个玩家：两者都要给他插成员行，由这把行锁串行
            lockPlayerState(t, leader.playerId());
            try {
                t.update(INSERT_MEMBER, guild.guildId(), leader.playerId(), leader.role(), leader.joinTimeMs(),
                        leader.lastActiveMs());
            } catch (SQLException e) {
                if (GuildSqlErrors.isDuplicateKey(e)) {
                    return TxOutcome.reject(GuildReject.ALREADY_IN_GUILD);
                }
                throw e;
            }
            // I2：成员行出现即清该玩家的全部申请（不清的话他日后离帮，72h 内的旧申请会按 I1「复活」）
            deleteApplicationsOfPlayer(t, leader.playerId());
            // 最后一条写（登记例外）：之后只剩 COMMIT
            try {
                t.update(INSERT_GUILD, guild.guildId(), guild.name(), nameNorm, guild.leaderId(), guild.level(),
                        guild.announcement(), guild.createTimeMs(), guild.maxMembers(), guild.zoneId());
            } catch (SQLException e) {
                if (GuildSqlErrors.isDuplicateKeyOn(e, GUILD_NAME_UNIQUE_KEY)) {
                    return TxOutcome.reject(GuildReject.NAME_TAKEN);
                }
                throw e; // 撞主键等其它 1062：发号出了问题，按故障
            }
            return TxOutcome.ok(new Created(guild.guildId(), leader.playerId(), guild.zoneId()));
        });
    }

    // ================================================================ 成员管理

    @Override
    public TxOutcome<RoleChanged> setMemberRole(long guildId, long actorId, long targetId, int role, OfficerCaps caps,
                                                Deadline deadline) {
        requireDistinct(actorId, targetId, "任免");
        if (!GuildRoles.assignableRole(role)) {
            throw new IllegalArgumentException("任免: role " + Integer.toUnsignedString(role) + " 不可分配");
        }
        Objects.requireNonNull(caps, "caps");
        return tx.run(GuildTxOp.SET_ROLE, deadline, t -> {
            GuildRow guild = lockGuildRow(t, guildId);
            if (guild == null) {
                return TxOutcome.reject(GuildReject.GUILD_GONE);
            }
            MemberPair pair = lockMemberPair(t, guildId, actorId, targetId);
            if (pair.reject() != null) {
                return TxOutcome.reject(pair.reject());
            }
            if (!GuildRoles.canAssignRole(pair.actorRole())) {
                return TxOutcome.reject(GuildReject.RANK_TOO_LOW);
            }
            // 防御分支：帮主的职位只能经转让变更（guild_manage_repo.go:1323-1326）
            if (GuildRoles.rank(pair.targetRole()) == GuildRoles.Rank.LEADER) {
                return TxOutcome.reject(GuildReject.RANK_TOO_LOW);
            }
            if (pair.targetRole() == role) {
                // 幂等：目标已是该角色，不写库（Changed=false）
                GuildData snapshot = loadGuild(t, guildId);
                return snapshot == null ? TxOutcome.reject(GuildReject.GUILD_GONE)
                        : TxOutcome.ok(new RoleChanged(snapshot, false, actorId, targetId));
            }
            if (role == GuildRoles.OFFICER) {
                OptionalInt maxOfficers = caps.maxOfficers(guild.level());
                if (maxOfficers.isEmpty()) {
                    return TxOutcome.reject(GuildReject.LEVEL_CONFIG_MISSING);
                }
                long officers = t.count(COUNT_MEMBERS_BY_ROLE, guildId, GuildRoles.OFFICER);
                if (Long.compareUnsigned(officers, Integer.toUnsignedLong(maxOfficers.getAsInt())) >= 0) {
                    return TxOutcome.reject(GuildReject.OFFICER_LIMIT);
                }
            }
            // 降为成员不查上限：配表缺失不该连「收权」都做不了
            t.updateExactlyOne("任免 " + id(targetId) + " @ " + id(guildId), UPDATE_MEMBER_ROLE, role, guildId, targetId);
            GuildData snapshot = loadGuild(t, guildId);
            return snapshot == null ? TxOutcome.reject(GuildReject.GUILD_GONE)
                    : TxOutcome.ok(new RoleChanged(snapshot, true, actorId, targetId));
        });
    }

    @Override
    public TxOutcome<Kicked> kickMember(long guildId, long actorId, long targetId, long nowMs, Deadline deadline) {
        requireDistinct(actorId, targetId, "踢人");
        requireNow(nowMs, "踢人");
        // G4：目标先普通读是不是本帮成员，不是就直接答复——不建状态行、不开事务（防随机 id 刷出垃圾状态行与全局守卫争用）
        GuildReject pre = tx.read(deadline, db -> {
            if (memberRoleOf(db, guildId, targetId) != null) {
                return null;
            }
            OperatorRole operator = readOperatorRole(db, guildId, actorId);
            return operator.reject() != null ? operator.reject() : GuildReject.TARGET_NOT_MEMBER;
        });
        if (pre != null) {
            return TxOutcome.reject(pre);
        }
        GuildReject ensure = ensurePlayerStateRows(GuildTxOp.KICK, nowMs, deadline, List.of(targetId));
        if (ensure != null) {
            return TxOutcome.reject(ensure);
        }
        return tx.run(GuildTxOp.KICK, deadline, t -> {
            if (lockGuildRow(t, guildId) == null) {
                return TxOutcome.reject(GuildReject.GUILD_GONE);
            }
            // 删成员行的事务先持有该玩家的状态行（与「别帮审批通过同一人」串行）
            lockPlayerState(t, targetId);
            MemberPair pair = lockMemberPair(t, guildId, actorId, targetId);
            if (pair.reject() != null) {
                return TxOutcome.reject(pair.reject());
            }
            if (!GuildRoles.canKick(pair.actorRole(), pair.targetRole())) {
                return TxOutcome.reject(GuildReject.RANK_TOO_LOW);
            }
            t.updateExactlyOne("踢出 " + id(targetId) + " @ " + id(guildId), DELETE_MEMBER, guildId, targetId);
            // I3：成员行消失即清该玩家的全部申请（持有他的状态行，候选集完整）
            deleteApplicationsOfPlayer(t, targetId);
            hooks.accelerateDonationDeadlines(t, guildId, List.of(targetId), nowMs);
            GuildData snapshot = loadGuild(t, guildId);
            return snapshot == null ? TxOutcome.reject(GuildReject.GUILD_GONE)
                    : TxOutcome.ok(new Kicked(snapshot, actorId, targetId));
        });
    }

    @Override
    public TxOutcome<Transferred> transferLeader(long guildId, long actorId, long targetId, OfficerCaps caps,
                                                 Deadline deadline) {
        requireDistinct(actorId, targetId, "转让");
        Objects.requireNonNull(caps, "caps");
        return tx.run(GuildTxOp.TRANSFER, deadline, t -> {
            GuildRow guild = lockGuildRow(t, guildId);
            if (guild == null) {
                return TxOutcome.reject(GuildReject.GUILD_GONE);
            }
            MemberPair pair = lockMemberPair(t, guildId, actorId, targetId);
            if (pair.reject() != null) {
                return TxOutcome.reject(pair.reject());
            }
            if (!GuildRoles.canTransferLeader(pair.actorRole())) {
                return TxOutcome.reject(GuildReject.RANK_TOO_LOW);
            }
            // leader_id 与 role=3 是同一事实的两份存储：对不上不猜哪份对，直接拒写（fail-closed）
            if (guild.leaderId() != actorId) {
                log.error("帮会 {} 转让: leader_id={} 与 role=3 的操作者 {} 不一致，拒写", id(guildId), id(guild.leaderId()),
                        id(actorId));
                return TxOutcome.reject(GuildReject.LEADER_MISMATCH);
            }
            OptionalInt maxOfficers = caps.maxOfficers(guild.level());
            if (maxOfficers.isEmpty()) {
                return TxOutcome.reject(GuildReject.LEVEL_CONFIG_MISSING);
            }
            long officers = t.count(COUNT_MEMBERS_BY_ROLE, guildId, GuildRoles.OFFICER);
            // 目标本来是长老：转让后他腾出一个长老位，原帮主正好补进去
            if (pair.targetRole() == GuildRoles.OFFICER && officers > 0) {
                officers--;
            }
            int oldLeaderRole = GuildRoles.demotedLeaderRole((int) Math.min(officers, 0xFFFF_FFFFL),
                    maxOfficers.getAsInt());
            // 三条写顺序固定：先 guild 行（位置 1 已持锁），再目标、再原帮主
            t.updateExactlyOne("帮会 " + id(guildId) + " 改 leader_id", UPDATE_LEADER, targetId, guildId);
            t.update(UPDATE_MEMBER_ROLE, GuildRoles.LEADER, guildId, targetId);
            t.update(UPDATE_MEMBER_ROLE, oldLeaderRole, guildId, actorId);
            long leaders = t.count(COUNT_MEMBERS_BY_ROLE, guildId, GuildRoles.LEADER);
            if (leaders != 1) {
                throw new GuildStoreException(GuildStoreException.Kind.INVARIANT_BROKEN,
                        "帮会 " + id(guildId) + " 转让后帮主人数应为 1，实际 " + leaders);
            }
            GuildData snapshot = loadGuild(t, guildId);
            return snapshot == null ? TxOutcome.reject(GuildReject.GUILD_GONE)
                    : TxOutcome.ok(new Transferred(snapshot, actorId, targetId));
        });
    }

    @Override
    public TxOutcome<Left> leaveGuild(long guildId, long playerId, long nowMs, Deadline deadline) {
        requirePlayer(playerId, "退帮者");
        requireNow(nowMs, "退帮");
        GuildReject ensure = ensurePlayerStateRows(GuildTxOp.LEAVE, nowMs, deadline, List.of(playerId));
        if (ensure != null) {
            return TxOutcome.reject(ensure);
        }
        return tx.run(GuildTxOp.LEAVE, deadline, t -> {
            GuildRow guild = lockGuildRow(t, guildId);
            if (guild == null) {
                return TxOutcome.reject(GuildReject.GUILD_GONE);
            }
            lockPlayerState(t, playerId);
            Integer role = lockMemberRole(t, guildId, playerId);
            if (role == null) {
                return TxOutcome.reject(GuildReject.NOT_MEMBER);
            }
            // 两份存储任一说他是帮主就拒绝：宁可多拒一次，也不放出「帮主已退、leader_id 还指着他」
            if (GuildRoles.rank(role) == GuildRoles.Rank.LEADER || guild.leaderId() == playerId) {
                return TxOutcome.reject(GuildReject.LEADER_CANT_LEAVE);
            }
            t.updateExactlyOne("退帮 " + id(playerId) + " @ " + id(guildId), DELETE_MEMBER, guildId, playerId);
            deleteApplicationsOfPlayer(t, playerId); // I3
            hooks.accelerateDonationDeadlines(t, guildId, List.of(playerId), nowMs);
            GuildData snapshot = loadGuild(t, guildId);
            return snapshot == null ? TxOutcome.reject(GuildReject.GUILD_GONE)
                    : TxOutcome.ok(new Left(snapshot, playerId));
        });
    }

    // ================================================================ 申请

    @Override
    public TxOutcome<Applied> applyToGuild(long guildId, long playerId, int requiredZone, long nowMs, ApplicationRules rules,
                                           Deadline deadline) {
        requirePlayer(playerId, "申请人");
        if (rules == null || rules.ttlMs() == 0 || rules.maxPerPlayer() == 0 || rules.maxPerGuild() == 0) {
            // 0 代表配表没读到：按配置错误拒绝，而不是当成「无限制」（guild_manage_repo.go:1679-1681）
            throw new IllegalArgumentException("申请: 申请规则未配置 " + rules);
        }
        GuildReject ensure = ensurePlayerStateRows(GuildTxOp.APPLY, nowMs, deadline, List.of(playerId));
        if (ensure != null) {
            return TxOutcome.reject(ensure);
        }
        long expireMs = nowMs + rules.ttlMs();
        TxOutcome<Applied> outcome = tx.run(GuildTxOp.APPLY, deadline, t -> {
            GuildRow guild = lockGuildRow(t, guildId);
            if (guild == null) {
                return TxOutcome.reject(GuildReject.GUILD_GONE);
            }
            if (requiredZone != 0 && guild.zoneId() != requiredZone) {
                return TxOutcome.reject(GuildReject.ZONE_MISMATCH);
            }
            lockPlayerState(t, playerId);
            // 非锁定读：加锁读会在 uk_guild_member 上取间隙锁，与并发审批的 INSERT 形成更多环；竞态由 I1 与审批 1062 分支兜住
            if (t.exists(SELECT_MEMBER_GUILD, playerId)) {
                return TxOutcome.reject(GuildReject.ALREADY_IN_GUILD);
            }
            purgeExpiredApplicationsOfPlayer(t, playerId, nowMs);
            // 「是否已申请过本帮」：完整主键 FOR UPDATE（挡住并发的撤回）；本人的过期行上一步已删，查得到的就是未过期行
            if (t.exists(LOCK_APPLICATION, guildId, playerId)) {
                // 刷新排在满员与两个上限之前；同一毫秒双击是 0 行，仍算刷新成功，不断言
                t.update(REFRESH_APPLICATION, nowMs, expireMs, guildId, playerId);
                return TxOutcome.ok(new Applied(false, List.of()));
            }
            long members = t.count(COUNT_MEMBERS, guildId);
            if (Long.compareUnsigned(members, Integer.toUnsignedLong(guild.maxMembers())) >= 0) {
                return TxOutcome.reject(GuildReject.GUILD_FULL);
            }
            long pending = t.count(COUNT_PENDING_APPLICATIONS_OF_PLAYER, playerId, nowMs);
            if (Long.compareUnsigned(pending, Integer.toUnsignedLong(rules.maxPerPlayer())) >= 0) {
                return TxOutcome.reject(GuildReject.APPLICATION_LIMIT);
            }
            long live = t.count(COUNT_LIVE_APPLICATIONS_OF_GUILD, guildId, nowMs);
            if (Long.compareUnsigned(live, Integer.toUnsignedLong(rules.maxPerGuild())) >= 0) {
                return TxOutcome.reject(GuildReject.QUEUE_FULL);
            }
            int inserted = t.update(INSERT_APPLICATION, guildId, playerId, nowMs, expireMs);
            if (inserted != 1) {
                // 撞上活记录：有人绕过了 S(p) 的串行化（或状态行被手工删掉）。不猜、不吞：回忙错误整体回滚，原地重试会命中刷新分支
                log.error("申请 ({},{}) 在玩家守卫下仍撞上活记录（rows affected {}），回 WRITE_CONFLICT", id(guildId),
                        id(playerId), inserted);
                return TxOutcome.reject(GuildReject.WRITE_CONFLICT);
            }
            List<Long> reviewers = t.ids(SELECT_REVIEWERS, guildId, GuildRoles.OFFICER, GuildRoles.LEADER);
            return TxOutcome.ok(new Applied(true, reviewers));
        });
        if (outcome.isOk()) {
            // 提交之后尽力清理本帮过期申请（纯卫生动作，失败只记 Info）；不失效任何缓存
            purgeExpiredApplicationsOfGuild(guildId, nowMs, deadline);
        }
        return outcome;
    }

    @Override
    public TxOutcome<Void> cancelApplication(long guildId, long playerId, long nowMs, Deadline deadline) {
        requirePlayer(playerId, "撤回者");
        return tx.run(GuildTxOp.CANCEL, deadline, t -> {
            if (!t.exists(LOCK_APPLICATION, guildId, playerId)) {
                return TxOutcome.reject(GuildReject.APPLICATION_NOT_FOUND);
            }
            if (t.update(CANCEL_APPLICATION, guildId, playerId, nowMs) == 1) {
                return TxOutcome.ok(null);
            }
            // 行在但没删到（锁在手里，只可能是已过期）：顺手删掉并提交，再回 NotFound
            t.update(DELETE_EXPIRED_APPLICATION, guildId, playerId, nowMs);
            return TxOutcome.commitThenReject(GuildReject.APPLICATION_NOT_FOUND);
        });
    }

    @Override
    public TxOutcome<Reviewed> reviewApplication(long guildId, long actorId, long applicantId, boolean approve,
                                                 int applicantZone, long nowMs, Deadline deadline) {
        requireDistinct(actorId, applicantId, "审批");
        if (approve && applicantZone == 0) {
            // 查不到申请人归属区就不许放人进来（fail-closed）
            throw new IllegalArgumentException("审批通过: 申请人 " + id(applicantId) + " 归属区未知");
        }
        if (approve) {
            // G4：申请行不在就不建状态行、不取全局插入守卫，按锁内同一优先级答复
            GuildReject pre = tx.read(deadline, db -> {
                if (db.exists(SELECT_APPLICATION_EXISTS, guildId, applicantId)) {
                    return null;
                }
                OperatorRole operator = readOperatorRole(db, guildId, actorId);
                if (operator.reject() != null) {
                    return operator.reject();
                }
                return GuildRoles.canReviewApplications(operator.role()) ? GuildReject.APPLICATION_NOT_FOUND
                        : GuildReject.RANK_TOO_LOW;
            });
            if (pre != null) {
                return TxOutcome.reject(pre);
            }
            GuildReject ensure = ensurePlayerStateRows(GuildTxOp.REVIEW, nowMs, deadline, List.of(applicantId));
            if (ensure != null) {
                return TxOutcome.reject(ensure);
            }
        }
        return tx.run(GuildTxOp.REVIEW, deadline, t -> {
            GuildRow guild = lockGuildRow(t, guildId);
            if (guild == null) {
                return TxOutcome.reject(GuildReject.GUILD_GONE);
            }
            if (approve) {
                // 只有通过分支会插成员行：先全局插入守卫 S(0)，再申请人的 S(p)（表内升序，p > 0）
                lockGlobalInsertGuard(t);
                lockPlayerState(t, applicantId);
            }
            Integer actorRole = lockMemberRole(t, guildId, actorId);
            if (actorRole == null) {
                return TxOutcome.reject(GuildReject.NOT_MEMBER);
            }
            if (!GuildRoles.canReviewApplications(actorRole)) {
                return TxOutcome.reject(GuildReject.RANK_TOO_LOW);
            }
            Long expireMs = t.one(LOCK_APPLICATION, rs -> GuildJdbc.u64(rs, 1), guildId, applicantId);
            if (expireMs == null) {
                return TxOutcome.reject(GuildReject.APPLICATION_NOT_FOUND);
            }
            if (Long.compareUnsigned(expireMs, nowMs) <= 0) {
                // 已过期：删掉并提交（直接回滚会把删除一起撤销，过期行就永远留在表里）
                t.update(DELETE_APPLICATION, guildId, applicantId);
                return TxOutcome.commitThenReject(GuildReject.APPLICATION_NOT_FOUND);
            }
            if (!approve) {
                t.update(DELETE_APPLICATION, guildId, applicantId);
                GuildData snapshot = loadGuild(t, guildId);
                return snapshot == null ? TxOutcome.reject(GuildReject.GUILD_GONE)
                        : TxOutcome.ok(new Reviewed(snapshot, false, actorId, applicantId));
            }
            // zone 复核：合服回滚只改 guild.zone_id、不动申请表，批准残留的跨区申请就造出跨区成员
            if (guild.zoneId() != applicantZone) {
                t.update(DELETE_APPLICATION, guildId, applicantId);
                return TxOutcome.commitThenReject(GuildReject.APPLICATION_NOT_FOUND);
            }
            long members = t.count(COUNT_MEMBERS, guildId);
            if (Long.compareUnsigned(members, Integer.toUnsignedLong(guild.maxMembers())) >= 0) {
                // 回滚并保留申请：人满只是暂时的
                return TxOutcome.reject(GuildReject.GUILD_FULL);
            }
            try {
                t.update(INSERT_MEMBER, guildId, applicantId, GuildRoles.MEMBER, nowMs, nowMs);
            } catch (SQLException e) {
                if (!GuildSqlErrors.isDuplicateKey(e)) {
                    throw e;
                }
                // uk_guild_member 拒绝 = 申请人已入他帮。InnoDB 只回滚这一条语句：接着删申请行再提交
                t.update(DELETE_APPLICATION, guildId, applicantId);
                return TxOutcome.commitThenReject(GuildReject.APPLICATION_NOT_FOUND);
            }
            // I2：清申请人在所有帮会的申请；已 FOR UPDATE 持有的 (G,p) 一并放进列表（不取新锁）
            deleteApplicationsOfPlayer(t, applicantId, new AppKey(guildId, applicantId));
            GuildData snapshot = loadGuild(t, guildId);
            return snapshot == null ? TxOutcome.reject(GuildReject.GUILD_GONE)
                    : TxOutcome.ok(new Reviewed(snapshot, true, actorId, applicantId));
        });
    }

    // ================================================================ 解散 / 公告

    @Override
    public TxOutcome<Disbanded> disbandGuild(long guildId, long actorId, long nowMs, ZoneFence fence, Deadline deadline) {
        requirePlayer(actorId, "解散者");
        requireNow(nowMs, "解散");
        ZoneFence zoneFence = fence == null ? ZoneFence.OPEN : fence;
        // 事务外建状态行：这次读不加锁，只决定给谁建行；帮会不存在时读到空集，照常进事务拿 GUILD_GONE
        List<Long> preread = tx.read(deadline, db -> db.ids(SELECT_MEMBER_IDS, guildId));
        GuildReject ensure = ensurePlayerStateRows(GuildTxOp.DISBAND, nowMs, deadline, preread);
        if (ensure != null) {
            return TxOutcome.reject(ensure);
        }
        return tx.run(GuildTxOp.DISBAND, deadline, t -> {
            GuildRow guild = lockGuildRow(t, guildId);
            if (guild == null) {
                return TxOutcome.reject(GuildReject.GUILD_GONE);
            }
            // 授权只读 MySQL 的 leader_id（不看 role；缓存在转让之后最长陈旧一个 TTL）
            if (guild.leaderId() != actorId) {
                return TxOutcome.reject(GuildReject.RANK_TOO_LOW);
            }
            // 事务内闸门：用行里的 zone_id（事务外那次查的是请求者归属区，有检查到使用的窗口）
            if (fenceRejects(zoneFence, guild.zoneId(), guildId)) {
                return TxOutcome.reject(GuildReject.ZONE_MERGING);
            }
            List<Long> members = lockAllMembers(t, guildId);
            // 删成员行紧跟锁成员行、排在删申请之前（否则持着申请行去等 uk 上的 S next-key 会成环，guild_manage_repo.go:2236-2242）
            for (long member : members) {
                t.updateExactlyOne("解散 " + id(guildId) + " 删成员 " + id(member), DELETE_MEMBER, guildId, member);
            }
            // I3：本帮的待审申请 + 成员们在别帮的申请，合成一个列表整体按主键升序删（两个解散会交叉删对方的行）
            List<AppKey> keys = new ArrayList<>();
            for (long applicant : t.ids(SELECT_APPLICANTS_OF_GUILD, guildId)) {
                keys.add(new AppKey(guildId, applicant));
            }
            keys.addAll(applicationKeysOfPlayers(t, members));
            deleteApplicationRows(t, keys);
            hooks.accelerateDonationDeadlines(t, guildId, members, nowMs);
            hooks.deleteGuildActivityProgress(t, guildId);
            int deleted = t.update(DELETE_GUILD, guildId);
            if (deleted == 0) {
                return TxOutcome.reject(GuildReject.GUILD_GONE);
            }
            if (deleted > 1) {
                throw new GuildStoreException(GuildStoreException.Kind.ROW_COUNT_MISMATCH,
                        "解散 " + id(guildId) + " 删掉了 " + deleted + " 行帮会");
            }
            return TxOutcome.ok(new Disbanded(guildId, guild.zoneId(), actorId, members));
        });
    }

    @Override
    public TxOutcome<AnnouncementUpdated> updateAnnouncement(long guildId, long playerId, String announcement,
                                                             Deadline deadline) {
        Objects.requireNonNull(announcement, "announcement");
        return tx.run(GuildTxOp.ANNOUNCEMENT, deadline, t -> {
            if (lockGuildRow(t, guildId) == null) {
                return TxOutcome.reject(GuildReject.GUILD_GONE);
            }
            Integer role = lockMemberRole(t, guildId, playerId);
            if (role == null || !GuildRoles.canSetAnnouncement(role)) {
                return TxOutcome.reject(GuildReject.ANNOUNCEMENT_FORBIDDEN);
            }
            t.update(UPDATE_ANNOUNCEMENT, announcement, guildId);
            GuildData snapshot = loadGuild(t, guildId);
            return snapshot == null ? TxOutcome.reject(GuildReject.GUILD_GONE)
                    : TxOutcome.ok(new AnnouncementUpdated(snapshot, playerId));
        });
    }

    // ================================================================ 读

    @Override
    public Optional<GuildData> loadGuild(long guildId, Deadline deadline) {
        return Optional.ofNullable(tx.read(deadline, db -> loadGuild(db, guildId)));
    }

    @Override
    public long playerGuildId(long playerId, Deadline deadline) {
        Long guildId = tx.read(deadline, db -> db.one(SELECT_MEMBER_GUILD, rs -> GuildJdbc.u64(rs, 1), playerId));
        return guildId == null ? 0 : guildId;
    }

    @Override
    public OptionalInt memberRole(long guildId, long playerId, Deadline deadline) {
        Integer role = tx.read(deadline, db -> memberRoleOf(db, guildId, playerId));
        return role == null ? OptionalInt.empty() : OptionalInt.of(role);
    }

    @Override
    public OptionalInt guildZoneId(long guildId, Deadline deadline) {
        Integer zone = tx.read(deadline, db -> db.one(SELECT_GUILD_ZONE, rs -> GuildJdbc.u32(rs, 1), guildId));
        return zone == null ? OptionalInt.empty() : OptionalInt.of(zone);
    }

    @Override
    public long countLiveApplications(long guildId, long nowMs, Deadline deadline) {
        return tx.read(deadline, db -> db.count(COUNT_LIVE_APPLICATIONS_OF_GUILD, guildId, nowMs));
    }

    @Override
    public List<ApplicationRow> listMyApplications(long playerId, long nowMs, int limit, Deadline deadline) {
        if (limit <= 0) {
            return List.of();
        }
        return tx.read(deadline, db -> db.list(LIST_MY_APPLICATIONS,
                rs -> new ApplicationRow(GuildJdbc.u64(rs, 1), GuildJdbc.u64(rs, 2), GuildJdbc.u64(rs, 3)),
                playerId, nowMs, limit));
    }

    @Override
    public List<ApplicantRow> listApplicants(long guildId, long nowMs, int limit, Deadline deadline) {
        if (limit <= 0) {
            return List.of();
        }
        return tx.read(deadline, db -> db.list(LIST_APPLICANTS,
                rs -> new ApplicantRow(GuildJdbc.u64(rs, 1), GuildJdbc.u64(rs, 2), GuildJdbc.u64(rs, 3)),
                guildId, nowMs, limit));
    }

    @Override
    public List<GuildScore> allGuildScores(Deadline deadline) {
        return tx.read(deadline, db -> db.list(SELECT_ALL_SCORES, JdbcGuildStore::scoreRow));
    }

    @Override
    public void scanGuildScores(Deadline deadline, Consumer<GuildScore> sink) {
        tx.read(deadline, db -> {
            db.forEach(SELECT_ALL_SCORES, JdbcGuildStore::scoreRow, sink);
            return null;
        });
    }

    @Override
    public Optional<GuildScore> guildScore(long guildId, Deadline deadline) {
        return Optional.ofNullable(tx.read(deadline, db -> db.one(SELECT_GUILD_SCORE, JdbcGuildStore::scoreRow, guildId)));
    }

    // ================================================================ 状态行与守卫

    /**
     * 保证这些玩家的状态行存在（ensurePlayerStateRows，guild_manage_repo.go:976-1029）。<b>调用时本线程不得持有任何事务</b>
     * （它自己的短事务要拿全局守卫，嵌在别的事务里就是「持业务行锁等全局锁」）。
     *
     * <ol>
     *   <li>id 按无符号排序去重；含 0（守卫本身，不是玩家）→ {@link IllegalArgumentException}，不碰库；</li>
     *   <li>普通读（IN 按 100 分块）挑出已存在的行，全在就返回（常态）；</li>
     *   <li>有缺的：一个 RC 短事务（op 用调用方的）：点锁 S(0) → 守卫下再普通读一次 → 对仍缺的按升序 INSERT IGNORE → 提交。</li>
     * </ol>
     *
     * @return null = 行都在；否则是事务的拒绝（只可能是 {@link GuildReject#WRITE_CONFLICT}）。哨兵行缺失抛 {@link GuildStoreException}。
     */
    GuildReject ensurePlayerStateRows(GuildTxOp op, long nowMs, Deadline deadline, List<Long> playerIds) {
        List<Long> ids = sortedUnique(playerIds);
        if (!ids.isEmpty() && ids.getFirst() == GLOBAL_INSERT_GUARD_PLAYER_ID) {
            throw new IllegalArgumentException("补建状态行: player_id 0 是全局插入守卫，不是玩家");
        }
        if (ids.isEmpty()) {
            return null;
        }
        List<Long> missing = tx.read(deadline, db -> missingPlayerStateRows(db, ids));
        if (missing.isEmpty()) {
            return null;
        }
        TxOutcome<Void> outcome = tx.run(op, deadline, t -> {
            lockGlobalInsertGuard(t);
            // 守卫下复读：别的首建者也都在这把守卫下建行、持到提交，拿到守卫时它们的行都已提交、看得见
            for (long playerId : missingPlayerStateRows(t, missing)) {
                t.update(ENSURE_PLAYER_STATE, playerId, nowMs);
            }
            return TxOutcome.ok(null);
        });
        return outcome.rejection();
    }

    /** ids 里哪些状态行还不存在（普通读，按入参顺序返回缺的那些）。ids 须已无符号升序去重。 */
    static List<Long> missingPlayerStateRows(GuildJdbc db, List<Long> ids) throws SQLException {
        Set<Long> existing = new HashSet<>();
        for (int from = 0; from < ids.size(); from += GuildLimits.IN_LIST_CHUNK) {
            List<Long> batch = ids.subList(from, Math.min(ids.size(), from + GuildLimits.IN_LIST_CHUNK));
            existing.addAll(db.ids(SELECT_EXISTING_PLAYER_STATES_HEAD + placeholders(batch.size()) + ")",
                    batch.toArray()));
        }
        List<Long> missing = new ArrayList<>();
        for (long id : ids) {
            if (!existing.contains(id)) {
                missing.add(id);
            }
        }
        return missing;
    }

    /** 事务内点锁全局插入守卫（S2，player_id = 0）。行不在 → {@link GuildStoreException}（部署问题，绝不在请求路径上补建）。 */
    static void lockGlobalInsertGuard(GuildJdbc t) throws SQLException {
        if (!t.exists(LOCK_PLAYER_STATE, GLOBAL_INSERT_GUARD_PLAYER_ID)) {
            throw new GuildStoreException(GuildStoreException.Kind.GLOBAL_INSERT_GUARD_MISSING,
                    "全局插入守卫哨兵行缺失（guild_player_state.player_id=0），检查启动期 ensureGlobalInsertGuard");
        }
    }

    /** 事务内点锁玩家状态行（S2）。行必须已由 {@link #ensurePlayerStateRows} 建好；缺行 → {@link GuildStoreException}。 */
    static void lockPlayerState(GuildJdbc t, long playerId) throws SQLException {
        if (!t.exists(LOCK_PLAYER_STATE, playerId)) {
            throw new GuildStoreException(GuildStoreException.Kind.PLAYER_STATE_ROW_MISSING,
                    "玩家 " + id(playerId) + " 的 guild_player_state 行缺失（事务外建行被跳过）");
        }
    }

    // ================================================================ 公共加锁助手

    /** G1 读到的权威帮会字段。 */
    private record GuildRow(int level, long leaderId, int zoneId, int maxMembers) {
    }

    private static GuildRow lockGuildRow(GuildJdbc t, long guildId) throws SQLException {
        return t.one(LOCK_GUILD, rs -> new GuildRow(GuildJdbc.u32(rs, 1), GuildJdbc.u64(rs, 2), GuildJdbc.u32(rs, 3),
                GuildJdbc.u32(rs, 4)), guildId);
    }

    /** M1：点锁一行成员，返回权威 role；行不存在返回 null（RC 下未命中不加锁）。 */
    private static Integer lockMemberRole(GuildJdbc t, long guildId, long playerId) throws SQLException {
        return t.one(LOCK_MEMBER_ROLE, rs -> GuildJdbc.u32(rs, 1), guildId, playerId);
    }

    private record MemberPair(int actorRole, int targetRole, GuildReject reject) {
    }

    /**
     * 锁住操作者与目标的成员行（lockMemberPair，guild_manage_repo.go:1076-1121）：按 player_id 无符号升序两次 M1，<b>两行都锁完才判存在性</b>：
     * 操作者不在 → NOT_MEMBER，其次目标不在 → TARGET_NOT_MEMBER。actor == target 时只锁一次：在 → TARGET_NOT_MEMBER、不在 → NOT_MEMBER。
     */
    private static MemberPair lockMemberPair(GuildJdbc t, long guildId, long actorId, long targetId) throws SQLException {
        if (actorId == targetId) {
            Integer role = lockMemberRole(t, guildId, actorId);
            return new MemberPair(0, 0, role == null ? GuildReject.NOT_MEMBER : GuildReject.TARGET_NOT_MEMBER);
        }
        boolean actorLow = Long.compareUnsigned(actorId, targetId) < 0;
        Integer lowRole = lockMemberRole(t, guildId, actorLow ? actorId : targetId);
        Integer highRole = lockMemberRole(t, guildId, actorLow ? targetId : actorId);
        Integer actorRole = actorLow ? lowRole : highRole;
        Integer targetRole = actorLow ? highRole : lowRole;
        if (actorRole == null) {
            return new MemberPair(0, 0, GuildReject.NOT_MEMBER);
        }
        if (targetRole == null) {
            return new MemberPair(0, 0, GuildReject.TARGET_NOT_MEMBER);
        }
        return new MemberPair(actorRole, targetRole, null);
    }

    /**
     * 锁住该帮全体成员的状态行与成员行（lockAllMembers，guild_manage_repo.go:2355-2384），返回 player_id 无符号升序列表。
     * 前置：已持有该帮 guild 行锁（成员集合稳定）。点不到成员行 = 有路径不锁 guild 行就增删成员，回内部错误整体回滚。
     */
    private static List<Long> lockAllMembers(GuildJdbc t, long guildId) throws SQLException {
        List<Long> members = t.ids(SELECT_MEMBER_IDS, guildId);
        for (long member : members) {
            lockPlayerState(t, member);
        }
        for (long member : members) {
            if (lockMemberRole(t, guildId, member) == null) {
                throw new GuildStoreException(GuildStoreException.Kind.INVARIANT_BROKEN,
                        "帮会 " + id(guildId) + " 的成员 " + id(member) + " 在 guild 行锁下消失了");
            }
        }
        return members;
    }

    private record OperatorRole(int role, GuildReject reject) {
    }

    /**
     * 非锁定读操作者在本帮的 role（readOperatorRole，guild_manage_repo.go:1488-1502）：帮会不在 → GUILD_GONE，操作者不在 → NOT_MEMBER。
     * 只用于「操作对象不存在」时组织答复（两个 G4 预读），<b>不</b>用于授权。
     */
    private static OperatorRole readOperatorRole(GuildJdbc db, long guildId, long actorId) throws SQLException {
        if (!db.exists(SELECT_GUILD_ZONE, guildId)) {
            return new OperatorRole(0, GuildReject.GUILD_GONE);
        }
        Integer role = memberRoleOf(db, guildId, actorId);
        return role == null ? new OperatorRole(0, GuildReject.NOT_MEMBER) : new OperatorRole(role, null);
    }

    private static Integer memberRoleOf(GuildJdbc db, long guildId, long playerId) throws SQLException {
        return db.one(SELECT_MEMBER_ROLE, rs -> GuildJdbc.u32(rs, 1), guildId, playerId);
    }

    /**
     * 装配一份完整快照（loadGuild，guild_repo.go:460-498）：G9 再 M11，成员按 player_id 升序；帮会行不存在返回 null。
     * 事务里调用即 txSnapshot：读得到本事务自己的写。
     */
    static GuildData loadGuild(GuildJdbc db, long guildId) throws SQLException {
        GuildData head = db.one(SELECT_GUILD, rs -> new GuildData(GuildJdbc.u64(rs, 1), rs.getString(2),
                GuildJdbc.u64(rs, 3), GuildJdbc.u32(rs, 4), rs.getString(5), GuildJdbc.u64(rs, 6), GuildJdbc.u32(rs, 7),
                GuildJdbc.u32(rs, 8), rs.getLong(9), GuildJdbc.u64(rs, 10), List.of()), guildId);
        if (head == null) {
            return null;
        }
        List<GuildData.Member> members = db.list(SELECT_MEMBERS, rs -> new GuildData.Member(GuildJdbc.u64(rs, 1),
                GuildJdbc.u32(rs, 2), GuildJdbc.u64(rs, 3), GuildJdbc.u64(rs, 4), GuildJdbc.u64(rs, 5),
                GuildJdbc.u64(rs, 6)), guildId);
        return new GuildData(head.guildId(), head.name(), head.leaderId(), head.level(), head.announcement(),
                head.createTimeMs(), head.maxMembers(), head.zoneId(), head.score(), head.funds(), members);
    }

    private static GuildScore scoreRow(java.sql.ResultSet rs) throws SQLException {
        return new GuildScore(GuildJdbc.u64(rs, 1), GuildJdbc.u32(rs, 2), rs.getLong(3));
    }

    /** 事务内合服闸门：合服中或读不出来（fail-closed）都拒绝（checkFence，economy_repo.go:323-335；4.5 的经济事务共用）。 */
    static boolean fenceRejects(ZoneFence fence, int zoneId, long guildId) {
        try {
            return fence.merging(zoneId);
        } catch (Exception e) {
            log.error("帮会 {} 的合服闸门读失败（zone {}），按合服中拒绝: {}", id(guildId), Integer.toUnsignedString(zoneId),
                    e.toString());
            return true;
        }
    }

    // ================================================================ 申请行的「候选普通读 + 主键点删」

    /** guild_application 的完整主键。 */
    record AppKey(long guildId, long playerId) {
    }

    /** 主键序 (guild_id, player_id)，均无符号。 */
    static int compareAppKeys(AppKey a, AppKey b) {
        int c = Long.compareUnsigned(a.guildId(), b.guildId());
        return c != 0 ? c : Long.compareUnsigned(a.playerId(), b.playerId());
    }

    /**
     * 普通读这些玩家名下全部申请的主键（A8，IN 按 100 分块）。候选集完整的前提：调用方已持有这些玩家的状态行锁（能给他们插申请的
     * 只有本人的申请事务，它必须先拿同一把锁）。
     */
    private static List<AppKey> applicationKeysOfPlayers(GuildJdbc t, List<Long> playerIds) throws SQLException {
        List<AppKey> keys = new ArrayList<>();
        for (int from = 0; from < playerIds.size(); from += GuildLimits.IN_LIST_CHUNK) {
            List<Long> batch = playerIds.subList(from, Math.min(playerIds.size(), from + GuildLimits.IN_LIST_CHUNK));
            keys.addAll(t.list(SELECT_APPLICATION_KEYS_OF_PLAYERS_HEAD + placeholders(batch.size()) + ")",
                    rs -> new AppKey(GuildJdbc.u64(rs, 1), GuildJdbc.u64(rs, 2)), batch.toArray()));
        }
        return keys;
    }

    /** 按主键无符号升序逐行点删（A4），排序去重；影响 0 行 = 已被并发的撤回 / 拒绝 / 清理删掉，跳过。 */
    private static void deleteApplicationRows(GuildJdbc t, List<AppKey> keys) throws SQLException {
        List<AppKey> sorted = new ArrayList<>(keys);
        sorted.sort(JdbcGuildStore::compareAppKeys);
        AppKey previous = null;
        for (AppKey key : sorted) {
            if (key.equals(previous)) {
                continue;
            }
            previous = key;
            t.update(DELETE_APPLICATION, key.guildId(), key.playerId());
        }
    }

    /**
     * 单个玩家的 I2 / I3（deleteApplicationsOfPlayer）：前置已持有该玩家的状态行锁。{@code alsoHeld} 是本事务已 FOR UPDATE 持有的行
     * （审批通过的那一条），一并放进删除列表（再删一次不取新锁，也保证它一定被删）。
     */
    private static void deleteApplicationsOfPlayer(GuildJdbc t, long playerId, AppKey... alsoHeld) throws SQLException {
        List<AppKey> keys = applicationKeysOfPlayers(t, List.of(playerId));
        Collections.addAll(keys, alsoHeld);
        deleteApplicationRows(t, keys);
    }

    /**
     * 申请事务内删本人的过期申请（purgeExpiredApplicationsOfPlayer，guild_manage_repo.go:1808-1837）：普通读候选（A10）→ 按 guild_id
     * 升序逐行「A1 点锁 → A5 带复核点删」；点锁读不到 = 已被别人删掉，跳过。
     */
    private static void purgeExpiredApplicationsOfPlayer(GuildJdbc t, long playerId, long nowMs) throws SQLException {
        for (long guildId : t.ids(SELECT_EXPIRED_APPLICATIONS_OF_PLAYER, playerId, nowMs)) {
            if (!t.exists(LOCK_APPLICATION, guildId, playerId)) {
                continue;
            }
            t.update(DELETE_EXPIRED_APPLICATION, guildId, playerId, nowMs);
        }
    }

    /**
     * 申请提交<b>之后</b>尽力清理本帮过期申请（purgeExpiredApplicationsOfGuild，guild_manage_repo.go:1852-1901）：A11 至多
     * {@value GuildLimits#PURGE_EXPIRED_APPLICATIONS_PER_APPLY} 个候选，每行一个 RC 短事务（op=apply）：A1 → A5。
     * 纯卫生动作（计数与列表本来就按 expire_ms 过滤）：任一失败只记 Info 并停止本轮，剩下的留给下一次申请。
     */
    void purgeExpiredApplicationsOfGuild(long guildId, long nowMs, Deadline deadline) {
        List<Long> candidates;
        try {
            candidates = tx.read(deadline, db -> db.ids(SELECT_EXPIRED_APPLICANTS_OF_GUILD, guildId, nowMs,
                    GuildLimits.PURGE_EXPIRED_APPLICATIONS_PER_APPLY));
        } catch (RuntimeException e) {
            log.info("清理帮会 {} 的过期申请：读候选失败: {}", id(guildId), e.toString());
            return;
        }
        for (long playerId : candidates) {
            TxOutcome<Void> outcome;
            try {
                outcome = tx.run(GuildTxOp.APPLY, deadline, t -> {
                    if (t.exists(LOCK_APPLICATION, guildId, playerId)) {
                        t.update(DELETE_EXPIRED_APPLICATION, guildId, playerId, nowMs);
                    }
                    return TxOutcome.ok(null);
                });
            } catch (RuntimeException e) {
                log.info("清理帮会 {} 的过期申请 ({},{}) 失败: {}", id(guildId), id(guildId), id(playerId), e.toString());
                return;
            }
            if (!outcome.isOk()) {
                log.info("清理帮会 {} 的过期申请 ({},{}) 失败: {}", id(guildId), id(guildId), id(playerId),
                        outcome.rejection());
                return;
            }
        }
    }

    // ================================================================ 小工具

    /** 按无符号升序去重。 */
    public static List<Long> sortedUnique(List<Long> ids) {
        List<Long> sorted = new ArrayList<>(ids);
        sorted.sort(Long::compareUnsigned);
        List<Long> out = new ArrayList<>(sorted.size());
        for (Long id : sorted) {
            if (out.isEmpty() || !out.getLast().equals(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** n 个 "?" 的逗号列表（n ≥ 1）。 */
    public static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    private static void requirePlayer(long playerId, String what) {
        if (playerId == 0) {
            throw new IllegalArgumentException(what + " 的 player_id 不能是 0");
        }
    }

    private static void requireDistinct(long actorId, long targetId, String what) {
        requirePlayer(actorId, what + "操作者");
        if (actorId == targetId) {
            throw new IllegalArgumentException(what + ": 操作者与目标必须不同 " + id(actorId));
        }
    }

    /** now 必须 &gt; 0：4.5 的提前截止把 deadline_ms 改成 now，0 在 guild_asset_op 表示「永不中止」（economy_repo.go:402-405）。 */
    private static void requireNow(long nowMs, String what) {
        if (nowMs == 0) {
            throw new IllegalArgumentException(what + ": now 必须 > 0");
        }
    }

    private static String id(long unsigned) {
        return Long.toUnsignedString(unsigned);
    }
}
