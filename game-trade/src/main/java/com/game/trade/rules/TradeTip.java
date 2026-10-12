package com.game.trade.rules;

import com.game.proto.TipInfoMessage;

/**
 * 聚宝斋回给客户端的每一种 in-band tip：码 + 发生点的原因（trade-spec §0.5、§0.7、§3）。
 *
 * <p><b>原因只进日志与测试断言，绝不进 {@code TipInfoMessage.parameters}</b>：基线每个 tip 都是 {@code TipInfoMessage{id}}
 * （tipOf，jubaozhai_logic.go:420-422），客户端只看 id（JubaozhaiClient.cs:286）。所以 {@link #proto()} 只设 id。
 * 同一个码在不同发生点原因不同（例如 1003 来自存储、归属区、发号或过载），所以这里按「发生点」列常量，不按码列（同 GuildTip 的组织方式，
 * 但不照抄它带英文原因串的形状）。
 *
 * <p>码一律取自 {@link TradeTips}（其值来自导表生成的枚举），不手写数字。{@link #OVERLOADED} 与 {@link #INTERNAL_ERROR} 是 Java 自有的
 * 发生点（基线没有应用层队列；T7），码与基线「预算到期 → in-band 1003」同形。
 */
public enum TradeTip {

    // ---- 1005 kInvalidParameter ----

    /** 服务层取不到会话（player_id = 0）。Java 经 gate 不可达（会话 0 在派发层回信封 1003），服务层仍保留判定（T6；jubaozhai_logic.go:105-108 等）。 */
    NO_SESSION(TradeTips.INVALID_PARAMETER, "会话里没有玩家号"),
    /** 浏览的纯校验不过：search / tab / section / category+subcategory / sort（jubaozhai_logic.go:109-113）。 */
    INVALID_BROWSE_REQUEST(TradeTips.INVALID_PARAMETER, "浏览参数非法"),
    /** 详情 / 收藏的 listing_id = 0（jubaozhai_logic.go:236-239、:285-287）。 */
    LISTING_ID_ZERO(TradeTips.INVALID_PARAMETER, "listing_id 为 0"),
    /** SeedListing 请求校验不过（admin_logic.go:66-68；phase.go:188-213）。 */
    INVALID_SEED_REQUEST(TradeTips.INVALID_PARAMETER, "种子请求参数非法"),

    // ---- 20000–20003 ----

    /** GetListing 查无此行（jubaozhai_logic.go:327-329）。 */
    LISTING_NOT_FOUND(TradeTips.LISTING_NOT_FOUND, "商品不存在"),
    /** 非卖家且 VisibleToBuyer 为假：已结束、非 LISTED/LOCKED、zone 范围下别区（jubaozhai_logic.go:347-350）。对客户端与「不存在」同码。 */
    LISTING_NOT_VISIBLE(TradeTips.LISTING_NOT_FOUND, "商品对调用者不可见"),
    /** 调用者没有归属区（resolveHomeZone 查到 0，jubaozhai_logic.go:372-375；Java：player 行缺失或 zone_id = 0）。 */
    HOME_ZONE_UNKNOWN(TradeTips.HOME_ZONE_UNKNOWN, "调用者没有归属区"),
    /** 种子卖家没有归属区（admin_logic.go:71-78）。 */
    SELLER_HOME_ZONE_UNKNOWN(TradeTips.HOME_ZONE_UNKNOWN, "种子卖家没有归属区"),
    /** 收藏数 ≥ 上限（jubaozhai_logic.go:307-313）。计数包括已经看不见的收藏（§2.7，Q8 保持基线）。 */
    FAVORITE_LIMIT_REACHED(TradeTips.FAVORITE_LIMIT_REACHED, "收藏数已达上限"),
    /** section = AUCTION，全部校验通过之后（jubaozhai_logic.go:114-116）。 */
    AUCTION_DISABLED(TradeTips.FEATURE_DISABLED, "竞价分区尚未开放"),

    // ---- 1003 kServiceUnavailable（fault） ----

    /** MySQL 调用失败或超时（storeFault，jubaozhai_logic.go:354-358；admin_logic.go:118-121）。 */
    STORE_FAULT(TradeTips.SERVICE_UNAVAILABLE, "存储故障"),
    /** 归属区查询失败或超时（resolveHomeZone，jubaozhai_logic.go:364-371）。 */
    HOME_ZONE_FAULT(TradeTips.SERVICE_UNAVAILABLE, "归属区查询故障"),
    /** 市场范围不是 zone / global（jubaozhai_logic.go:140-144）：启动校验已挡住，走到这里按故障拒绝、不退化成全服可见。 */
    SCOPE_UNSPECIFIED(TradeTips.SERVICE_UNAVAILABLE, "市场范围配置非法"),
    /** listing_id 发号失败、租约失效或拿到 0（admin_logic.go:80-93；Java T4 / T13）。 */
    LISTING_ID_UNAVAILABLE(TradeTips.SERVICE_UNAVAILABLE, "listing_id 发号失败"),
    /** Java 自有（T7）：工作队列满，或排队时已超整请求预算（同 friend；不带原因串）。 */
    OVERLOADED(TradeTips.SERVICE_UNAVAILABLE, "工作队列过载或排队超预算"),
    /** Java 自有：处理器抛出未分类的 RuntimeException（trade-spec §5.3 最后一行）。 */
    INTERNAL_ERROR(TradeTips.SERVICE_UNAVAILABLE, "处理器异常");

    private final int code;
    private final String reason;
    private final TipInfoMessage proto;

    TradeTip(int code, String reason) {
        this.code = code;
        this.reason = reason;
        this.proto = TipInfoMessage.newBuilder().setId(code).build();
    }

    /** tip 码（{@code TipInfoMessage.id}）。 */
    public int code() {
        return code;
    }

    /** 发生点的原因：<b>只进日志</b>，不进应答。 */
    public String reason() {
        return reason;
    }

    /** 写进应答 {@code error_message} 的消息：只有 id、没有 parameters（不可变，可共享）。 */
    public TipInfoMessage proto() {
        return proto;
    }

    /** 是否是 in-band 故障（只有 1003；见 {@link TradeTips#isFault}）。 */
    public boolean fault() {
        return TradeTips.isFault(code);
    }
}
