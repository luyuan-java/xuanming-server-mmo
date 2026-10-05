package com.game.data.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.game.data.DataProperties;
import com.game.data.query.TransactionLogQueryService;
import com.game.data.recall.RecallPlanner;
import com.game.data.snapshot.SnapshotAdminService;
import com.game.data.snapshot.SnapshotAdminService.CreateRequest;
import com.game.data.snapshot.SnapshotAdminService.Created;
import com.game.data.snapshot.SnapshotDiffService;
import com.game.data.store.TransactionLogEntry;
import com.game.data.store.TransactionLogMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 批次 7.2a 新接口的参数绑定与结果码（服务层用替身；SQL 与业务规则在各自的 *SqlTest 里）。 */
class OpsAdminControllersTest {

    private static final long NOW = 1_800_000_000_000L;

    private final SnapshotAdminService snapshots = mock(SnapshotAdminService.class);
    private final SnapshotDiffService diff = mock(SnapshotDiffService.class);
    private final RecallPlanner planner = mock(RecallPlanner.class);
    private final TransactionLogMapper txlog = mock(TransactionLogMapper.class);
    private final DataProperties props = new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate("xm.data", DataProperties.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SnapshotAdminController(snapshots),
                    new PlayerOpsAdminController(diff),
                    new ItemAdminController(new TransactionLogQueryService(txlog, Duration.ofDays(7))),
                    new RecallAdminController(planner, props, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)))
            .setControllerAdvice(new OpsErrorAdvice()).build();

    @Test
    void 手工快照_必带幂等键与原因_原因缺省GM_MANUAL_操作人取请求头() throws Exception {
        when(snapshots.create(any(), anyString(), anyString())).thenReturn(new Created(Map.of("snapshotId", "9"), false));
        String body = "{\"player\":\"1001\",\"note\":\"事件:补偿\",\"reason\":\"客诉\"}";

        mvc.perform(post("/admin/player-snapshots").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(AdminAuthFilter.OPERATOR_HEADER, "ops"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        mvc.perform(post("/admin/player-snapshots").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"player\":\"1001\"}").header("Idempotency-Key", "k1")
                        .header(AdminAuthFilter.OPERATOR_HEADER, "ops"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/player-snapshots").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Idempotency-Key", "bad key").header(AdminAuthFilter.OPERATOR_HEADER, "ops"))
                .andExpect(status().isBadRequest());
        verify(snapshots, never()).create(any(), any(), any());

        mvc.perform(post("/admin/player-snapshots").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Idempotency-Key", "k1").header(AdminAuthFilter.OPERATOR_HEADER, "ops"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.snapshotId").value("9"));
        verify(snapshots).create(new CreateRequest(1001, 6, "事件:补偿", "客诉"), "ops", "k1");
    }

    @Test
    void 快照详情与差异的参数() throws Exception {
        when(snapshots.detail(-1L, true)).thenReturn(Map.of("snapshotId", "18446744073709551615"));
        when(diff.diff(eq(1001L), eq(5L), eq(null))).thenReturn(Map.of("a", 1));
        when(diff.diff(eq(1001L), eq(null), eq(300L))).thenReturn(Map.of("b", 2));

        mvc.perform(get("/admin/player-snapshots/18446744073709551615").param("includeState", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.snapshotId").value("18446744073709551615"));
        mvc.perform(get("/admin/players/1001/snapshot-diff").param("snapshot", "5")).andExpect(jsonPath("$.a").value(1));
        mvc.perform(get("/admin/players/1001/snapshot-diff").param("atMs", "300")).andExpect(jsonPath("$.b").value(2));
        mvc.perform(get("/admin/players/x/snapshot-diff").param("atMs", "300")).andExpect(status().isBadRequest());
    }

    private static TransactionLogEntry hop(long txId, long timeMs, long from, long to, int reason, int qty) {
        TransactionLogEntry e = new TransactionLogEntry();
        e.setTxId(txId);
        e.setTimeMs(timeMs);
        e.setFromPlayer(from);
        e.setToPlayer(to);
        e.setReason(reason);
        e.setKind(2);
        e.setItemUuid(77);
        e.setItemQuantity(qty);
        return e;
    }

    @Test
    void 物品追溯_末跳提示_获得为持有_销毁数量0为合并_还有下一页时不给提示() throws Exception {
        when(txlog.query(any())).thenReturn(List.of(hop(1, 100, 0, 7, 8, 1), hop(2, 200, 7, 8, 1, 1)));
        mvc.perform(get("/admin/items/77/trace")).andExpect(status().isOk())
                .andExpect(jsonPath("$.hops.length()").value(2))
                .andExpect(jsonPath("$.hint").value("HELD_BY"))
                .andExpect(jsonPath("$.holder").value("8"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        when(txlog.query(any())).thenReturn(List.of(hop(1, 100, 0, 7, 8, 1), hop(3, 300, 7, 0, 13, 0)));
        mvc.perform(get("/admin/items/77/trace")).andExpect(jsonPath("$.hint").value("MERGED"));

        when(txlog.query(any())).thenReturn(List.of(hop(1, 100, 0, 7, 8, 1), hop(3, 300, 7, 0, 10, 1)));
        mvc.perform(get("/admin/items/77/trace")).andExpect(jsonPath("$.hint").value("DESTROYED"));

        when(txlog.query(any())).thenReturn(List.of(hop(1, 100, 0, 7, 8, 1), hop(2, 200, 7, 8, 1, 1)));
        mvc.perform(get("/admin/items/77/trace").param("limit", "1"))
                .andExpect(jsonPath("$.nextCursor").value("100:1"))
                .andExpect(jsonPath("$.hint").doesNotExist());
        mvc.perform(get("/admin/items/0/trace")).andExpect(status().isBadRequest());
    }

    @Test
    void 回收_dryRun_false回501_执行参数取值校验_dry_run走计划() throws Exception {
        when(planner.dryRun(any(), any())).thenReturn(Map.of("dryRun", true));
        String base = "\"kind\":\"currency\",\"currencyType\":0,\"sinceMs\":0,\"untilMs\":1000,\"players\":[\"7\"]";

        mvc.perform(post("/admin/recalls").contentType(MediaType.APPLICATION_JSON).content("{" + base + ",\"dryRun\":false}")
                        .header(AdminAuthFilter.OPERATOR_HEADER, "ops"))
                .andExpect(status().isNotImplemented()).andExpect(jsonPath("$.code").value("not_implemented"));
        mvc.perform(post("/admin/recalls").contentType(MediaType.APPLICATION_JSON).content("{" + base
                        + ",\"ifOnline\":\"maybe\"}").header(AdminAuthFilter.OPERATOR_HEADER, "ops"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/recalls").contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"currency\"}")
                        .header(AdminAuthFilter.OPERATOR_HEADER, "ops"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        verify(planner, never()).dryRun(any(), any());

        mvc.perform(post("/admin/recalls").contentType(MediaType.APPLICATION_JSON).content("{" + base + ",\"dryRun\":true}")
                        .header(AdminAuthFilter.OPERATOR_HEADER, "ops"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(true));
        verify(planner).dryRun(any(), eq("ops"));
    }
}
