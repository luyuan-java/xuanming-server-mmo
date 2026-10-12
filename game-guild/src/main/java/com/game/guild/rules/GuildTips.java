package com.game.guild.rules;

import com.game.table.CommonErrorTip;
import com.game.table.GuildErrorTip;
import java.util.Set;

/**
 * 帮会的 tip 码（基线 go/guild/internal/constants/constants.go:62-136，guild-spec §0.4）与仓储拒绝 → 答复的映射（§2.7 mapWriteErr）。
 *
 * <p>码值只能取自导表生成的 {@code GuildErrorTip.guild_error} / {@code CommonErrorTip.common_error}，不许手写数字（同基线
 * constants_test.go TestNoHandWrittenTipCodes；Java 侧由 {@code GuildTipsTest} 扫描本文件钉住）。帮会段 14000–14031 全部列出：
 * 4.4 发 14000–14021，14022–14031 是 4.5 的经济段（guild-economy-spec §0.4）。活动段 tip 码在 Java 契约里不存在（guild-spec §0.4 末尾）。
 * 经济事务的哨兵经 {@link #forReject} 映射（基线 economyTip 先认经济哨兵、余下交 mapWriteErr，economy_logic.go:313-344）；
 * 通道关闭（14026）、配表缺行（故障）、op_id 发号失败（14008）是服务层的答复，不经 {@link GuildReject}（§7.9）。
 *
 * <p>故障分类：基线由 Tip.xlsx 的 fault 列生成（go/shared/generated/tip/faults.go:60），帮会段只有 14008 是故障（发号器坏了，
 * 指标计 {@code fault}，仍以 in-band 回客户端）。Java 的配置表没有 fault 列，所以这里写死 {@link #FAULTS} = {14008}，并用测试钉住。
 *
 * <p>每个 tip 的英文原因串在 {@link GuildTip}（按发生点列出）。
 */
public final class GuildTips {

    /** 成功（不设 {@code error_message}）。 */
    public static final int OK = GuildErrorTip.guild_error.kGuild_errorOK_VALUE;

    // ---- 帮会段：4.4 发的码（guild_error_tip.proto:9-54） ----

    public static final int ALREADY_IN_GUILD = GuildErrorTip.guild_error.kGuildAlreadyInGuild_VALUE;
    public static final int GUILD_NOT_FOUND = GuildErrorTip.guild_error.kGuildNotFound_VALUE;
    public static final int NOT_IN_GUILD = GuildErrorTip.guild_error.kGuildNotInGuild_VALUE;
    public static final int GUILD_FULL = GuildErrorTip.guild_error.kGuildFull_VALUE;
    public static final int LEADER_CANT_LEAVE = GuildErrorTip.guild_error.kGuildLeaderCantLeave_VALUE;
    /** 只用于解散（guild_logic.go:494-498）。 */
    public static final int NOT_LEADER = GuildErrorTip.guild_error.kGuildNotLeader_VALUE;
    public static final int NO_PERMISSION = GuildErrorTip.guild_error.kGuildNoPermission_VALUE;
    public static final int NOT_RANKED = GuildErrorTip.guild_error.kGuildNotRanked_VALUE;
    /** 发号器不可用：帮会段唯一的 in-band 故障码。 */
    public static final int ID_GEN_UNAVAILABLE = GuildErrorTip.guild_error.kGuildIdGenUnavailable_VALUE;
    public static final int NAME_INVALID = GuildErrorTip.guild_error.kGuildNameInvalid_VALUE;
    public static final int NAME_TAKEN = GuildErrorTip.guild_error.kGuildNameTaken_VALUE;
    public static final int ANNOUNCEMENT_TOO_LONG = GuildErrorTip.guild_error.kGuildAnnouncementTooLong_VALUE;
    public static final int HOME_ZONE_UNKNOWN = GuildErrorTip.guild_error.kGuildHomeZoneUnknown_VALUE;
    public static final int ZONE_MERGING = GuildErrorTip.guild_error.kGuildZoneMerging_VALUE;
    public static final int TARGET_NOT_MEMBER = GuildErrorTip.guild_error.kGuildTargetNotMember_VALUE;
    public static final int CANNOT_TARGET_SELF = GuildErrorTip.guild_error.kGuildCannotTargetSelf_VALUE;
    public static final int RANK_TOO_LOW = GuildErrorTip.guild_error.kGuildRankTooLow_VALUE;
    public static final int OFFICER_LIMIT = GuildErrorTip.guild_error.kGuildOfficerLimit_VALUE;
    public static final int APPLICATION_NOT_FOUND = GuildErrorTip.guild_error.kGuildApplicationNotFound_VALUE;
    public static final int APPLICATION_LIMIT = GuildErrorTip.guild_error.kGuildApplicationLimit_VALUE;
    public static final int APPLICATION_QUEUE_FULL = GuildErrorTip.guild_error.kGuildApplicationQueueFull_VALUE;
    /** 写冲突 / 过载：玩家原地重试一次即可。 */
    public static final int BUSY_RETRY = GuildErrorTip.guild_error.kGuildBusyRetry_VALUE;

    // ---- 帮会段：4.5 经济段（guild_error_tip.proto:55-74；4.4 不发） ----

    public static final int FUNDS_INSUFFICIENT = GuildErrorTip.guild_error.kGuildFundsInsufficient_VALUE;
    public static final int MAX_LEVEL = GuildErrorTip.guild_error.kGuildMaxLevel_VALUE;
    public static final int DONATE_LIMIT = GuildErrorTip.guild_error.kGuildDonateLimit_VALUE;
    public static final int CURRENCY_INSUFFICIENT = GuildErrorTip.guild_error.kGuildCurrencyInsufficient_VALUE;
    public static final int ASSET_PENDING = GuildErrorTip.guild_error.kGuildAssetPending_VALUE;
    public static final int ASSET_REJECTED = GuildErrorTip.guild_error.kGuildAssetRejected_VALUE;
    public static final int SHOP_GOODS_NOT_FOUND = GuildErrorTip.guild_error.kGuildShopGoodsNotFound_VALUE;
    public static final int SHOP_LEVEL_TOO_LOW = GuildErrorTip.guild_error.kGuildShopLevelTooLow_VALUE;
    public static final int SHOP_LIMIT = GuildErrorTip.guild_error.kGuildShopLimit_VALUE;
    public static final int CONTRIBUTION_INSUFFICIENT = GuildErrorTip.guild_error.kGuildContributionInsufficient_VALUE;

    // ---- 通用段（tip_text.json） ----

    /** 信封故障：依赖故障、处理器异常、无会话、请求体非法、上行 8 / 220（§7.3）。 */
    public static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    /** D13：4.5 / 4.6 的号在 4.4 期间回 in-band；契约里有但不归 guild 的号回信封（§7.3）。 */
    public static final int FEATURE_UNAVAILABLE = CommonErrorTip.common_error.kFeatureUnavailable_VALUE;
    /** 契约里没有的消息号（信封；§7.3）。 */
    public static final int MESSAGE_ID_NOT_FOUND = CommonErrorTip.common_error.kMessageIdNotFound_VALUE;

    /** 帮会段内的 in-band 故障码集合（基线 faults.go:60 只有 GuildIdGenUnavailable）。 */
    public static final Set<Integer> FAULTS = Set.of(ID_GEN_UNAVAILABLE);

    /** 故障答复（ErrLeaderMismatch / ErrGuildLevelConfigMissing）的基线 gRPC 文案（guild_manage_logic.go:325）。 */
    public static final String INCONSISTENT_REASON = "guild data or configuration is inconsistent";

    /**
     * guild 段的声明（基线 go/shared/generated/tip/segments.go:43，源头是 Tip.xlsx 组头 {@code //guild_error base=14000 width=1000}）。
     * 这是段声明而不是 tip 码；Java 没有生成的段表，只能照抄。
     */
    private static final int SEGMENT_BASE = 14000;
    private static final int SEGMENT_WIDTH = 1000;

    private static final RejectReply.Fault INCONSISTENT = new RejectReply.Fault(INCONSISTENT_REASON);

    private GuildTips() {
    }

    /** 是否是 in-band 故障（指标计 {@code fault}、打 ERROR）；其余非 0 码都是业务拒绝。 */
    public static boolean isFault(int code) {
        return FAULTS.contains(code);
    }

    /** 码是否落在 guild 段 {@code [14000, 15000)} 内（基线判属看 [Base, Base+Width)，不看已分配上界）。 */
    public static boolean isGuildCode(int code) {
        return code >= SEGMENT_BASE && code < SEGMENT_BASE + SEGMENT_WIDTH;
    }

    /**
     * 仓储拒绝 → 答复（基线 mapWriteErr 整表，guild_manage_logic.go:281-329；单测 guild_manage_logic_test.go:397）。
     *
     * <p>与基线的一处结构差异：基线 mapWriteErr 不认 ErrGuildNameTaken / ErrAnnouncementForbidden（落到 default 当故障），
     * 它们只在建帮 / 改公告的调用点先行映射成 14010 / 14006（guild_logic.go:274-275、:544-546）。这两个拒绝也只会由那两个事务产生，
     * 所以 Java 直接并进本表，结果与基线相同。解散的 RANK_TOO_LOW 要回 14005，用 {@link #forDisbandReject}。
     */
    public static RejectReply forReject(GuildReject reject) {
        return switch (reject) {
            // 别区的帮会与不存在的帮会同一答复，不向客户端透露「它在别的区」。
            case GUILD_GONE, ZONE_MISMATCH -> tip(GuildTip.GUILD_NOT_FOUND, RejectReply.MappingRepair.VERIFY_AGAINST_CACHED);
            case NOT_MEMBER -> tip(GuildTip.NOT_A_MEMBER, RejectReply.MappingRepair.VERIFY_AGAINST_CACHED);
            case TARGET_NOT_MEMBER -> tip(GuildTip.TARGET_NOT_MEMBER);
            case RANK_TOO_LOW -> tip(GuildTip.RANK_TOO_LOW);
            case OFFICER_LIMIT -> tip(GuildTip.OFFICER_LIMIT);
            case LEADER_CANT_LEAVE -> tip(GuildTip.LEADER_CANT_LEAVE);
            case GUILD_FULL -> tip(GuildTip.GUILD_FULL);
            // 缓存说他没入帮、MySQL 说他入了：以 MySQL 为准，顺手把 0 映射纠正回来。
            case ALREADY_IN_GUILD -> tip(GuildTip.ALREADY_IN_GUILD, RejectReply.MappingRepair.VERIFY_AGAINST_ZERO);
            case NAME_TAKEN -> tip(GuildTip.NAME_TAKEN);
            case ANNOUNCEMENT_FORBIDDEN -> tip(GuildTip.NO_PERMISSION);
            case APPLICATION_NOT_FOUND -> tip(GuildTip.APPLICATION_NOT_FOUND);
            case APPLICATION_LIMIT -> tip(GuildTip.APPLICATION_LIMIT);
            case QUEUE_FULL -> tip(GuildTip.APPLICATION_QUEUE_FULL);
            case ZONE_MERGING -> tip(GuildTip.ZONE_MERGING);
            // 记 INFO 不记 ERROR；绝不能回信封（会让客户端进重连隔离）。
            case WRITE_CONFLICT -> tip(GuildTip.WRITE_CONFLICT);
            // 双存储互相矛盾 / 配表缺行：不是玩家能修的，必须以故障形式暴露出来让人看见。
            case LEADER_MISMATCH, LEVEL_CONFIG_MISSING -> INCONSISTENT;
            // ---- 4.5 经济哨兵（economyTip，economy_logic.go:313-344；先认经济哨兵，余下才走 mapWriteErr） ----
            // 捐献选项的 min_guild_level 与商品的 required_guild_level 共用一个码（05 §5.12）。
            case LEVEL_TOO_LOW -> tip(GuildTip.GUILD_LEVEL_TOO_LOW);
            case DONATE_LIMIT -> tip(GuildTip.DONATE_LIMIT);
            case SHOP_LIMIT -> tip(GuildTip.SHOP_LIMIT);
            case CONTRIBUTION_INSUFFICIENT -> tip(GuildTip.CONTRIBUTION_INSUFFICIENT);
            case MAX_LEVEL -> tip(GuildTip.MAX_LEVEL);
            case FUNDS_INSUFFICIENT -> tip(GuildTip.FUNDS_INSUFFICIENT);
            // 守卫拒绝不是故障（再发新指令会破坏 scene 账本窗口的正确性证明）：服务记 INFO，不记 ERROR。
            case TOO_MANY_PENDING -> tip(GuildTip.TOO_MANY_PENDING);
        };
    }

    /**
     * 解散专用：RANK_TOO_LOW 回 14005「not guild leader」（解散只有帮主能做，沿用「只有会长可以执行该操作」文案；
     * guild_logic.go:491-498 在 mapWriteErr 之前拦截），其余同 {@link #forReject}。
     */
    public static RejectReply forDisbandReject(GuildReject reject) {
        return reject == GuildReject.RANK_TOO_LOW ? tip(GuildTip.NOT_GUILD_LEADER) : forReject(reject);
    }

    private static RejectReply tip(GuildTip tip) {
        return new RejectReply.Tip(tip, RejectReply.MappingRepair.NONE);
    }

    private static RejectReply tip(GuildTip tip, RejectReply.MappingRepair repair) {
        return new RejectReply.Tip(tip, repair);
    }
}
