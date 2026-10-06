package com.game.data.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.game.data.DataProperties;
import com.game.data.ops.OpsJobService;
import com.game.data.ops.pb.OpsJobKind;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.rollback.RollbackRequest;
import com.game.data.rollback.RollbackService;
import com.game.data.snapshot.ZoneSnapshotService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 批次 7.2b 接口的参数绑定与 HTTP 状态（服务层用替身；业务规则在 *SqlTest 里）。 */
class RollbackAdminControllerTest {

    private final RollbackService rollbacks = mock(RollbackService.class);
    private final ZoneSnapshotService zoneSnapshots = mock(ZoneSnapshotService.class);
    private final OpsJobService jobs = mock(OpsJobService.class);

    private MockMvc mvc(boolean opsEnabled) {
        DataProperties props = new Binder(new MapConfigurationPropertySource(Map.of("xm.data.ops.enabled",
                Boolean.toString(opsEnabled)))).bindOrCreate("xm.data", DataProperties.class);
        return MockMvcBuilders.standaloneSetup(new RollbackAdminController(rollbacks, zoneSnapshots),
                new OpsJobAdminController(jobs, props)).setControllerAdvice(new OpsErrorAdvice()).build();
    }

    @Test
    void 回档_受理回202_dry_run回200_请求体与幂等键原样交给服务() throws Exception {
        when(rollbacks.handle(any(), eq("ops"), eq("k1"))).thenReturn(Map.of("jobId", "9", "replayed", false));
        when(rollbacks.handle(any(), eq("ops"), isNull())).thenReturn(Map.of("dryRun", true));
        String body = "{\"scope\":\"players\",\"players\":[\"1001\"],\"snapshotId\":\"5\",\"sections\":[\"assets\"],"
                + "\"ifOnline\":\"kick\",\"reason\":\"客诉\"}";
        mvc(true).perform(post("/admin/rollbacks").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Idempotency-Key", "k1").header(AdminAuthFilter.OPERATOR_HEADER, "ops"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").value("9"));
        verify(rollbacks).handle(eq(new RollbackRequest.Body("players", List.of("1001"), null, null, "5", null,
                List.of("assets"), "kick", null, null, "客诉", null)), eq("ops"), eq("k1"));
        mvc(true).perform(post("/admin/rollbacks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"players\",\"dryRun\":true}").header(AdminAuthFilter.OPERATOR_HEADER, "ops"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(true));
    }

    @Test
    void 整区维护前快照_202() throws Exception {
        when(zoneSnapshots.submit(any(), eq("ops"), eq("k2"))).thenReturn(Map.of("jobId", "10"));
        mvc(true).perform(post("/admin/zone-snapshots").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zones\":[1],\"reason\":\"维护\"}").header("Idempotency-Key", "k2")
                        .header(AdminAuthFilter.OPERATOR_HEADER, "ops"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").value("10"));
        verify(zoneSnapshots).submit(eq(new ZoneSnapshotService.Body(List.of(1L), null, null, "维护")), eq("ops"),
                eq("k2"));
    }

    @Test
    void 作业查询_状态种类参数_取消要写开关() throws Exception {
        when(jobs.list(OpsJobStatus.OPS_JOB_RUNNING, OpsJobKind.OPS_JOB_ZONE_SNAPSHOT, 5)).thenReturn(List.of(Map.of("a", 1)));
        mvc(false).perform(get("/admin/ops-jobs?status=running&kind=zone-snapshot&limit=5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].a").value(1));
        mvc(false).perform(get("/admin/ops-jobs?status=bogus")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        mvc(false).perform(get("/admin/ops-jobs?limit=0")).andExpect(status().isBadRequest());
        when(jobs.view(-1L)).thenReturn(Map.of("jobId", "18446744073709551615"));
        mvc(false).perform(get("/admin/ops-jobs/18446744073709551615")).andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("18446744073709551615"));
        when(jobs.players(7L, 3L, 10)).thenReturn(List.of());
        mvc(false).perform(get("/admin/ops-jobs/7/players?after=3&limit=10")).andExpect(status().isOk());

        mvc(false).perform(post("/admin/ops-jobs/7/cancel")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ops_disabled"));
        verify(jobs, never()).cancel(org.mockito.ArgumentMatchers.anyLong());
        when(jobs.cancel(7L)).thenReturn(Map.of("cancelRequested", true));
        mvc(true).perform(post("/admin/ops-jobs/7/cancel")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.cancelRequested").value(true));
    }
}
