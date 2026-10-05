package com.game.guild.dispatch;

import java.util.List;

/**
 * {@code guildpb.GuildService} 的 28 个方法名（guild.proto:533-576；消息号只取自 {@code MessageIdRegistry}，代码里不写数字）。
 * 指标的 {@code method} 标签也只取这里的名字或 {@code unrouted}（AGENTS.md §5）。
 *
 * <p>四组（guild-spec §0.2、§7.3、§6.5；guild-economy-spec §0.2、§7.9）：
 * <ul>
 *   <li>{@link #CLIENT_REQUESTS}：4.4 的 16 个 C2S 方法，进工作线程池处理；</li>
 *   <li>{@link #ECONOMY_REQUESTS}：4.5 经济 5 个 C2S（捐献页 / 捐献 / 升级 / 商店页 / 兑换），进工作线程池处理；捐献与兑换在预留事务之后
 *       异步完成（同步投递不占工作线程）。4.4 的 in-band 1006 占位（D13）随之撤销；</li>
 *   <li>{@link #FORBIDDEN}：上行 8（UpdateGuildScore，只对内部开放）与 220（NotifyGuildChanged，推送占位）——基线会话白名单不含它们
 *       （session.go:43-78）→ PermissionDenied → 路由服信封 1003；</li>
 *   <li>{@link #PLACEHOLDERS}：4.6 活动 5 个，仍回 in-band 1006（D13），4.6 只换处理器。</li>
 * </ul>
 */
public final class GuildMethods {

    /** 服务裸名（{@code message_id.txt} 的键前缀，如 {@code 8=GuildServiceUpdateGuildScore}）。 */
    public static final String SERVICE = "GuildService";

    // ---- 4.4 的 16 个 C2S ----
    public static final String CREATE_GUILD = "CreateGuild";
    public static final String GET_GUILD = "GetGuild";
    public static final String GET_PLAYER_GUILD = "GetPlayerGuild";
    public static final String LEAVE_GUILD = "LeaveGuild";
    public static final String DISBAND_GUILD = "DisbandGuild";
    public static final String SET_ANNOUNCEMENT = "SetAnnouncement";
    public static final String SET_GUILD_MEMBER_ROLE = "SetGuildMemberRole";
    public static final String KICK_GUILD_MEMBER = "KickGuildMember";
    public static final String TRANSFER_GUILD_LEADER = "TransferGuildLeader";
    public static final String APPLY_JOIN_GUILD = "ApplyJoinGuild";
    public static final String CANCEL_GUILD_APPLICATION = "CancelGuildApplication";
    public static final String LIST_MY_GUILD_APPLICATIONS = "ListMyGuildApplications";
    public static final String LIST_GUILD_APPLICATIONS = "ListGuildApplications";
    public static final String REVIEW_GUILD_APPLICATION = "ReviewGuildApplication";
    public static final String GET_GUILD_RANK = "GetGuildRank";
    public static final String GET_GUILD_RANK_BY_GUILD = "GetGuildRankByGuild";

    // ---- 客户端不得上行的两个 ----
    public static final String UPDATE_GUILD_SCORE = "UpdateGuildScore";
    public static final String NOTIFY_GUILD_CHANGED = "NotifyGuildChanged";

    // ---- 4.5 经济 ----
    public static final String GET_GUILD_DONATE_OPTIONS = "GetGuildDonateOptions";
    public static final String DONATE_TO_GUILD = "DonateToGuild";
    public static final String UPGRADE_GUILD = "UpgradeGuild";
    public static final String GET_GUILD_SHOP = "GetGuildShop";
    public static final String BUY_GUILD_SHOP_GOODS = "BuyGuildShopGoods";

    // ---- 4.6 活动（仍是占位）----
    public static final String GET_GUILD_ACTIVITIES = "GetGuildActivities";
    public static final String LIGHT_GUILD_LANTERN = "LightGuildLantern";
    public static final String CLAIM_GUILD_REUNION = "ClaimGuildReunion";
    public static final String START_GUILD_TRIAL = "StartGuildTrial";
    public static final String RESPOND_GUILD_TRIAL_INVITE = "RespondGuildTrialInvite";

    public static final List<String> CLIENT_REQUESTS = List.of(CREATE_GUILD, GET_GUILD, GET_PLAYER_GUILD, LEAVE_GUILD,
            DISBAND_GUILD, SET_ANNOUNCEMENT, SET_GUILD_MEMBER_ROLE, KICK_GUILD_MEMBER, TRANSFER_GUILD_LEADER, APPLY_JOIN_GUILD,
            CANCEL_GUILD_APPLICATION, LIST_MY_GUILD_APPLICATIONS, LIST_GUILD_APPLICATIONS, REVIEW_GUILD_APPLICATION,
            GET_GUILD_RANK, GET_GUILD_RANK_BY_GUILD);

    public static final List<String> ECONOMY_REQUESTS = List.of(GET_GUILD_DONATE_OPTIONS, DONATE_TO_GUILD, UPGRADE_GUILD,
            GET_GUILD_SHOP, BUY_GUILD_SHOP_GOODS);

    public static final List<String> FORBIDDEN = List.of(UPDATE_GUILD_SCORE, NOTIFY_GUILD_CHANGED);

    public static final List<String> PLACEHOLDERS = List.of(GET_GUILD_ACTIVITIES, LIGHT_GUILD_LANTERN, CLAIM_GUILD_REUNION,
            START_GUILD_TRIAL, RESPOND_GUILD_TRIAL_INVITE);

    private GuildMethods() {
    }

    /** 全部 28 个方法（启动校验：每个都必须在 message_id.txt 里）。 */
    public static List<String> all() {
        return java.util.stream.Stream.of(CLIENT_REQUESTS, ECONOMY_REQUESTS, FORBIDDEN, PLACEHOLDERS).flatMap(List::stream)
                .toList();
    }
}
