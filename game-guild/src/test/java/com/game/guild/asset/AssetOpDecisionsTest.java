package com.game.guild.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.api.proto.AssetStream;
import com.game.guild.asset.AssetOpDecisions.CounterRefund;
import com.game.guild.rules.GuildLimits;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.guild.store.pb.GuildAssetOpStatus;
import com.game.guild.store.pb.GuildDailyCounterKind;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * 资产通道调用方的纯规则（照 mmorpg decide_test.go 全表，外加 reconcile_test.go 的 carryPartialReason / rpcFor、
 * economy_repo_test.go:84 的 StatusToRecord 与 :279 的 counterRefund；guild-economy-spec §11.1 AssetOpDecisionsTest）。
 */
class AssetOpDecisionsTest {

    private static AssetOpResult res(AssetOutcome outcome, int reason, boolean durable) {
        return new AssetOpResult(outcome, reason, durable, false, false);
    }

    private static AssetOp op(int stream, long deadlineMs, int lastReason) {
        return new AssetOp(7, 8, stream, 3, 1_700_000_000_000L, 7, 24, AssetBundle.getDefaultInstance(), 0, deadlineMs,
                0x11, lastReason);
    }

    // ================================================================ Decide

    @Test
    void decide全表() {
        RuntimeException transport = new RuntimeException("connection refused");
        record Case(String name, AssetOpResult res, Throwable err, AssetOpAction want) {
        }
        List<Case> cases = List.of(
                new Case("传输失败 → 重试", null, transport, AssetOpAction.RETRY),
                new Case("传输失败时忽略结果", res(AssetOutcome.ASSET_OUTCOME_APPLIED, 0, true), transport, AssetOpAction.RETRY),
                new Case("结局翻转 → 告警", null, new AssetOutcomeFlipException("flip"), AssetOpAction.ALERT),
                new Case("包了一层的结局翻转 → 告警", null,
                        new CompletionException(new RuntimeException("包一层", new AssetOutcomeFlipException("flip"))),
                        AssetOpAction.ALERT),
                new Case("UNKNOWN → 告警", res(AssetOutcome.ASSET_OUTCOME_UNKNOWN, 0, false), null, AssetOpAction.ALERT),
                new Case("UNKNOWN 即使 durable 也告警", res(AssetOutcome.ASSET_OUTCOME_UNKNOWN, 0, true), null, AssetOpAction.ALERT),
                new Case("APPLIED 且 durable → 终结", res(AssetOutcome.ASSET_OUTCOME_APPLIED, 0, true), null, AssetOpAction.FINALIZE),
                new Case("APPLIED 未 durable → 等落盘", res(AssetOutcome.ASSET_OUTCOME_APPLIED, 0, false), null,
                        AssetOpAction.AWAIT_DURABLE),
                new Case("REJECTED 且 durable → 终结", res(AssetOutcome.ASSET_OUTCOME_REJECTED, 27000, true), null,
                        AssetOpAction.FINALIZE),
                new Case("REJECTED 未 durable → 等落盘", res(AssetOutcome.ASSET_OUTCOME_REJECTED, 27000, false), null,
                        AssetOpAction.AWAIT_DURABLE),
                new Case("RETRY → 重试", res(AssetOutcome.ASSET_OUTCOME_RETRY, AssetOpDecisions.REASON_IN_BATTLE, false), null,
                        AssetOpAction.RETRY),
                new Case("NOT_HERE → 重试", res(AssetOutcome.ASSET_OUTCOME_NOT_HERE, 0, false), null, AssetOpAction.RETRY),
                new Case("本地合成的 NOT_HERE → 重试", AssetOpResult.localNotHere(), null, AssetOpAction.RETRY),
                new Case("不认识的结局 → 告警（fail-closed）", res(AssetOutcome.UNRECOGNIZED, 0, true), null, AssetOpAction.ALERT),
                new Case("既无结果也无错误 → 告警", null, null, AssetOpAction.ALERT));
        for (Case c : cases) {
            assertThat(AssetOpDecisions.decide(c.res(), c.err())).as(c.name()).isEqualTo(c.want());
        }
    }

    /** durable 是终结的硬前置：REJECTED 也不例外（比契约更严，理由见规格 C9；TestTerminalRequiresDurable）。 */
    @Test
    void 终结必须durable() {
        for (AssetOutcome outcome : List.of(AssetOutcome.ASSET_OUTCOME_APPLIED, AssetOutcome.ASSET_OUTCOME_REJECTED)) {
            assertThat(res(outcome, 0, false).terminal()).as("%s 未 durable", outcome).isFalse();
            assertThat(res(outcome, 0, true).terminal()).as("%s 且 durable", outcome).isTrue();
        }
        for (AssetOutcome outcome : List.of(AssetOutcome.ASSET_OUTCOME_UNKNOWN, AssetOutcome.ASSET_OUTCOME_RETRY,
                AssetOutcome.ASSET_OUTCOME_NOT_HERE)) {
            assertThat(res(outcome, 0, true).terminal()).as("%s 永不可终结，哪怕 durable", outcome).isFalse();
        }
        assertThat(AssetOpDecisions.isSceneTerminal(AssetOutcome.ASSET_OUTCOME_APPLIED)).isTrue();
        assertThat(AssetOpDecisions.isSceneTerminal(AssetOutcome.ASSET_OUTCOME_NOT_HERE)).isFalse();
    }

    @Test
    void 结果的构造与诊断数值() {
        AssetOpResult fromScene = AssetOpResult.of(AssetOpResponse.newBuilder()
                .setOutcome(AssetOutcome.ASSET_OUTCOME_APPLIED).setReason(27007).setDurable(true).setPartial(true).build());
        assertThat(fromScene).isEqualTo(new AssetOpResult(AssetOutcome.ASSET_OUTCOME_APPLIED, 27007, true, true, false));
        AssetOpResult unknownValue = AssetOpResult.of(AssetOpResponse.newBuilder().setOutcomeValue(9).build());
        assertThat(unknownValue.outcome()).isEqualTo(AssetOutcome.UNRECOGNIZED);
        assertThat(unknownValue.outcomeNumber()).as("不认识的结局写 -1（列里 4294967295）").isEqualTo(-1);
        assertThat(AssetOpDecisions.decide(unknownValue, null)).isEqualTo(AssetOpAction.ALERT);
        assertThat(AssetOpResult.localNotHere()).isEqualTo(new AssetOpResult(AssetOutcome.ASSET_OUTCOME_NOT_HERE, 0, false, false, true));
        assertThat(AssetOpResult.localNotHere().outcomeNumber()).isEqualTo(AssetOutcome.ASSET_OUTCOME_NOT_HERE_VALUE);
        assertThat(AssetOpResult.persisted(AssetOutcome.ASSET_OUTCOME_REJECTED, 27000, false).terminal()).isTrue();
    }

    // ================================================================ FinalStatus

    @Test
    void finalStatus全表() {
        AssetOpResult applied = res(AssetOutcome.ASSET_OUTCOME_APPLIED, 0, true);
        record Case(String name, AssetRpc rpc, AssetOpResult res, int lastReason, AssetOpStatus want) {
        }
        List<Case> cases = List.of(
                new Case("扣除成功", AssetRpc.DEBIT, applied, 0, AssetOpStatus.APPLIED),
                new Case("中止占位（reason 0）", AssetRpc.ABORT_DEBIT, res(AssetOutcome.ASSET_OUTCOME_REJECTED, 0, true), 0,
                        AssetOpStatus.ABORTED),
                new Case("中止时才发现余额不足", AssetRpc.ABORT_DEBIT,
                        res(AssetOutcome.ASSET_OUTCOME_REJECTED, AssetOpDecisions.REASON_CURRENCY_INSUFFICIENT, true), 0,
                        AssetOpStatus.REJECTED),
                new Case("发放被拒", AssetRpc.CREDIT, res(AssetOutcome.ASSET_OUTCOME_REJECTED, AssetOpDecisions.REASON_BLOCKED, true),
                        0, AssetOpStatus.REJECTED),
                new Case("扣除被拒 reason 0 也算业务拒绝", AssetRpc.DEBIT, res(AssetOutcome.ASSET_OUTCOME_REJECTED, 0, true), 0,
                        AssetOpStatus.REJECTED),
                new Case("本次答复带 partial", AssetRpc.CREDIT,
                        new AssetOpResult(AssetOutcome.ASSET_OUTCOME_APPLIED, 0, true, true, false), 0, AssetOpStatus.APPLIED_PARTIAL),
                new Case("只读答复不带 partial，靠行上的 last_reason", AssetRpc.CREDIT, applied,
                        AssetOpDecisions.REASON_PARTIAL_APPLIED, AssetOpStatus.APPLIED_PARTIAL),
                new Case("本次 reason 是 27007", AssetRpc.CREDIT,
                        res(AssetOutcome.ASSET_OUTCOME_APPLIED, AssetOpDecisions.REASON_PARTIAL_APPLIED, true), 0,
                        AssetOpStatus.APPLIED_PARTIAL),
                new Case("非终结结局算不出状态", AssetRpc.DEBIT, res(AssetOutcome.ASSET_OUTCOME_RETRY, 0, false), 0,
                        AssetOpStatus.PENDING),
                new Case("UNKNOWN 算不出状态", AssetRpc.DEBIT, res(AssetOutcome.ASSET_OUTCOME_UNKNOWN, 0, true), 0,
                        AssetOpStatus.PENDING));
        for (Case c : cases) {
            AssetOp row = op(AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE, 0, c.lastReason());
            assertThat(AssetOpDecisions.finalStatus(c.rpc(), c.res(), row)).as(c.name()).isEqualTo(c.want());
        }
    }

    // ================================================================ 退避

    @Test
    void 退避_指数_封顶_抖动_越界夹回() {
        long now = 1_000_000;
        // attempts = 3、base = 1 s → 8 s，再乘 [0.8, 1.2)
        for (double j : new double[] {0, 0.5, 0.999999}) {
            long delay = AssetOpDecisions.nextAttemptMs(now, 3, 1_000, 60_000, () -> j) - now;
            assertThat(delay).as("jitter=%s", j).isBetween(6_400L, 9_600L);
        }
        // attempts 很大时封顶在 max，再乘抖动
        assertThat(AssetOpDecisions.nextAttemptMs(now, 20, 1_000, 60_000, () -> 0) - now).isEqualTo(48_000);
        assertThat(AssetOpDecisions.nextAttemptMs(now, 20, 1_000, 60_000, () -> 0.999999) - now).isBetween(48_000L, 72_000L);
        // 不注入随机源时取中值：结果确定（基线生产路径就是这样，E10 之前）
        assertThat(AssetOpDecisions.nextAttemptMs(now, 0, 1_000, 60_000, null) - now).isEqualTo(1_000);
        // 无抖动时 attempts 0..5 → 1 / 2 / 4 / 8 / 16 / 32 s，之后 60 s 封顶（guild-economy-spec §2.5）
        long[] want = {1_000, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000};
        for (int a = 0; a < want.length; a++) {
            assertThat(AssetOpDecisions.nextAttemptMs(now, a, 1_000, 60_000, null) - now).as("attempts=%d", a).isEqualTo(want[a]);
        }
        // 越界的随机实现不得把退避拉飞
        for (double wild : new double[] {42, -3, Double.NaN, 1.0}) {
            assertThat(AssetOpDecisions.nextAttemptMs(now, 0, 1_000, 60_000, () -> wild) - now).as("rnd=%s", wild)
                    .isBetween(800L, 1_200L);
        }
        // 移位上限 16 只防溢出；attempts 按 uint32 看（-1 = 4294967295 → 封顶）
        assertThat(AssetOpDecisions.nextAttemptMs(now, -1, 1_000, 60_000, null) - now).isEqualTo(60_000);
        assertThat(AssetOpDecisions.nextAttemptMs(now, 63, Long.MAX_VALUE / 2, Long.MAX_VALUE, null) - now)
                .as("溢出按 max").isEqualTo((long) (Long.MAX_VALUE * 1.0));
        // base ≤ 0 按 1 s；max < base 按 base
        assertThat(AssetOpDecisions.nextAttemptMs(now, 0, 0, 0, null) - now).isEqualTo(1_000);
        assertThat(AssetOpDecisions.nextAttemptMs(now, 5, 2_000, 100, null) - now).isEqualTo(2_000);
        // 后台事务的退避同一个公式：10 ms 起、200 ms 封顶（seq.go:420-425）
        assertThat(AssetOpDecisions.nextAttemptMs(0, 0, GuildLimits.BACKGROUND_TX_BASE_BACKOFF_MS,
                GuildLimits.BACKGROUND_TX_MAX_BACKOFF_MS, () -> 0)).isEqualTo(8);
        // 无符号的 now（≥ 2^63）照样加得上
        long bigNow = 0x8000_0000_0000_0000L;
        assertThat(AssetOpDecisions.nextAttemptMs(bigNow, 0, 1_000, 60_000, null)).isEqualTo(bigNow + 1_000);
    }

    // ================================================================ 方向

    @Test
    void 流的投递方向_非法流号不猜() {
        Map<AssetStream, AssetRpc> want = new EnumMap<>(AssetStream.class);
        want.put(AssetStream.ASSET_STREAM_GUILD_DEBIT, AssetRpc.DEBIT);
        want.put(AssetStream.ASSET_STREAM_GUILD_CREDIT, AssetRpc.CREDIT);
        want.put(AssetStream.ASSET_STREAM_TRADE_DEBIT, AssetRpc.DEBIT);
        want.put(AssetStream.ASSET_STREAM_TRADE_CREDIT, AssetRpc.CREDIT);
        want.put(AssetStream.ASSET_STREAM_SYSTEM_CREDIT, AssetRpc.CREDIT);
        want.forEach((stream, rpc) -> assertThat(AssetOpDecisions.applyRpcOf(stream.getNumber())).as("%s", stream).contains(rpc));
        assertThat(AssetOpDecisions.applyRpcOf(AssetStream.ASSET_STREAM_UNSPECIFIED_VALUE)).as("未指定的流").isEmpty();
        assertThat(AssetOpDecisions.applyRpcOf(99)).as("越界流号").isEmpty();
        assertThat(AssetOpDecisions.applyRpcOf(-1)).isEmpty();
    }

    @Test
    void 过了截止改发中止() {
        long deadline = 1_700_000_600_000L;
        int debit = AssetStream.ASSET_STREAM_GUILD_DEBIT_VALUE;
        assertThat(AssetOpDecisions.rpcFor(op(debit, deadline, 0), deadline - 1)).contains(AssetRpc.DEBIT);
        assertThat(AssetOpDecisions.rpcFor(op(debit, deadline, 0), deadline)).as("恰在截止").contains(AssetRpc.ABORT_DEBIT);
        assertThat(AssetOpDecisions.rpcFor(op(debit, deadline, 0), deadline + 1)).contains(AssetRpc.ABORT_DEBIT);
        assertThat(AssetOpDecisions.rpcFor(op(AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE, 0, 0), Long.MAX_VALUE))
                .as("截止 0 = 永不中止").contains(AssetRpc.CREDIT);
        assertThat(AssetOpDecisions.rpcFor(op(99, 0, 0), 1)).as("坏流号且未到截止").isEmpty();
        assertThat(AssetOpDecisions.rpcFor(op(99, deadline, 0), deadline)).as("中止允许用于任何流").contains(AssetRpc.ABORT_DEBIT);
        // 无符号比较：截止 ≥ 2^63 时，「现在」小于它就不该中止
        long bigDeadline = 0x8000_0000_0000_1000L;
        assertThat(AssetOpDecisions.rpcFor(op(debit, bigDeadline, 0), 1_700_000_000_000L)).contains(AssetRpc.DEBIT);
    }

    // ================================================================ 粘性

    @Test
    void 部分发放码在重排时是粘性的() {
        int partial = AssetOpDecisions.REASON_PARTIAL_APPLIED;
        AssetOp sticky = op(AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE, 0, partial);
        AssetOp plain = op(AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE, 0, AssetOpDecisions.REASON_BAG_FULL);

        // 本地 NOT_HERE（reason 0）不许把 27007 抹平
        assertThat(AssetOpDecisions.carryPartialReason(sticky, AssetOpResult.localNotHere()).reason()).isEqualTo(partial);
        assertThat(AssetOpDecisions.carryPartialReason(sticky, res(AssetOutcome.ASSET_OUTCOME_RETRY,
                AssetOpDecisions.REASON_BAG_FULL, false)).reason()).as("后来的背包满也覆盖不掉").isEqualTo(partial);
        // 其余字段不动
        AssetOpResult carried = AssetOpDecisions.carryPartialReason(sticky, AssetOpResult.localNotHere());
        assertThat(carried.outcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_NOT_HERE);
        assertThat(carried.local()).isTrue();
        // 行上不是 27007：照写本次 reason
        AssetOpResult retry = res(AssetOutcome.ASSET_OUTCOME_RETRY, AssetOpDecisions.REASON_IN_BATTLE, false);
        assertThat(AssetOpDecisions.carryPartialReason(plain, retry)).isSameAs(retry);
        AssetOpResult already = res(AssetOutcome.ASSET_OUTCOME_APPLIED, partial, false);
        assertThat(AssetOpDecisions.carryPartialReason(sticky, already)).isSameAs(already);
    }

    // ================================================================ 退款判定 / 状态映射

    /** 退次数 / 退限购的判定只此一处（TestCounterRefundIsTheOnlyRefundRule，economy_repo_test.go:279）。 */
    @Test
    void 退款判定只此一处() {
        int period = 20_260_921;
        for (AssetOpStatus status : List.of(AssetOpStatus.REJECTED, AssetOpStatus.ABORTED)) {
            assertThat(AssetOpDecisions.counterRefund(GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE, 1, period, status))
                    .as("捐献 %s 要退次数", status)
                    .contains(new CounterRefund(GuildDailyCounterKind.GUILD_DAILY_COUNTER_KIND_DONATE, 1));
            assertThat(AssetOpDecisions.counterRefund(GuildAssetOpKind.GUILD_ASSET_OP_KIND_SHOP, 3, period, status))
                    .as("兑换 %s 要退限购", status)
                    .contains(new CounterRefund(GuildDailyCounterKind.GUILD_DAILY_COUNTER_KIND_SHOP, 3));
            assertThat(AssetOpDecisions.counterRefund(GuildAssetOpKind.GUILD_ASSET_OP_KIND_ACTIVITY_REWARD, 1, period, status))
                    .as("活动发奖不占计数行").isEmpty();
            assertThat(AssetOpDecisions.counterRefund(GuildAssetOpKind.GUILD_ASSET_OP_KIND_UNSPECIFIED, 1, period, status))
                    .isEmpty();
        }
        for (AssetOpStatus status : List.of(AssetOpStatus.APPLIED, AssetOpStatus.APPLIED_PARTIAL, AssetOpStatus.PENDING)) {
            assertThat(AssetOpDecisions.counterRefund(GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE, 1, period, status))
                    .as("%s 不退", status).isEmpty();
            assertThat(AssetOpDecisions.counterRefund(GuildAssetOpKind.GUILD_ASSET_OP_KIND_SHOP, 3, period, status))
                    .as("%s 不退", status).isEmpty();
        }
        assertThat(AssetOpDecisions.counterRefund(GuildAssetOpKind.GUILD_ASSET_OP_KIND_SHOP, 3, 0, AssetOpStatus.REJECTED))
                .as("不限购的商品当初没占计数行").isEmpty();
        assertThat(AssetOpDecisions.counterRefund(GuildAssetOpKind.GUILD_ASSET_OP_KIND_SHOP, 0, period, AssetOpStatus.REJECTED))
                .as("0 份不退").isEmpty();
        assertThat(AssetOpDecisions.counterRefund(GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE, 1, 0, AssetOpStatus.ABORTED))
                .isEqualTo(Optional.empty());
    }

    /** 语义状态 → 库值只此一处（TestStatusToRecordMapsEverySemanticStatus，economy_repo_test.go:84）。 */
    @Test
    void 状态映射与终态判定() {
        Map<AssetOpStatus, GuildAssetOpStatus> want = new EnumMap<>(AssetOpStatus.class);
        want.put(AssetOpStatus.PENDING, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
        want.put(AssetOpStatus.APPLIED, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED);
        want.put(AssetOpStatus.REJECTED, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED);
        want.put(AssetOpStatus.ABORTED, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_ABORTED);
        want.put(AssetOpStatus.APPLIED_PARTIAL, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_PARTIAL);
        assertThat(want.keySet()).isEqualTo(EnumSet.allOf(AssetOpStatus.class));
        want.forEach((s, r) -> assertThat(AssetOpStatus.toRecord(s)).as("%s", s).isEqualTo(r));
        assertThat(AssetOpStatus.toRecord(null)).as("未知语义值落 UNSPECIFIED，既不能当未决也不能伪装成功")
                .isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_UNSPECIFIED);
        // PENDING 的库值不能是 0：零值行（写坏 / 半截插入）绝不能被当成待办领走（TestPendingStatusIsNotZero）
        assertThat(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING_VALUE).isNotZero();
        // 与客户端镜像 GuildAssetOrderStatus 逐项同值（guild.proto:229-236）
        for (GuildAssetOpStatus s : GuildAssetOpStatus.values()) {
            if (s != GuildAssetOpStatus.UNRECOGNIZED) {
                assertThat(com.game.proto.guild.GuildAssetOrderStatus.forNumber(s.getNumber())).as("%s", s).isNotNull();
            }
        }

        for (AssetOpStatus s : AssetOpStatus.values()) {
            assertThat(s.terminal()).as("%s", s).isEqualTo(s != AssetOpStatus.PENDING);
            assertThat(AssetOpStatus.isTerminalRecord(AssetOpStatus.toRecord(s))).as("%s", s).isEqualTo(s.terminal());
        }
        assertThat(AssetOpStatus.isTerminalRecord(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_UNSPECIFIED)).isFalse();
        assertThat(AssetOpStatus.isTerminalRecord(GuildAssetOpStatus.UNRECOGNIZED)).isFalse();
    }

    /** 名字会进指标 label 与签名规范串，写飘了告警规则就全废、验签就全失败（TestStatusAndActionNames）。 */
    @Test
    void 标签与规范串名字() {
        assertThat(List.of(AssetOpStatus.values()).stream().map(AssetOpStatus::label))
                .containsExactly("pending", "applied", "rejected", "aborted", "applied_partial");
        assertThat(List.of(AssetOpAction.values()).stream().map(AssetOpAction::label))
                .containsExactly("finalize", "await_durable", "retry", "alert");
        assertThat(List.of(AssetRpc.values()).stream().map(AssetRpc::wireName))
                .containsExactly("debit", "abort_debit", "credit");
        assertThat(List.of(DeliveryOrigin.values())).containsExactly(DeliveryOrigin.SYNC, DeliveryOrigin.LOOP,
                DeliveryOrigin.MANUAL);
    }

    // ================================================================ 令牌 / 请求 / 人工终结

    @Test
    void 令牌非零且最低位置一() {
        SplittableRandom random = new SplittableRandom(42);
        boolean sawHigh = false;
        for (int i = 0; i < 1_000; i++) {
            long token = AssetOpDecisions.leaseToken(random);
            assertThat(token & 1L).isEqualTo(1L);
            assertThat(token).isNotZero();
            sawHigh |= token < 0;
        }
        assertThat(sawHigh).as("约一半 ≥ 2^63：落库必须按无符号绑定").isTrue();
        assertThat(AssetOpDecisions.leaseToken(new FixedRandom(0))).isEqualTo(1L);
        assertThat(AssetOpDecisions.leaseToken(new FixedRandom(-2))).isEqualTo(-1L);
    }

    /** 固定输出的随机源。 */
    private record FixedRandom(long value) implements java.util.random.RandomGenerator {
        @Override
        public long nextLong() {
            return value;
        }
    }

    @Test
    void 请求不带签名_字段原样() {
        AssetBundle bundle = AssetBundle.newBuilder()
                .addCurrencies(AssetCurrency.newBuilder().setCurrencyType(0).setAmount(10_000)).build();
        AssetOp op = new AssetOp(0x8000_0000_0000_0001L, 9, AssetStream.ASSET_STREAM_GUILD_DEBIT_VALUE, 5, 1_700_000_000_000L,
                0x8000_0000_0000_0001L, 24, bundle, 2, 0, 0xFFFF_FFFF_FFFF_FFFFL, 0);
        var request = op.request();
        assertThat(request.hasAuth()).as("签名由调用方每次发包前现签").isFalse();
        assertThat(request.getPlayerId()).isEqualTo(9);
        assertThat(request.getStream()).isEqualTo(AssetStream.ASSET_STREAM_GUILD_DEBIT);
        assertThat(request.getSeq()).isEqualTo(5);
        assertThat(request.getStreamEpoch()).isEqualTo(1_700_000_000_000L);
        assertThat(request.getCorrelationId()).isEqualTo(0x8000_0000_0000_0001L);
        assertThat(request.getTxType()).isEqualTo(24);
        assertThat(request.getBundle()).isEqualTo(bundle);
        assertThat(new AssetOp(1, 2, 99, 1, 1, 1, 24, bundle, 0, 0, 1, 0).request().getStreamValue())
                .as("坏流号原样带出（scene 会判 UNKNOWN）").isEqualTo(99);
    }

    @Test
    void 人工终结入参校验() {
        String op64 = "o".repeat(GuildLimits.RESOLVED_BY_MAX_CHARS);
        String reason191 = "由".repeat(GuildLimits.RESOLVE_REASON_MAX_CHARS);
        assertThat(new ManualResolution(1, AssetOpStatus.APPLIED, op64, reason191).validate()).isNull();
        assertThat(new ManualResolution(1, AssetOpStatus.ABORTED, "ops", "").validate()).as("依据可以为空（CLI 另判非空）").isNull();
        assertThat(new ManualResolution(1, AssetOpStatus.PENDING, "ops", "r").validate()).contains("terminal");
        assertThat(new ManualResolution(1, null, "ops", "r").validate()).contains("terminal");
        assertThat(new ManualResolution(1, AssetOpStatus.APPLIED, "", "r").validate()).contains("required");
        assertThat(new ManualResolution(1, AssetOpStatus.APPLIED, op64 + "x", "r").validate()).contains("limit 64");
        assertThat(new ManualResolution(1, AssetOpStatus.APPLIED, "ops", reason191 + "x").validate()).contains("limit 191");
        // 按码点数计：64 个表情（各占两个 UTF-16 单元）仍合法
        String emoji64 = "😀".repeat(GuildLimits.RESOLVED_BY_MAX_CHARS);
        assertThat(new ManualResolution(1, AssetOpStatus.APPLIED, emoji64, "r").validate()).isNull();
    }
}
