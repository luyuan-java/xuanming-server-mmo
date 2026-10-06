package com.game.scene.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.game.scene.metrics.SceneBattleMetrics.AckResult;
import com.game.scene.metrics.SceneBattleMetrics.AckTrigger;
import com.game.scene.metrics.SceneBattleMetrics.Cancel;
import com.game.scene.metrics.SceneBattleMetrics.Confirm;
import com.game.scene.metrics.SceneBattleMetrics.HintTrigger;
import com.game.scene.metrics.SceneBattleMetrics.ItemKind;
import com.game.scene.metrics.SceneBattleMetrics.Phase;
import com.game.scene.metrics.SceneBattleMetrics.Prepare;
import com.game.scene.metrics.SceneBattleMetrics.RebuildReason;
import com.game.scene.metrics.SceneBattleMetrics.RebuildResult;
import com.game.scene.metrics.SceneBattleMetrics.Recovery;
import com.game.scene.metrics.SceneBattleMetrics.Rescue;
import com.game.scene.metrics.SceneBattleMetrics.RpcMethod;
import com.game.scene.metrics.SceneBattleMetrics.RpcResult;
import com.game.scene.metrics.SceneBattleMetrics.SettlementPath;
import com.game.scene.metrics.SceneBattleMetrics.SettlementResult;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * scene 侧回合制战斗指标（scene-battle-spec §9 的 scene 表）：按 Prometheus 导出名逐项核对——每个指标的<b>每个标签取值都在构造时预建</b>
 * （初值 0：「从没发生」与「指标不存在」分得开），导出的时间序列与下面照规格抄的表<b>一一相等</b>（多一个取值、少一个取值、改了名都先红在这里）；
 * 标签只有有界枚举的那几个键（不带 player_id / battle_id / 会话 / 节点号，{@code AGENTS.md} §5）；每个取值有自己的发射入口、各计各的。
 *
 * <p>与规格表格的两处出入（已在类注释 / 审计里登记）：{@code rebuilds} 多一个取值 {@code skipped_pending}（审计 OPS-13）；
 * 在途闸的 {@code xm_scene_battle_gate_rejects_total} 由 {@link SceneMetrics} 注册（各服务闸共用那个实例），在 {@code SceneMetricsTest} 里核。
 */
class SceneBattleMetricsTest {

    /** 单标签的计数器：导出名 → 标签键 → 取值（照规格 §9 逐字抄）。 */
    private static final Map<String, Map.Entry<String, List<String>>> SINGLE_TAG = singleTag();
    private static final List<String> REBUILD_REASONS = List.of("login", "late_confirm", "carried");
    private static final List<String> REBUILD_RESULTS = List.of("rebuilt", "reverted", "ledger_hit", "skipped_corrupt", "skipped_pending",
            "miss", "error");
    private static final List<String> RPC_METHODS = List.of("prepare", "cancel", "confirm", "settlement");
    private static final List<String> RPC_RESULTS = List.of("handled", "not_here", "deferred", "overloaded", "error");
    private static final List<String> SETTLEMENT_PATHS = List.of("online", "by_lock", "login", "rescue");
    private static final List<String> SETTLEMENT_RESULTS = List.of("applied", "already_applied", "discarded_invalid", "discarded_mismatch",
            "discarded_void", "deferred_frozen", "deferred_currency", "deferred_recovering", "deferred_lock_read", "deferred_ledger",
            "not_here");
    private static final List<String> ACK_TRIGGERS = List.of("apply", "persisted", "reaper", "login", "discard");
    private static final List<String> ACK_RESULTS = List.of("released", "not_ours", "deferred", "error");
    private static final List<String> UNTAGGED = List.of("xm_scene_battle_ledger_evictions_total", "xm_scene_battle_pending_corrupt_total",
            "xm_scene_battle_exp_ignored_total");
    private static final List<String> FROZEN_STATES = List.of("preparing", "fighting");

    private static Map<String, Map.Entry<String, List<String>>> singleTag() {
        Map<String, Map.Entry<String, List<String>>> out = new LinkedHashMap<>();
        out.put("xm_scene_battle_prepares_total", Map.entry("result", List.of("ok", "invalid", "not_here", "switching", "in_battle",
                "not_ready", "dead", "lock_held", "redis_error", "stale", "cancelled")));
        out.put("xm_scene_battle_cancels_total", Map.entry("result", List.of("cleared", "idempotent", "mismatch", "rejected_fighting",
                "deferred", "offline_deleted", "offline_rejected_fighting", "offline_absent", "offline_error")));
        out.put("xm_scene_battle_confirms_total", Map.entry("result", List.of("upgraded", "idempotent", "reextended", "mismatch", "rebuilt",
                "frozen_extended", "offline_extended", "offline_miss", "ledger_hit", "invalid", "error")));
        out.put("xm_scene_battle_freeze_expired_total", Map.entry("phase", List.of("preparing", "fighting")));
        out.put("xm_scene_battle_rescues_total", Map.entry("result", List.of("applied", "already_applied", "deferred", "miss", "error",
                "gave_up")));
        out.put("xm_scene_battle_reconnect_hints_total", Map.entry("trigger", List.of("confirm", "late_confirm", "login", "carried")));
        out.put("xm_scene_battle_recovery_total", Map.entry("result", List.of("ready", "retry", "error")));
        out.put("xm_scene_battle_items_total", Map.entry("kind", List.of("consume_clamped", "consume_rejected", "drop_overflow",
                "drop_lost")));
        return out;
    }

    private final PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final SceneBattleMetrics metrics = new SceneBattleMetrics(prometheus);

    @AfterEach
    void close() {
        prometheus.close();
    }

    /** 规格表里的全部时间序列（{@code 名字{键="值",…}}，标签按键名排序，同 Prometheus 文本格式）。 */
    private static Set<String> specSeries(boolean withGauge) {
        Set<String> out = new TreeSet<>();
        SINGLE_TAG.forEach((name, tag) -> tag.getValue().forEach(value -> out.add(series(name, tag.getKey(), value))));
        for (String reason : REBUILD_REASONS) {
            for (String result : REBUILD_RESULTS) {
                out.add(series("xm_scene_battle_rebuilds_total", "reason", reason, "result", result));
            }
        }
        for (String method : RPC_METHODS) {
            for (String result : RPC_RESULTS) {
                out.add(series("xm_scene_battle_rpc_total", "method", method, "result", result));
            }
        }
        for (String path : SETTLEMENT_PATHS) {
            for (String result : SETTLEMENT_RESULTS) {
                out.add(series("xm_scene_battle_settlements_total", "path", path, "result", result));
            }
        }
        for (String trigger : ACK_TRIGGERS) {
            for (String result : ACK_RESULTS) {
                out.add(series("xm_scene_battle_acks_total", "result", result, "trigger", trigger));
            }
        }
        out.addAll(UNTAGGED);
        if (withGauge) {
            FROZEN_STATES.forEach(state -> out.add(series("xm_scene_battle_frozen", "state", state)));
        }
        return out;
    }

    private static String series(String name, String... keyValues) {
        Map<String, String> sorted = new TreeMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            sorted.put(keyValues[i], keyValues[i + 1]);
        }
        StringBuilder out = new StringBuilder(name).append('{');
        sorted.forEach((key, value) -> out.append(key).append("=\"").append(value).append("\","));
        out.setCharAt(out.length() - 1, '}');
        return out.toString();
    }

    /** 抓取文本里的全部样本：时间序列 → 值。 */
    private Map<String, Double> scrape() {
        Map<String, Double> out = new TreeMap<>();
        for (String line : prometheus.scrape().split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int space = line.lastIndexOf(' ');
            out.put(line.substring(0, space), Double.parseDouble(line.substring(space + 1)));
        }
        return out;
    }

    @Test
    void 规格表里每个指标的每个取值都以0预建_导出的时间序列与规格逐项相等() {
        Map<String, Double> samples = scrape();

        assertThat(samples.keySet()).as("导出的时间序列 = 规格 §9 的 scene 表（外加 rebuilds 的 skipped_pending）")
                .containsExactlyInAnyOrderElementsOf(specSeries(true));
        assertThat(samples.values()).as("初值都是 0").containsOnly(0.0);
        // 抽几条按字面核对导出文本的形状
        assertThat(prometheus.scrape()).contains(
                "xm_scene_battle_prepares_total{result=\"lock_held\"} 0.0",
                "xm_scene_battle_rebuilds_total{reason=\"late_confirm\",result=\"skipped_pending\"} 0.0",
                "xm_scene_battle_rpc_total{method=\"settlement\",result=\"overloaded\"} 0.0",
                "xm_scene_battle_settlements_total{path=\"by_lock\",result=\"deferred_lock_read\"} 0.0",
                "xm_scene_battle_acks_total{result=\"not_ours\",trigger=\"persisted\"} 0.0",
                "xm_scene_battle_frozen{state=\"fighting\"} 0.0",
                "xm_scene_battle_ledger_evictions_total 0.0",
                "xm_scene_battle_pending_corrupt_total 0.0",
                "xm_scene_battle_exp_ignored_total 0.0");
    }

    @Test
    void 标签低基数_只有有界枚举的键_取值都是小写蛇形_没有任何号() {
        Set<String> keys = new TreeSet<>();
        Set<String> values = new TreeSet<>();
        Matcher label = Pattern.compile("([a-z_]+)=\"([^\"]*)\"").matcher(String.join("\n", scrape().keySet()));
        while (label.find()) {
            keys.add(label.group(1));
            values.add(label.group(2));
        }

        assertThat(keys).containsExactlyInAnyOrder("result", "reason", "phase", "trigger", "state", "method", "path", "kind");
        assertThat(values).as("取值是枚举名的小写（没有数字 = 没有玩家号 / 战斗号 / 会话 / 节点号）").allMatch(v -> v.matches("[a-z_]+"));
        assertThat(scrape()).as("时间序列总数是常数，不随玩家 / 战斗数增长").hasSize(specSeries(true).size());
        assertThat(specSeries(true)).hasSize(11 + 9 + 11 + 2 + 6 + 4 + 3 + 4 + 21 + 20 + 44 + 20 + 3 + 2);
    }

    /** 每个枚举取值各发射一次：每条计数器恰好是 1（没有两个取值落到同一条、没有哪条没有发射入口），之后序列集合不变。 */
    @Test
    void 每个取值有自己的发射入口_各计各的_发射不新增时间序列() {
        for (Prepare v : Prepare.values()) {
            metrics.prepare(v);
        }
        for (Cancel v : Cancel.values()) {
            metrics.cancel(v);
        }
        for (Confirm v : Confirm.values()) {
            metrics.confirm(v);
        }
        for (RebuildReason reason : RebuildReason.values()) {
            for (RebuildResult result : RebuildResult.values()) {
                metrics.rebuild(reason, result);
            }
        }
        for (Phase v : Phase.values()) {
            metrics.freezeExpired(v);
        }
        for (Rescue v : Rescue.values()) {
            metrics.rescue(v);
        }
        for (HintTrigger v : HintTrigger.values()) {
            metrics.reconnectHint(v);
        }
        for (RpcMethod method : RpcMethod.values()) {
            for (RpcResult result : RpcResult.values()) {
                metrics.rpc(method, result);
            }
        }
        for (SettlementPath path : SettlementPath.values()) {
            for (SettlementResult result : SettlementResult.values()) {
                metrics.settlement(path, result);
            }
        }
        for (AckTrigger trigger : AckTrigger.values()) {
            for (AckResult result : AckResult.values()) {
                metrics.ack(trigger, result);
            }
        }
        for (Recovery v : Recovery.values()) {
            metrics.recovery(v);
        }
        for (ItemKind v : ItemKind.values()) {
            metrics.item(v);
        }
        metrics.ledgerEvicted();
        metrics.pendingCorrupt();
        metrics.expIgnored();

        Map<String, Double> samples = scrape();

        assertThat(samples.keySet()).containsExactlyInAnyOrderElementsOf(specSeries(true));
        for (String counter : specSeries(false)) {
            assertThat(samples.get(counter)).as(counter).isEqualTo(1.0);
        }
        // 抽查：枚举名 → 标签取值是小写原名
        metrics.confirm(Confirm.FROZEN_EXTENDED);
        metrics.settlement(SettlementPath.BY_LOCK, SettlementResult.DEFERRED_LOCK_READ);
        metrics.ack(AckTrigger.PERSISTED, AckResult.NOT_OURS);
        assertThat(prometheus.scrape()).contains(
                "xm_scene_battle_confirms_total{result=\"frozen_extended\"} 2.0",
                "xm_scene_battle_settlements_total{path=\"by_lock\",result=\"deferred_lock_read\"} 2.0",
                "xm_scene_battle_acks_total{result=\"not_ours\",trigger=\"persisted\"} 2.0");
    }

    /** 冻结人数是 reaper 每轮推的绝对值（不是累加）。 */
    @Test
    void 冻结人数_推的是绝对值() {
        metrics.frozen(2, 5);
        assertThat(prometheus.scrape()).contains("xm_scene_battle_frozen{state=\"preparing\"} 2.0",
                "xm_scene_battle_frozen{state=\"fighting\"} 5.0");

        metrics.frozen(0, 1);

        assertThat(prometheus.scrape()).contains("xm_scene_battle_frozen{state=\"preparing\"} 0.0",
                "xm_scene_battle_frozen{state=\"fighting\"} 1.0");
    }

    @Test
    void 不导出的实例_全部入口可调() {
        SceneBattleMetrics noop = SceneBattleMetrics.noop();

        assertThatCode(() -> {
            noop.prepare(Prepare.OK);
            noop.rebuild(RebuildReason.LOGIN, RebuildResult.SKIPPED_PENDING);
            noop.rpc(RpcMethod.SETTLEMENT, RpcResult.OVERLOADED);
            noop.ack(AckTrigger.DISCARD, AckResult.ERROR);
            noop.frozen(1, 2);
            noop.ledgerEvicted();
        }).doesNotThrowAnyException();
    }
}
