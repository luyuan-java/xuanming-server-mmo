package com.game.data.admin;

import com.game.data.DataProperties;
import com.game.data.ops.OpsException;
import com.game.data.ops.OpsJobService;
import com.game.data.ops.OpsRequests;
import com.game.data.ops.pb.OpsJobKind;
import com.game.data.ops.pb.OpsJobStatus;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维作业的查询与取消（data-ops-spec §7.4）：{@code GET /admin/ops-jobs?status=&kind=&limit=}、{@code GET /admin/ops-jobs/{id}}（含事件）、
 * {@code GET /admin/ops-jobs/{id}/players?after=&limit=}、{@code POST /admin/ops-jobs/{id}/cancel}（需要写开关；只在第一笔写之前有效）。
 */
@RestController
public class OpsJobAdminController {

    public static final String PATH = "/admin/ops-jobs";
    static final int MAX_LIMIT = 1000;

    private final OpsJobService jobs;
    private final DataProperties props;

    public OpsJobAdminController(OpsJobService jobs, DataProperties props) {
        this.jobs = jobs;
        this.props = props;
    }

    @GetMapping(PATH)
    public List<Map<String, Object>> list(@RequestParam(name = "status", required = false) String status,
                                          @RequestParam(name = "kind", required = false) String kind,
                                          @RequestParam(name = "limit", defaultValue = "50") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw OpsException.badRequest("limit 取 1–" + MAX_LIMIT);
        }
        return jobs.list(status == null ? null : parseStatus(status), kind == null ? null : parseKind(kind), limit);
    }

    @GetMapping(PATH + "/{jobId}")
    public Map<String, Object> view(@PathVariable("jobId") String jobId) {
        return jobs.view(OpsRequests.u64("jobId", jobId));
    }

    @GetMapping(PATH + "/{jobId}/players")
    public List<Map<String, Object>> players(@PathVariable("jobId") String jobId,
                                             @RequestParam(name = "after", required = false) String after,
                                             @RequestParam(name = "limit", defaultValue = "200") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw OpsException.badRequest("limit 取 1–" + MAX_LIMIT);
        }
        return jobs.players(OpsRequests.u64("jobId", jobId), after == null ? 0 : OpsRequests.u64("after", after), limit);
    }

    @PostMapping(PATH + "/{jobId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable("jobId") String jobId) {
        if (!props.ops().enabled()) {
            throw new OpsException(HttpStatus.SERVICE_UNAVAILABLE, OpsException.OPS_DISABLED,
                    "改玩家数据的运维写操作未开启（xm.data.ops.enabled=false）");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(jobs.cancel(OpsRequests.u64("jobId", jobId)));
    }

    static OpsJobStatus parseStatus(String raw) {
        String name = "OPS_JOB_" + raw.trim().toUpperCase(Locale.ROOT);
        try {
            OpsJobStatus s = OpsJobStatus.valueOf(name);
            if (s == OpsJobStatus.UNRECOGNIZED) {
                throw new IllegalArgumentException();
            }
            return s;
        } catch (IllegalArgumentException e) {
            throw OpsException.badRequest("未知的作业状态：" + raw);
        }
    }

    static OpsJobKind parseKind(String raw) {
        String name = "OPS_JOB_" + raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            OpsJobKind k = OpsJobKind.valueOf(name);
            if (k == OpsJobKind.UNRECOGNIZED) {
                throw new IllegalArgumentException();
            }
            return k;
        } catch (IllegalArgumentException e) {
            throw OpsException.badRequest("未知的作业种类：" + raw);
        }
    }
}
