package com.game.data.admin;

import com.game.data.DataProperties;
import com.game.data.ops.OpsException;
import com.game.data.ops.OpsRequests;
import com.game.data.recall.RecallExecutor;
import com.game.data.recall.RecallPlanner;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维：批量回收（data-ops-spec §5；对应 108 BatchRecallItems，L958 批次 7.2c）。
 * {@code dryRun=true}（缺省）输出预览与 {@code planDigest}；{@code dryRun=false} 需携带 {@code planDigest} 并经
 * {@link RecallExecutor} 执行离线围栏扣减与 {@code recall_source} 去重。鉴权见 {@link AdminAuthFilter}。
 */
@RestController
public class RecallAdminController {

    public static final String PATH = "/admin/recalls";

    /**
     * 请求体（§5.2）。{@code dryRun} 缺省 true（缺了开关绝不当执行）；{@code planDigest} 在 {@code dryRun=false} 时必填。
     */
    public record RecallBody(List<String> players, String kind, Long currencyType, Long itemConfigId, Long sinceMs,
                             Long untilMs, List<Integer> reasons, String ifOnline, String shortfall, String reason,
                             Boolean dryRun, String planDigest) {
        public RecallBody(List<String> players, String kind, Long currencyType, Long itemConfigId, Long sinceMs,
                          Long untilMs, List<Integer> reasons, String ifOnline, String shortfall, String reason,
                          Boolean dryRun) {
            this(players, kind, currencyType, itemConfigId, sinceMs, untilMs, reasons, ifOnline, shortfall, reason,
                    dryRun, null);
        }
    }

    private final RecallPlanner planner;
    private final RecallExecutor executor;
    private final DataProperties props;
    private final Clock clock;

    @Autowired
    public RecallAdminController(RecallPlanner planner, ObjectProvider<RecallExecutor> executor, DataProperties props,
                                 Clock clock) {
        this(planner, executor == null ? null : executor.getIfAvailable(), props, clock);
    }

    public RecallAdminController(RecallPlanner planner, DataProperties props, Clock clock) {
        this(planner, (RecallExecutor) null, props, clock);
    }

    public RecallAdminController(RecallPlanner planner, RecallExecutor executor, DataProperties props, Clock clock) {
        this.planner = planner;
        this.executor = executor;
        this.props = props;
        this.clock = clock;
    }

    @PostMapping(PATH)
    public Map<String, Object> recall(@RequestBody(required = false) RecallBody body, HttpServletRequest request) {
        if (body == null) {
            throw OpsException.badRequest("缺少请求体");
        }
        if (body.ifOnline() != null && !List.of("reject", "kick").contains(body.ifOnline())) {
            throw OpsException.badRequest("ifOnline 只能是 reject / kick");
        }
        if (body.shortfall() != null && !List.of("report", "debt").contains(body.shortfall())) {
            throw OpsException.badRequest("shortfall 只能是 report / debt");
        }
        String reason = body.reason() == null ? null : OpsRequests.text("reason", body.reason());
        List<Long> players = new ArrayList<>();
        if (body.players() != null) {
            for (String p : body.players()) {
                players.add(OpsRequests.u64("players", p));
            }
        }
        RecallPlanner.Plan plan = RecallPlanner.plan(players, body.kind(), body.currencyType(), body.itemConfigId(),
                body.sinceMs(), body.untilMs(), body.reasons(), reason, clock.millis(),
                props.retention().transactionLog(), props.ops().maxWindow());
        String operator = AdminAuthFilter.operator(request.getHeader(AdminAuthFilter.OPERATOR_HEADER));
        if (body.dryRun() != null && !body.dryRun()) {
            if (executor == null) {
                throw new OpsException(HttpStatus.NOT_IMPLEMENTED, OpsException.NOT_IMPLEMENTED,
                        "当前实例未装配批量回收执行器，只提供 dryRun=true");
            }
            return executor.execute(plan, body.planDigest(), body.shortfall(), operator);
        }
        return planner.dryRun(plan, operator);
    }
}
