package com.game.guild.service;

import com.game.common.deadline.Deadline;
import com.game.guild.cache.GuildCache;
import com.game.guild.cache.pb.GuildSnapshot;
import com.game.guild.push.GuildPushes;
import com.game.guild.rank.GuildRanks;
import com.game.guild.rules.GuildAnnouncements;
import com.game.guild.rules.GuildLimits;
import com.game.guild.rules.GuildNames;
import com.game.guild.rules.GuildReject;
import com.game.guild.rules.GuildRoles;
import com.game.guild.rules.GuildTip;
import com.game.guild.service.GuildAccess.Membership;
import com.game.guild.service.GuildAccess.ZoneCheck;
import com.game.guild.store.GuildData;
import com.game.guild.store.GuildStore;
import com.game.guild.store.GuildStore.AnnouncementUpdated;
import com.game.guild.store.GuildStore.Created;
import com.game.guild.store.GuildStore.Disbanded;
import com.game.guild.store.GuildStore.Left;
import com.game.guild.store.TxOutcome;
import com.game.proto.guild.CreateGuildRequest;
import com.game.proto.guild.CreateGuildResponse;
import com.game.proto.guild.DisbandGuildRequest;
import com.game.proto.guild.DisbandGuildResponse;
import com.game.proto.guild.GetGuildRequest;
import com.game.proto.guild.GetGuildResponse;
import com.game.proto.guild.GetPlayerGuildRequest;
import com.game.proto.guild.GetPlayerGuildResponse;
import com.game.proto.guild.GuildChangeKind;
import com.game.proto.guild.LeaveGuildRequest;
import com.game.proto.guild.LeaveGuildResponse;
import com.game.proto.guild.SetAnnouncementRequest;
import com.game.proto.guild.SetAnnouncementResponse;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 帮会核心：建 / 查 / 查本人 / 退 / 解散 / 公告（基线 guild_logic.go:193-556；guild-spec §3.2–§3.7）。
 *
 * <p>每个方法的前置按基线的<b>实际执行顺序</b>排列，顺序即错误优先级（§3）：会话（派发器已保证 {@code me ≠ 0}）→ 本方法的参数级检查 →
 * 归属区（14012 / 故障）→ 合服闸门（14013）→ 业务前置 → 事务。提交之后：先同步失效缓存，再清榜（解散）/ 入榜（建帮），最后推送；
 * 失效与推送的失败绝不改变已提交的结果（§0.6 第 5 条）。
 *
 * <p>结局三种形态：成功应答体；业务拒绝 → 应答体 error_message（in-band tip，带基线英文原因串）；故障 → 抛异常，派发器回信封 1003。
 * Java 只有客户端来源（D12）：请求体里的 player_id / zone_id 一律忽略，与会话不一致只打 ERROR（guild_logic.go:139-148）。
 *
 * <p>全部方法阻塞，只在 guild-worker 线程上调用；每个调用带一个整请求预算 {@link Deadline}。线程安全。
 */
public final class GuildService {

    private static final Logger log = LoggerFactory.getLogger(GuildService.class);

    private final GuildStore store;
    private final GuildCache cache;
    private final GuildAccess access;
    private final GuildViews views;
    private final GuildRanks ranks;
    private final GuildPushes pushes;
    private final LongSupplier guildIds;
    private final GuildTableLookup tables;
    private final LongSupplier clockMs;

    /**
     * @param guildIds 发 guild_id（生产 {@code GuildIds::nextId}；抛异常或返回 0 = 发号器不可用 → 14008）
     * @param clockMs  本服务唯一的「现在」（Unix 毫秒，guild_manage_logic.go:200-202）
     */
    public GuildService(GuildStore store, GuildCache cache, GuildAccess access, GuildViews views, GuildRanks ranks,
                        GuildPushes pushes, LongSupplier guildIds, GuildTableLookup tables, LongSupplier clockMs) {
        this.store = store;
        this.cache = cache;
        this.access = access;
        this.views = views;
        this.ranks = ranks;
        this.pushes = pushes;
        this.guildIds = guildIds;
        this.tables = tables;
        this.clockMs = clockMs;
    }

    // ================================================================ CreateGuild（15）

    /**
     * 建帮（guild_logic.go:193-291；§3.2）。前置：帮名（14009，先于任何外部查询）→ 归属区（覆盖请求体 zone_id）→ 闸门（放在发号之前）→
     * GuildLevel[1]（缺行 → 故障）→ 已在帮预检（14000）→ 发号（14008）→ 事务（14000 / 14010 / 14021）。提交后入榜（失败只打 ERROR），
     * 不推送（帮里只有建帮者，他手上的响应就是最新状态）；回包用内存构造的帮会（不重读库）。
     */
    public CreateGuildResponse createGuild(long me, CreateGuildRequest request, Deadline deadline) {
        bodyIdentity("CreateGuild", me, request.getPlayerId());
        String name = GuildNames.displayName(request.getName());
        if (name == null) {
            return createTip(GuildTip.INVALID_GUILD_NAME);
        }
        ZoneCheck zone = access.clientZone(me, deadline);
        if (zone.rejected()) {
            return createTip(zone.tip());
        }
        // 闸门在发号与任何写之前：合服中的 zone 连号都不发
        GuildTip fenced = access.mergeFenceTip(zone.zoneId());
        if (fenced != null) {
            return createTip(fenced);
        }
        // 读表在闸门之后、发号之前：配表缺行时既不越过闸门，也不白烧一个 guild_id
        OptionalInt maxMembers = tables.initialMaxMembers();
        if (maxMembers.isEmpty()) {
            log.error("[guild] GuildLevel row {} missing, cannot decide the member cap of a new guild",
                    GuildLimits.DEFAULT_INIT_LEVEL);
            throw new GuildFaultException("GuildLevel row " + GuildLimits.DEFAULT_INIT_LEVEL + " missing");
        }
        // 缓存预检只是省一次事务，不是判据：缓存说已入帮时先用 MySQL 复核（真正的判定在事务的唯一索引上）
        if (access.alreadyInGuild(me, deadline)) {
            return createTip(GuildTip.ALREADY_IN_GUILD);
        }
        long now = clockMs.getAsLong();
        long guildId = mintGuildId(me);
        if (guildId == 0) {
            return createTip(GuildTip.ID_GENERATOR_UNAVAILABLE);
        }
        GuildData guild = new GuildData(guildId, name, me, GuildLimits.DEFAULT_INIT_LEVEL, "", now, maxMembers.getAsInt(),
                zone.zoneId(), 0, 0, List.of(new GuildData.Member(me, GuildRoles.LEADER, now, now, 0, 0)));
        TxOutcome<Created> outcome = store.createGuild(guild, deadline);
        if (!(outcome instanceof TxOutcome.Ok<Created> ok)) {
            // 建帮者按预检不在任何帮：cached 传 0（ALREADY_IN_GUILD 顺手 verifyMapping(p, 0)，guild_logic.go:270-273）；撞名时这次发的号作废
            return createTip(access.replyFor(me, guildId, 0, outcome.rejection(), deadline));
        }
        access.invalidate(ok.value().invalidation(), deadline);
        // 新帮以 0 分入榜：失败可容忍（权威分在 MySQL，缺口由下次启动的重建自愈；GuildRanks 永不抛）
        ranks.add(guildId, zone.zoneId(), 0, deadline);
        log.info("[guild] CreateGuild ok guild={} leader={} zone={}", Long.toUnsignedString(guildId),
                Long.toUnsignedString(me), Integer.toUnsignedString(zone.zoneId()));
        return CreateGuildResponse.newBuilder().setGuild(views.guildInfo(guild, me, deadline)).build();
    }

    /**
     * 发号（基线 mintGuildID，guild_logic.go:322-340）：拿不到号就整体失败，绝不用 0 或自造 id。
     *
     * @return 0 = 发号器不可用（调用方回 14008）
     */
    private long mintGuildId(long me) {
        try {
            long id = guildIds.getAsLong();
            if (id == 0) {
                log.error("[guild] CreateGuild: id generator returned 0 (player={})", Long.toUnsignedString(me));
            }
            return id;
        } catch (RuntimeException e) {
            log.error("[guild] CreateGuild: id generator refused to mint (player={}): {}", Long.toUnsignedString(me),
                    e.toString());
            return 0;
        }
    }

    private static CreateGuildResponse createTip(GuildTip tip) {
        return CreateGuildResponse.newBuilder().setErrorMessage(tip.proto()).build();
    }

    // ================================================================ GetGuild（60）

    /**
     * 查帮（guild_logic.go:342-365；§3.3）：归属区 → 读快照（缓存 + 代次；出错 → 故障）→ 不存在或不在本区 → 14001（别区的帮与不存在的帮同一答复）。
     * 无闸门、无推送；查别人的帮时 viewer 不是成员，待审数为 0。
     */
    public GetGuildResponse getGuild(long me, GetGuildRequest request, Deadline deadline) {
        ZoneCheck zone = access.clientZone(me, deadline);
        if (zone.rejected()) {
            return GetGuildResponse.newBuilder().setErrorMessage(zone.tip().proto()).build();
        }
        Optional<GuildSnapshot> guild = cache.guild(request.getGuildId(), deadline);
        if (guild.isEmpty() || guild.get().getZoneId() != zone.zoneId()) {
            return GetGuildResponse.newBuilder().setErrorMessage(GuildTip.GUILD_NOT_FOUND.proto()).build();
        }
        return GetGuildResponse.newBuilder()
                .setGuild(views.guildInfo(GuildSnapshots.toData(guild.get()), me, deadline)).build();
    }

    // ================================================================ GetPlayerGuild（35）

    /**
     * 查本人所在帮（guild_logic.go:367-388 客户端路径；§3.4）：<b>不查归属区、不按区过滤</b>（自己的帮永远看得见）。权威解析
     * （缓存说没入帮或快照里没有本人时用 MySQL 复核）；解析报错 → 以 M5 复核：此刻不在帮 → 14002，否则原错误照回（故障）。
     */
    public GetPlayerGuildResponse getPlayerGuild(long me, GetPlayerGuildRequest request, Deadline deadline) {
        bodyIdentity("GetPlayerGuild", me, request.getPlayerId());
        Optional<GuildSnapshot> guild;
        try {
            guild = cache.resolve(me, deadline);
        } catch (RuntimeException e) {
            GuildTip tip = access.leftGuildWhileResolving(me, 0, e, deadline);
            if (tip != null) {
                return GetPlayerGuildResponse.newBuilder().setErrorMessage(tip.proto()).build();
            }
            throw e;
        }
        if (guild.isEmpty()) {
            return GetPlayerGuildResponse.newBuilder().setErrorMessage(GuildTip.NOT_IN_ANY_GUILD.proto()).build();
        }
        return GetPlayerGuildResponse.newBuilder()
                .setGuild(views.guildInfo(GuildSnapshots.toData(guild.get()), me, deadline)).build();
    }

    // ================================================================ LeaveGuild（29）

    /**
     * 退帮（guild_logic.go:423-466；§3.5）：归属区 → 闸门 → operatorGuild（14002）→ 事务。事务说「不是成员 / 帮已没了」时以 MySQL 复核：
     * 复核为 0 → <b>幂等成功</b>；复核为别的帮 → 14000「guild membership changed, retry」（绝不按缓存里的旧 guild_id 去删别帮的成员行）；
     * 复核出错 → 故障。成功后推 MEMBER_LEFT（actor = target = 自己）给剩余全体。
     */
    public LeaveGuildResponse leaveGuild(long me, LeaveGuildRequest request, Deadline deadline) {
        bodyIdentity("LeaveGuild", me, request.getPlayerId());
        ZoneCheck zone = access.clientWrite(me, deadline);
        if (zone.rejected()) {
            return LeaveGuildResponse.newBuilder().setErrorMessage(zone.tip().proto()).build();
        }
        Membership membership = access.operatorGuild(me, deadline);
        if (membership.rejected()) {
            return LeaveGuildResponse.newBuilder().setErrorMessage(membership.tip().proto()).build();
        }
        long guildId = membership.guildId();
        TxOutcome<Left> outcome = store.leaveGuild(guildId, me, clockMs.getAsLong(), deadline);
        if (!(outcome instanceof TxOutcome.Ok<Left> ok)) {
            GuildReject reject = outcome.rejection();
            if (reject == GuildReject.NOT_MEMBER || reject == GuildReject.GUILD_GONE) {
                long current = cache.verifyGuildIdOf(me, guildId, deadline);
                if (current == 0) {
                    return LeaveGuildResponse.getDefaultInstance();
                }
                log.error("[guild] player {} guild mapping changed from stale {} to authoritative {}; retrying is required",
                        Long.toUnsignedString(me), Long.toUnsignedString(guildId), Long.toUnsignedString(current));
                return LeaveGuildResponse.newBuilder().setErrorMessage(GuildTip.MEMBERSHIP_CHANGED.proto()).build();
            }
            return LeaveGuildResponse.newBuilder()
                    .setErrorMessage(access.replyFor(me, guildId, guildId, reject, deadline).proto()).build();
        }
        Left left = ok.value();
        access.invalidate(left.invalidation(), deadline);
        pushes.notify(GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_LEFT, guildId, me, me, left.pushRecipients());
        return LeaveGuildResponse.getDefaultInstance();
    }

    // ================================================================ DisbandGuild（38）

    /**
     * 解散（guild_logic.go:468-520；§3.6）：归属区 → 闸门 → operatorGuild → 事务（授权只看 guild.leader_id；事务内再判一次闸门）。
     * RANK_TOO_LOW → 14005「not guild leader」（唯一不回 14016 的地方）。提交后：失效 → 按<b>事务内读到的 zone</b> 清榜（失败只打 ERROR）→
     * 推 DISBANDED（target = 0）给解散前全体成员除帮主（只提交过申请的人不通知）。
     */
    public DisbandGuildResponse disbandGuild(long me, DisbandGuildRequest request, Deadline deadline) {
        bodyIdentity("DisbandGuild", me, request.getPlayerId());
        ZoneCheck zone = access.clientWrite(me, deadline);
        if (zone.rejected()) {
            return DisbandGuildResponse.newBuilder().setErrorMessage(zone.tip().proto()).build();
        }
        Membership membership = access.operatorGuild(me, deadline);
        if (membership.rejected()) {
            return DisbandGuildResponse.newBuilder().setErrorMessage(membership.tip().proto()).build();
        }
        long guildId = membership.guildId();
        TxOutcome<Disbanded> outcome = store.disbandGuild(guildId, me, clockMs.getAsLong(), access.txFence(), deadline);
        if (!(outcome instanceof TxOutcome.Ok<Disbanded> ok)) {
            return DisbandGuildResponse.newBuilder()
                    .setErrorMessage(access.disbandReplyFor(me, guildId, outcome.rejection(), deadline).proto()).build();
        }
        Disbanded disbanded = ok.value();
        access.invalidate(disbanded.invalidation(), deadline);
        // 清榜只能用事务内读到的 zone：缓存里的 zone 在合服之后会陈旧一个 TTL（GuildRanks 永不抛，失败由下次启动重建自愈）
        ranks.remove(guildId, disbanded.zoneId(), deadline);
        pushes.notify(GuildChangeKind.GUILD_CHANGE_KIND_DISBANDED, guildId, me, 0, disbanded.pushRecipients());
        log.info("[guild] DisbandGuild ok guild={} leader={} members={}", Long.toUnsignedString(guildId),
                Long.toUnsignedString(me), disbanded.memberIds().size());
        return DisbandGuildResponse.getDefaultInstance();
    }

    // ================================================================ SetAnnouncement（39）

    /**
     * 改公告（guild_logic.go:522-556；§3.7）：<b>最先</b>按未 trim 的 UTF-8 字节判超长（14011，先于身份与归属区）→ 归属区 → 闸门 → 事务
     * （guild_id 取自请求体，授权完全靠事务内锁行复核：不在该帮或档位不足 → 14006；帮会不存在 → 14001 并以请求体 guild_id 自愈映射）。
     * 提交后无条件失效（写同样的文本也算一次成功），推 ANNOUNCEMENT_CHANGED（target = 0）给快照除操作者；回包是事务内快照。
     */
    public SetAnnouncementResponse setAnnouncement(long me, SetAnnouncementRequest request, Deadline deadline) {
        bodyIdentity("SetAnnouncement", me, request.getPlayerId());
        if (GuildAnnouncements.tooLong(request.getAnnouncement())) {
            return SetAnnouncementResponse.newBuilder().setErrorMessage(GuildTip.ANNOUNCEMENT_TOO_LONG.proto()).build();
        }
        ZoneCheck zone = access.clientWrite(me, deadline);
        if (zone.rejected()) {
            return SetAnnouncementResponse.newBuilder().setErrorMessage(zone.tip().proto()).build();
        }
        long guildId = request.getGuildId();
        TxOutcome<AnnouncementUpdated> outcome = store.updateAnnouncement(guildId, me, request.getAnnouncement(), deadline);
        if (!(outcome instanceof TxOutcome.Ok<AnnouncementUpdated> ok)) {
            return SetAnnouncementResponse.newBuilder()
                    .setErrorMessage(access.replyFor(me, guildId, guildId, outcome.rejection(), deadline).proto()).build();
        }
        AnnouncementUpdated updated = ok.value();
        access.invalidate(updated.invalidation(), deadline);
        pushes.notify(GuildChangeKind.GUILD_CHANGE_KIND_ANNOUNCEMENT_CHANGED, guildId, me, 0, updated.pushRecipients());
        return SetAnnouncementResponse.newBuilder().setGuild(views.guildInfo(updated.guild(), me, deadline)).build();
    }

    // ================================================================ 工具

    /** 请求体里的 player_id 对客户端无效；非 0 且与会话不一致只打 ERROR，以会话为准（guild_logic.go:139-148 callerOf）。 */
    static void bodyIdentity(String method, long me, long bodyPlayerId) {
        if (bodyPlayerId != 0 && bodyPlayerId != me) {
            log.error("[guild] {} 请求体 player_id={} 与会话身份 {} 不一致，以会话为准", method, Long.toUnsignedString(bodyPlayerId),
                    Long.toUnsignedString(me));
        }
    }
}
