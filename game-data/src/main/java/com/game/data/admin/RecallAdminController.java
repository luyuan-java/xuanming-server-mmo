package com.game.data.admin;

import com.game.data.DataProperties;
import com.game.data.ops.OpsException;
import com.game.data.ops.OpsRequests;
import com.game.data.recall.RecallPlanner;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维：批量回收（data-ops-spec §5；对应 108 BatchRecallItems）。批次 7.2a 只提供 dry-run（基线在生产上唯一可用的面）：
 * {@code dryRun=false} 回 501 {@code not_implemented}——真正执行（离线编辑、recall_source 去重、落库完整性闸）随 7.2c。
 * dry-run 只读，不需要 Idempotency-Key。鉴权见 {@link AdminAuthFilter}。
 */
@RestController
public class RecallAdminController {

    public static final String PATH = "/admin/recalls";

    /**
     * 请求体（§5.2）。{@code ifOnline} / {@code shortfall} 是执行参数，dry-run 只校验取值；{@code dryRun} 缺省 true
     * （缺了开关绝不当执行）。
     */
    public record RecallBody(List<String> players, String kind, Long currencyType, Long itemConfigId, Long sinceMs,
                             Long untilMs, List<Integer> reasons, String ifOnline, String shortfall, String reason,
                             Boolean dryRun) {
    }

    private final RecallPlanner planner;
    private final DataProperties props;
    private final Clock clock;

    public RecallAdminController(RecallPlanner planner, DataProperties props, Clock clock) {
        this.planner = planner;
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
        if (body.dryRun() != null && !body.dryRun()) {
            throw new OpsException(HttpStatus.NOT_IMPLEMENTED, OpsException.NOT_IMPLEMENTED,
                    "批量回收的执行随批次 7.2c（离线编辑、去重、落库完整性闸）；现在只提供 dryRun=true");
        }
        String operator = AdminAuthFilter.operator(request.getHeader(AdminAuthFilter.OPERATOR_HEADER));
        return planner.dryRun(plan, operator);
    }
}
