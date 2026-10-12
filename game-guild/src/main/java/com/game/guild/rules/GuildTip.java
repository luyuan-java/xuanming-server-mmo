package com.game.guild.rules;

import com.game.proto.TipInfoMessage;

/**
 * 帮会服务回给客户端的每一种 in-band tip：码 + 基线的固定英文原因串（guild-spec §0.4、§2.7）。
 *
 * <p>基线每个 tip 的形状都是 {@code TipInfoMessage{id, parameters:[一句固定英文原因]}}（guild_logic.go:120-122 tipErr）。
 * 客户端只看 id（GuildClient.cs:728-770）；Java 照抄原句，便于与基线逐字节对拍（§9.2 第 16 条）。
 * 同一个码在不同发生点带不同原因串，所以这里按「发生点」列常量，不按码列。
 *
 * <p>码一律取自 {@link GuildTips}（其值来自导表生成的 {@code GuildErrorTip.guild_error} / {@code CommonErrorTip.common_error}），
 * 不手写数字。只有两条是 Java 自有的（基线没有对应发生点）：{@link #OVERLOADED}（工作队列过载，已拍板 in-band 14021）与
 * {@link #FEATURE_UNAVAILABLE}（D13：4.5 / 4.6 的号在 4.4 期间回 in-band 1006）。
 */
public enum GuildTip {

    // ---- 14000 kGuildAlreadyInGuild ----

    /** 建帮预检 / 事务、申请预检、事务 ErrPlayerAlreadyInGuild（guild_logic.go:237、:273；guild_manage_logic.go:305、:485）。 */
    ALREADY_IN_GUILD(GuildTips.ALREADY_IN_GUILD, "already in a guild"),
    /** 退帮：缓存指的帮里没有他、MySQL 复核他在别的帮（guild_logic.go:458）。 */
    MEMBERSHIP_CHANGED(GuildTips.ALREADY_IN_GUILD, "guild membership changed, retry"),

    // ---- 14001 kGuildNotFound ----

    /** GetGuild 不存在或别区、内部 GetPlayerGuild 重读仍无帮、事务 ErrGuildGone / ErrGuildZoneMismatch（guild_logic.go:362；guild_manage_logic.go:285-288）。 */
    GUILD_NOT_FOUND(GuildTips.GUILD_NOT_FOUND, "guild not found"),
    /** 申请的 guild_id = 0（guild_manage_logic.go:470）。 */
    APPLY_GUILD_ID_ZERO(GuildTips.GUILD_NOT_FOUND, "guild id is zero"),

    // ---- 14002 kGuildNotInGuild ----

    /** GetPlayerGuild / operatorGuild 映射与复核都说不在帮（guild_logic.go:385、:395、:410；guild_manage_logic.go:249）。 */
    NOT_IN_ANY_GUILD(GuildTips.NOT_IN_GUILD, "not in any guild"),
    /** 事务 ErrNotGuildMember、列待审时查不到本人成员行（guild_manage_logic.go:289-291、:638）。 */
    NOT_A_MEMBER(GuildTips.NOT_IN_GUILD, "not a member of the guild"),

    // ---- 14003–14012 ----

    /** 申请、审批通过时帮会已满（guild_manage_logic.go:300-301）。 */
    GUILD_FULL(GuildTips.GUILD_FULL, "guild is full"),
    /** 帮主退帮（guild_manage_logic.go:298-299）。 */
    LEADER_CANT_LEAVE(GuildTips.LEADER_CANT_LEAVE, "leader cannot leave, disband or transfer instead"),
    /** <b>只用于解散</b>：事务说职位不够（guild_logic.go:494-498）。 */
    NOT_GUILD_LEADER(GuildTips.NOT_LEADER, "not guild leader"),
    /** 公告：不在该帮或职位不足（guild_logic.go:544-546）。 */
    NO_PERMISSION(GuildTips.NO_PERMISSION, "no permission"),
    /** 任免的 role 不是 0 / 1（guild_manage_logic.go:384-386）。 */
    ROLE_NOT_ASSIGNABLE(GuildTips.NO_PERMISSION, "role not assignable"),
    /** GetGuildRankByGuild 查不到名次（guild_logic.go:654-656）。 */
    NOT_RANKED(GuildTips.NOT_RANKED, "guild not ranked"),
    /** 建帮发号失败（guild_logic.go:328-340）。帮会段唯一的 in-band 故障码（{@link GuildTips#FAULTS}）。 */
    ID_GENERATOR_UNAVAILABLE(GuildTips.ID_GEN_UNAVAILABLE, "id generator unavailable"),
    /**
     * 4.5：资产指令 op_id 发号失败（发号器未接线 / 出错 / 返回 0，economy_logic.go:386-401；guild-economy-spec §2.2、E6）。
     * 同为 in-band 故障码 14008；绝不自造 id。
     */
    ASSET_OP_ID_UNAVAILABLE(GuildTips.ID_GEN_UNAVAILABLE, "asset op id generator unavailable"),
    /** 帮名非法（guild_logic.go:195-198）。 */
    INVALID_GUILD_NAME(GuildTips.NAME_INVALID, "invalid guild name"),
    /** 建帮撞 uk_guild（guild_logic.go:274-275）。 */
    NAME_TAKEN(GuildTips.NAME_TAKEN, "guild name taken"),
    /** 公告超过 600 字节（guild_logic.go:524-526）。 */
    ANNOUNCEMENT_TOO_LONG(GuildTips.ANNOUNCEMENT_TOO_LONG, "announcement too long"),
    /** 归属区为 0 / 查不到（guild_logic.go:160-164；Java D3：player 行缺失或 zone_id = 0）。 */
    HOME_ZONE_UNKNOWN(GuildTips.HOME_ZONE_UNKNOWN, "home zone unknown"),

    // ---- 14013 kGuildZoneMerging（D4：4.4 的闸门恒放行，事务外两条暂不出现） ----

    /** 事务外闸门读失败（fail-closed；guild_logic.go:310-313 mergeFenceTip）。 */
    MERGE_FENCE_UNREADABLE(GuildTips.ZONE_MERGING, "merge fence unreadable"),
    /** 事务外闸门命中（guild_logic.go:315-317 mergeFenceTip）、事务内闸门 ErrZoneMerging（guild_manage_logic.go:312-316）。 */
    ZONE_MERGING(GuildTips.ZONE_MERGING, "zone merging"),

    // ---- 14014 / 14015 ----

    /** 任免 / 踢人 / 转让的 target = 0（guild_manage_logic.go:336-338）。 */
    TARGET_ID_ZERO(GuildTips.TARGET_NOT_MEMBER, "target player id is zero"),
    /** 事务 ErrTargetNotMember（guild_manage_logic.go:292-293）。 */
    TARGET_NOT_MEMBER(GuildTips.TARGET_NOT_MEMBER, "target is not a member"),
    /** 任免 / 踢人 / 转让的 target = 自己（guild_manage_logic.go:339-341）。 */
    CANNOT_TARGET_SELF(GuildTips.CANNOT_TARGET_SELF, "cannot target self"),
    /** 审批自己的申请（guild_manage_logic.go:698-700）。 */
    CANNOT_REVIEW_OWN(GuildTips.CANNOT_TARGET_SELF, "cannot review own application"),

    // ---- 14016 / 14017 ----

    /** 事务 ErrRankTooLow（解散除外，guild_manage_logic.go:294-295）。 */
    RANK_TOO_LOW(GuildTips.RANK_TOO_LOW, "rank too low"),
    /** 列待审时 MySQL 的 role 不到长老（guild_manage_logic.go:641-645）。 */
    OFFICER_RANK_REQUIRED(GuildTips.RANK_TOO_LOW, "officer rank required"),
    /** 任命长老时已达上限（guild_manage_logic.go:296-297）。 */
    OFFICER_LIMIT(GuildTips.OFFICER_LIMIT, "officer limit reached"),

    // ---- 14018 kGuildApplicationNotFound ----

    /** 事务 ErrApplicationNotFound（guild_manage_logic.go:306-307）。 */
    APPLICATION_NOT_FOUND(GuildTips.APPLICATION_NOT_FOUND, "application not found or expired"),
    /** 撤回申请的 guild_id = 0（guild_manage_logic.go:516-522）。 */
    CANCEL_GUILD_ID_ZERO(GuildTips.APPLICATION_NOT_FOUND, "guild id is zero"),
    /** 审批的申请人 = 0（guild_manage_logic.go:695-697）。 */
    APPLICANT_ID_ZERO(GuildTips.APPLICATION_NOT_FOUND, "applicant player id is zero"),
    /** 审批通过时申请人没有归属区（guild_manage_logic.go:735-738）。 */
    APPLICANT_HOME_ZONE_UNKNOWN(GuildTips.APPLICATION_NOT_FOUND, "applicant home zone unknown"),

    // ---- 14019–14021 ----

    /** 本人待审申请数已达上限（guild_manage_logic.go:308-309）。 */
    APPLICATION_LIMIT(GuildTips.APPLICATION_LIMIT, "pending application limit reached"),
    /** 目标帮会待审申请已满（guild_manage_logic.go:310-311）。 */
    APPLICATION_QUEUE_FULL(GuildTips.APPLICATION_QUEUE_FULL, "guild application queue is full"),
    /** 写冲突：死锁重跑用尽 / 1205 / 超子预算 / COMMIT 结果不明（guild_manage_logic.go:317-321；guild_logic.go:276-279）。 */
    WRITE_CONFLICT(GuildTips.BUSY_RETRY, "guild write conflict"),
    /**
     * Java 自有：工作队列满或排队已超预算。已拍板回 in-band 14021 而不是 spec D11 的信封 1003——客户端遇到任何信封错误都会停用整个
     * 帮会模块（GuildClient.cs:785-808），瞬时过载不该让玩家重登。
     */
    OVERLOADED(GuildTips.BUSY_RETRY, "guild service overloaded"),

    // ---- 14022–14031 经济段（4.5；guild-economy-spec §0.4。全是业务拒绝，scene 的 27xxx 原因码只进视图、不做成 tip） ----

    /** 升级事务内资金不足（economy_logic.go:335-336）。 */
    FUNDS_INSUFFICIENT(GuildTips.FUNDS_INSUFFICIENT, "guild funds insufficient"),
    /** 当前等级行 upgrade_cost_funds == 0（economy_logic.go:333-334）。 */
    MAX_LEVEL(GuildTips.MAX_LEVEL, "guild already at max level"),
    /** 捐献事务内次数 upsert 达上限（economy_logic.go:327-328）。 */
    DONATE_LIMIT(GuildTips.DONATE_LIMIT, "daily donate limit reached"),
    /** 捐献被 durable REJECTED 且 reason = 27000（economy_logic.go:515-523）。 */
    CURRENCY_INSUFFICIENT(GuildTips.CURRENCY_INSUFFICIENT, "currency insufficient"),
    /** 资产通道关闭（economy_logic.go:376-380）：没写任何行，回包不带视图。 */
    ASSET_CHANNEL_DISABLED(GuildTips.ASSET_PENDING, "guild asset channel disabled"),
    /** 未决守卫拒绝（economy_logic.go:337-340）：没写任何行，回包不带视图；记 INFO。 */
    TOO_MANY_PENDING(GuildTips.ASSET_PENDING, "too many pending asset ops"),
    /** 配表没有该 donate_id（economy_logic.go:722-726）。 */
    DONATE_OPTION_NOT_FOUND(GuildTips.ASSET_REJECTED, "donate option not found"),
    /** 捐献 REJECTED 且原因不是 27000、兑换 REJECTED（economy_logic.go:522、:987-991）。 */
    ASSET_REJECTED(GuildTips.ASSET_REJECTED, "asset op rejected"),
    /** GuildShop 配表没有该 goods_id（economy_logic.go:887-890）。 */
    SHOP_GOODS_NOT_FOUND(GuildTips.SHOP_GOODS_NOT_FOUND, "shop goods not found"),
    /** 捐献的 min_guild_level 与商品的 required_guild_level 共用：预判与事务内（economy_logic.go:324-326、:728-730、:905-907）。 */
    GUILD_LEVEL_TOO_LOW(GuildTips.SHOP_LEVEL_TOO_LOW, "guild level too low"),
    /** 份数超过 MaxBuyCount，发号之前（economy_logic.go:901-903）。 */
    COUNT_EXCEEDS_MAX_BUY(GuildTips.SHOP_LIMIT, "count exceeds max buy count"),
    /** 仓储纯判断 count &gt; limit_count 或 upsert 达上限（economy_logic.go:329-330）。 */
    SHOP_LIMIT(GuildTips.SHOP_LIMIT, "shop purchase limit reached"),
    /** 帮贡预判与事务内（economy_logic.go:331-332、:1021-1022）。 */
    CONTRIBUTION_INSUFFICIENT(GuildTips.CONTRIBUTION_INSUFFICIENT, "contribution insufficient"),

    // ---- 通用段 ----

    /** Java 自有（D13）：4.5 / 4.6 的经济、活动 10 个号在 4.4 期间回 in-band 1006，写进各自应答的 error_message。 */
    FEATURE_UNAVAILABLE(GuildTips.FEATURE_UNAVAILABLE, "guild feature unavailable");

    private final int code;
    private final String reason;
    private final TipInfoMessage proto;

    GuildTip(int code, String reason) {
        this.code = code;
        this.reason = reason;
        this.proto = TipInfoMessage.newBuilder().setId(code).addParameters(reason).build();
    }

    /** tip 码（{@code TipInfoMessage.id}）。 */
    public int code() {
        return code;
    }

    /** 基线的英文原因串（{@code TipInfoMessage.parameters[0]}）。 */
    public String reason() {
        return reason;
    }

    /** 写进应答 {@code error_message} 的消息（不可变，可共享）。 */
    public TipInfoMessage proto() {
        return proto;
    }

    /** 是否是 in-band 故障（只有 14008：建帮发号与 4.5 的 op_id 发号；指标计 {@code fault}，见 {@link GuildTips#isFault}）。 */
    public boolean fault() {
        return GuildTips.isFault(code);
    }
}
