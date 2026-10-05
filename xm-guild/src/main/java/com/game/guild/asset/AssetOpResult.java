package com.game.guild.asset;

import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import java.util.Objects;

/**
 * 一次资产 RPC 的结果（已剥掉传输层；基线 assetop.Result，types.go:155-187）。传输失败（Dubbo 异常、超时、定位故障）<b>不</b>用它表达：
 * 那时根本不知道 scene 做了什么，调用方拿到的是异常（{@link AssetOpDecisions#decide} 的 error 参数），重排时也不覆盖行上的
 * last_outcome / last_reason / durable（E12）。
 *
 * @param outcome scene 给出的结局；{@code local} 时是本地合成的 NOT_HERE
 * @param reason  asset_error 段的原因码（27000–27008），无原因为 0；uint32 位模式
 * @param durable 该 seq 的结局已在最近一次确认写成功的存档里（Java：MySQL player_state 围栏写提交成功，E7）。只有它为真，终结才安全（I4）
 * @param partial APPLIED 但只发放了一部分：不做对侧账，转人工补偿
 * @param local   根本没发出 RPC（没人持有该玩家 / 节点没有资产通道），NOT_HERE 是本地合成的；离线读已落盘账本只在它为真时有意义
 */
public record AssetOpResult(AssetOutcome outcome, int reason, boolean durable, boolean partial, boolean local) {

    public AssetOpResult {
        Objects.requireNonNull(outcome, "outcome");
    }

    /** scene 的应答（{@code local = false}）。 */
    public static AssetOpResult of(AssetOpResponse response) {
        AssetOutcome outcome = response.getOutcome();
        return new AssetOpResult(outcome == null ? AssetOutcome.UNRECOGNIZED : outcome, response.getReason(),
                response.getDurable(), response.getPartial(), false);
    }

    /** 本地合成的 NOT_HERE（caller.go:96-111：没人持有 → 不算错误，按 Retry 退避）。 */
    public static AssetOpResult localNotHere() {
        return new AssetOpResult(AssetOutcome.ASSET_OUTCOME_NOT_HERE, 0, false, false, true);
    }

    /** 已落盘结局（离线读账本得出的结论天然 durable；reconcile.go:635-667）。 */
    public static AssetOpResult persisted(AssetOutcome outcome, int reason, boolean partial) {
        return new AssetOpResult(outcome, reason, true, partial, false);
    }

    /**
     * 这条结果是否足以终结 outbox 行：结局固定（APPLIED / REJECTED）<b>且</b> durable（Terminal，types.go:181-187）。REJECTED 也要 durable：
     * 未落盘的拒绝可能被 scene 崩溃抹掉，之后同一个 seq 又被真的应用。
     */
    public boolean terminal() {
        return durable && (outcome == AssetOutcome.ASSET_OUTCOME_APPLIED || outcome == AssetOutcome.ASSET_OUTCOME_REJECTED);
    }

    /**
     * 写进 last_outcome 列（uint32）的数值。scene 回了本端不认识的结局（{@link AssetOutcome#UNRECOGNIZED}，原值已丢）时写 -1
     * （列里是 4294967295），诊断时一眼可辨；它同时按 ALERT 处理、永不终结。
     */
    public int outcomeNumber() {
        return outcome == AssetOutcome.UNRECOGNIZED ? -1 : outcome.getNumber();
    }

    /** 换一个原因码（{@link AssetOpDecisions#carryPartialReason} 用）。 */
    public AssetOpResult withReason(int newReason) {
        return new AssetOpResult(outcome, newReason, durable, partial, local);
    }
}
