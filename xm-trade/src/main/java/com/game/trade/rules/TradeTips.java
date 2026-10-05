package com.game.trade.rules;

import com.game.table.CommonErrorTip;
import com.game.table.TradeErrorTip;
import java.util.Set;

/**
 * 聚宝斋的 tip 码（基线 go/trade/internal/constants/constants.go:20-40；trade-spec §0.5）。
 *
 * <p>码值只能取自导表生成的 {@code TradeErrorTip.trade_error} / {@code CommonErrorTip.common_error}，不许手写数字（同基线
 * constants_test.go:99 TestNoHandWrittenTipCodes；Java 侧由 {@code TradeTipsTest} 扫描本文件钉住）。trade 段只有这 4 个码
 * （trade_error_tip.proto:9-20）：设计稿规划的 P3 码还没进 Tip.xlsx，Java 不能提前加（§0.5 末尾）。
 *
 * <p><b>tip 永不带 parameters</b>（基线 tipOf，jubaozhai_logic.go:420-422）：friend 的 Route.failure 会塞英文原因串、guild 照基线带英文原因，
 * trade 两样都不能照抄（§0.3「应答的形状纪律」）。每个发生点的原因串在 {@link TradeTip}，只进日志。
 *
 * <p>故障分类：基线由 Tip.xlsx 的 fault 列生成（constants_test.go:24-33 钉住 ErrServiceUnavailable 是唯一的 fault）。Java 的配置表没有
 * fault 列，所以这里写死 {@link #FAULTS} = {1003}（同 GuildTips.FAULTS 的做法），并用测试钉住。
 */
public final class TradeTips {

    // ---- trade 段（trade_error_tip.proto:9-20；//trade_error base=20000 width=1000） ----

    /** 商品不存在、已结束，或 zone 范围下属于别区（不泄露别区有哪些商品；constants.go:28-30）。 */
    public static final int LISTING_NOT_FOUND = TradeErrorTip.trade_error.kTradeListingNotFound_VALUE;
    /** 调用者（或种子卖家）没有归属区：数据状态，不是故障（constants.go:32-33）。客户端把整个市场标为不可用。 */
    public static final int HOME_ZONE_UNKNOWN = TradeErrorTip.trade_error.kTradeHomeZoneUnknown_VALUE;
    /** 收藏数已达 {@code xm.trade.market.max-favorites-per-player}（constants.go:35-36）。 */
    public static final int FAVORITE_LIMIT_REACHED = TradeErrorTip.trade_error.kTradeFavoriteLimitReached_VALUE;
    /** P1 未开放的功能：竞价分区 AUCTION（constants.go:38-39）。 */
    public static final int FEATURE_DISABLED = TradeErrorTip.trade_error.kTradeFeatureDisabled_VALUE;

    // ---- 通用段（common_error_tip.proto:18、:22；tip_text.json） ----

    /** 参数非法、服务层取不到会话（constants.go:20-22）。 */
    public static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    /**
     * 存储 / 归属区 / 发号故障、范围配置非法、整请求预算到期、工作队列过载、处理器异常（constants.go:24-26）。<b>只用于真故障</b>，业务拒绝不许复用。
     * 也是派发层信封的故障码（请求体解析失败、会话 player_id = 0、带会话调 199，trade-spec §5.3）。
     */
    public static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    /** 信封：契约里有、但不归 trade 的消息号（同 FriendDispatcher；trade-spec §5.3）。 */
    public static final int FEATURE_UNAVAILABLE = CommonErrorTip.common_error.kFeatureUnavailable_VALUE;
    /** 信封：契约里没有的消息号（trade-spec §5.3）。 */
    public static final int MESSAGE_ID_NOT_FOUND = CommonErrorTip.common_error.kMessageIdNotFound_VALUE;

    /** in-band 故障码集合：只有 1003（constants_test.go:24-33；fault 列）。 */
    public static final Set<Integer> FAULTS = Set.of(SERVICE_UNAVAILABLE);

    /**
     * trade 段的声明（Tip.xlsx 组头 {@code //trade_error base=20000 width=1000}，设计稿 :24、:266）。这是段声明而不是 tip 码；
     * Java 没有生成的段表，只能照抄（同 GuildTips.SEGMENT_BASE）。
     */
    private static final int SEGMENT_BASE = 20000;
    private static final int SEGMENT_WIDTH = 1000;

    private TradeTips() {
    }

    /** 是否是 in-band 故障（指标计 {@code internal_error}、打 ERROR）；其余非 0 码都是业务拒绝。 */
    public static boolean isFault(int code) {
        return FAULTS.contains(code);
    }

    /** 码是否落在 trade 段 {@code [20000, 21000)} 内（基线判属看 [Base, Base+Width)，不看已分配上界）。 */
    public static boolean isTradeCode(int code) {
        return code >= SEGMENT_BASE && code < SEGMENT_BASE + SEGMENT_WIDTH;
    }
}
