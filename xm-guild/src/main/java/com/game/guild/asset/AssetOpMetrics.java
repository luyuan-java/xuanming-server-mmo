package com.game.guild.asset;

import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetOutcome;
import com.game.api.proto.AssetStream;
import com.game.discovery.location.SceneAssetLocator.ResolveResult;
import java.util.List;
import java.util.Locale;

/**
 * 资产通道调用方与重投循环的指标出口（基线 go/shared/assetop/metrics.go 的 {@code assetop_*} 与 scenenode 的 {@code scenenode_resolve_total}；
 * guild-economy-spec §8.2）。实现是 {@code GuildMetrics}（名字带 {@code xm_guild_} 前缀）；单测用 {@link #NONE} 或记录器。
 *
 * <p><b>铁律</b>（metrics.go:12-17、AGENTS.md §5）：label 全部来自本接口里的固定枚举，{@code player_id / op_id / seq} 一律不进 label（只进日志）。
 * 流号只分三档（{@link #streamLabel}）：xm-guild 只拥有 GUILD_DEBIT / GUILD_CREDIT（不变量 I6），其余（含 0 与越界的坏行）归 {@code other}，
 * 绝不把原始数值放进 label。实现必须便宜、不抛异常、线程安全。
 */
public interface AssetOpMetrics {

    /** 流号 label 的全集（启动时按它预注册）。 */
    List<String> STREAM_LABELS = List.of("guild_debit", "guild_credit", "other");

    /** {@code xm_guild_assetop_pending_oldest_age_seconds{stream}} 刷新的流（基线 GuildAssetStreams，svc/asset_op.go:78-85）。 */
    List<Integer> GUILD_STREAMS = List.of(AssetStream.ASSET_STREAM_GUILD_DEBIT_VALUE, AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE);

    /** 一次资产 RPC 的结局 label（{@code rpc_total{outcome}}；scene 的五个结局 + 本端的三个）。 */
    enum RpcOutcome {
        UNKNOWN, APPLIED, REJECTED, RETRY, NOT_HERE,
        /** scene 回了本端不认识的结局值。 */
        OTHER,
        /** 传输层失败（连不上 / 超时 / 过载 / 鉴权失败）。 */
        ERROR,
        /** 没发出去：此刻没有节点持有该玩家（本地合成 NOT_HERE）。 */
        NO_LOCATION;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** scene 结局 → label（outcomeLabel，metrics.go:174-186）。 */
        public static RpcOutcome of(AssetOutcome outcome) {
            if (outcome == null) {
                return OTHER;
            }
            return switch (outcome) {
                case ASSET_OUTCOME_UNKNOWN -> UNKNOWN;
                case ASSET_OUTCOME_APPLIED -> APPLIED;
                case ASSET_OUTCOME_REJECTED -> REJECTED;
                case ASSET_OUTCOME_RETRY -> RETRY;
                case ASSET_OUTCOME_NOT_HERE -> NOT_HERE;
                default -> OTHER;
            };
        }
    }

    /** durable 重查的结果（{@code requery_total{result}}）。 */
    enum RequeryResult {
        /** 等到了落盘。 */
        DURABLE,
        /** 预算用完仍未落盘，或重查拿到非终结结局（换节点 / 账本判坏）。 */
        TIMEOUT,
        /** 重查出错（传输失败）。 */
        ERROR;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 领取单行的结果（{@code claim_total{result}}）。 */
    enum ClaimResult {
        CLAIMED, LOST, POISON;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 离线读已落盘账本的结果（{@code ledger_read_total{result}}）。 */
    enum LedgerReadResult {
        /** 读到 APPLIED / REJECTED：可终结。 */
        FINALIZED,
        /** 读到了账本，但这个 seq 不是结论（未见 / 旧纪元 / 滑出窗口）：继续等。 */
        UNSEEN,
        /** 没有 player_state 行。 */
        ABSENT,
        /** 读失败 / 超时 / 解析失败 / 账本加载即判损坏。 */
        ERROR;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 存储出错的种类（{@code store_errors_total{op}}；基线八个 + Java 的 settle_rejected）。 */
    enum StoreOp {
        LIST, CLAIM, DECODE, FINALIZE, RESCHEDULE, LEDGER_READ, PENDING_AGE, MANUAL_RESOLVE,
        /** Java 增项：同步投递的落库执行器满了（行仍 PENDING，租约到期后由循环接手，scene 对同一 seq 只读答复）。 */
        SETTLE_REJECTED,
        /** Java 增项：同步投递的落库在执行器里排队太久、开工时已来不及在请求截止前回包（不落库，行留 PENDING 由循环接手并推送）。 */
        SETTLE_LATE;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 流号 → label：1 guild_debit、2 guild_credit、其余 other。 */
    static String streamLabel(int stream) {
        return switch (stream) {
            case AssetStream.ASSET_STREAM_GUILD_DEBIT_VALUE -> STREAM_LABELS.get(0);
            case AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE -> STREAM_LABELS.get(1);
            default -> STREAM_LABELS.get(2);
        };
    }

    // ---------------------------------------------------------------- 调用方（caller.go）

    /** 一次实际发出的资产 RPC（{@code rpc_total} + {@code rpc_duration}）。 */
    void rpc(int stream, AssetRpc rpc, RpcOutcome outcome, long elapsedNanos);

    /** 没发出去（{@code rpc_total{outcome="no_location"}}，不进耗时）。 */
    void noLocation(int stream, AssetRpc rpc);

    void requery(AssetRpc rpc, RequeryResult result);

    /** 同一 seq 两次查询给出不同终结结局（违反 I2，必须告警）。 */
    void outcomeFlip(int stream);

    /** 部分发放（对侧账不做，须人工补偿）。 */
    void partial(int stream);

    /** 一次定位的结局（{@code xm_guild_scene_resolve_total{result}}）。 */
    void resolved(ResolveResult result);

    // ---------------------------------------------------------------- 循环（reconcile.go）

    void finalized(int stream, AssetOpStatus status);

    void rescheduled(int stream, AssetOpAction reason);

    /** 重排落空：租约已被别的副本接管，本次结果被丢弃。 */
    void rescheduleLost(int stream);

    /** scene 回 UNKNOWN / 坏流号 / 终结分支算不出状态（必须告警）。 */
    void unknown(int stream);

    void claim(ClaimResult result);

    void ledgerRead(LedgerReadResult result);

    void manualResolve(AssetOpStatus status);

    void storeError(StoreOp op);

    /** 某条流最老未决行的年龄（秒；没有未决行为 0）。 */
    void pendingOldestAge(int stream, double seconds);

    /** 不接指标（单测、CLI）。 */
    AssetOpMetrics NONE = new AssetOpMetrics() {
        @Override
        public void rpc(int stream, AssetRpc rpc, RpcOutcome outcome, long elapsedNanos) {
        }

        @Override
        public void noLocation(int stream, AssetRpc rpc) {
        }

        @Override
        public void requery(AssetRpc rpc, RequeryResult result) {
        }

        @Override
        public void outcomeFlip(int stream) {
        }

        @Override
        public void partial(int stream) {
        }

        @Override
        public void resolved(ResolveResult result) {
        }

        @Override
        public void finalized(int stream, AssetOpStatus status) {
        }

        @Override
        public void rescheduled(int stream, AssetOpAction reason) {
        }

        @Override
        public void rescheduleLost(int stream) {
        }

        @Override
        public void unknown(int stream) {
        }

        @Override
        public void claim(ClaimResult result) {
        }

        @Override
        public void ledgerRead(LedgerReadResult result) {
        }

        @Override
        public void manualResolve(AssetOpStatus status) {
        }

        @Override
        public void storeError(StoreOp op) {
        }

        @Override
        public void pendingOldestAge(int stream, double seconds) {
        }
    };
}
