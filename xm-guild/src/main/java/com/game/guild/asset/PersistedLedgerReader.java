package com.game.guild.asset;

import com.game.api.proto.AssetOutcome;
import com.game.guild.rules.GuildLimits;
import com.game.player.store.asset.PersistedAssetLedger;
import com.game.player.store.asset.PersistedAssetLedgerReader;
import com.game.player.store.asset.PersistedAssetLedgerReader.Read;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * 重投循环的离线读已落盘账本（E8 / Q1：Java 接线，基线帮会没接 {@code Loop.Ledger}；reconcile.go:635-667 tryPersistedLedger、
 * classify.go:143-161 ClassifyPersisted；guild-economy-spec §2.10、§4.9）。
 *
 * <p>读的是 {@code xm_java.player_state} 里该玩家<b>已提交、带 owner_epoch 围栏</b>的那份记录（Java 的 durable 就是它，E7），分类用 xm-player-store 的
 * {@link PersistedAssetLedger}——与 scene 在线账本同一份判定代码（Q3a）。只有 APPLIED / REJECTED 是结论，<b>天然 durable</b>（按不变量 I2，已记账的
 * 结局永不改变），可直接终结；未见 / 其他 → 继续等，<b>绝不在调用方判中止</b>（I7：scene 没记账就不能保证这个 seq 将来不会被晚到的请求应用）。
 * 加载即判损坏的账本按读失败处理（基线 Go 不做整本校验；Java 与 scene 同口径更严，损坏只会让调用方继续等）。
 *
 * <p>阻塞 JDBC，单次上限 {@value GuildLimits#ASSET_LEDGER_READ_TIMEOUT_MS} ms，不重试（下一轮投递本身就是重试）；只在循环 worker 线程上调用。
 * 线程安全（无状态）。唯一能让已 durable 的结局从 player_state 里「消失」的是回档（7.2），回档闸接入之前 Java 不提供回档入口（§9.2 第 9 条）。
 */
public final class PersistedLedgerReader {

    /** 读一名玩家已落盘账本（生产 {@link PersistedAssetLedgerReader#read}；从不抛异常）。 */
    @FunctionalInterface
    public interface Source {
        Read read(long playerId);
    }

    /** 一次离线读的结论（四选一，同 {@code ledger_read_total{result}}）。 */
    public sealed interface Outcome permits Finalized, Unseen, Absent, Unreadable {
    }

    /** 已落盘的结局（APPLIED / REJECTED，durable）：可直接终结。 */
    public record Finalized(AssetOpResult result) implements Outcome {
        public Finalized {
            Objects.requireNonNull(result, "result");
        }
    }

    /** 读到了账本，但这个 seq 不是结论（未见 / 旧纪元 / 滑出窗口 / 跳号）。 */
    public record Unseen() implements Outcome {
    }

    /** 没有 player_state 行（玩家从未写过状态）。 */
    public record Absent() implements Outcome {
    }

    /** 读失败 / 超时 / 解析失败，或账本加载即判损坏：结局未知，不得终结。 */
    public record Unreadable(String reason) implements Outcome {
    }

    private final Source source;

    public PersistedLedgerReader(Source source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** 生产装配：用帮会自己的数据源直读 {@code xm_java.player_state}（单次 300 ms）。 */
    public static PersistedLedgerReader of(DataSource dataSource) {
        PersistedAssetLedgerReader reader = new PersistedAssetLedgerReader(dataSource,
                Duration.ofMillis(GuildLimits.ASSET_LEDGER_READ_TIMEOUT_MS));
        return new PersistedLedgerReader(reader::read);
    }

    /** 读并分类这一行的 (流, 纪元, seq)。阻塞；从不抛异常。 */
    public Outcome classify(AssetOp op) {
        Read read;
        try {
            read = source.read(op.playerId());
        } catch (RuntimeException e) {
            return new Unreadable("读已落盘账本抛异常: " + e);
        }
        return switch (read) {
            case Read.Absent ignored -> new Absent();
            case Read.Failed failed -> new Unreadable(failed.reason());
            case Read.Loaded loaded -> classify(loaded.ledger(), op);
            case null -> new Unreadable("读已落盘账本返回了 null");
        };
    }

    private static Outcome classify(PersistedAssetLedger ledger, AssetOp op) {
        if (ledger.invalidReason() != null) {
            return new Unreadable("已落盘账本加载即判损坏: " + ledger.invalidReason());
        }
        PersistedAssetLedger.Outcome outcome = ledger.outcomeOf(op.stream(), op.streamEpoch(), op.seq());
        if (!outcome.conclusive()) {
            return new Unseen();
        }
        return switch (outcome.state()) {
            // 部分发放带 27007（ClassifyPersisted 的返回值，classify.go:149-152），FinalStatus 据此落 APPLIED_PARTIAL
            case APPLIED -> new Finalized(AssetOpResult.persisted(AssetOutcome.ASSET_OUTCOME_APPLIED,
                    outcome.partial() ? AssetOpDecisions.REASON_PARTIAL_APPLIED : 0, outcome.partial()));
            case REJECTED -> new Finalized(AssetOpResult.persisted(AssetOutcome.ASSET_OUTCOME_REJECTED,
                    outcome.rejectionReason(), false));
            default -> new Unseen();
        };
    }
}
