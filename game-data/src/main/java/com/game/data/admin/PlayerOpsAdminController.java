package com.game.data.admin;

import com.game.data.ops.OpsException;
import com.game.data.ops.OpsRequests;
import com.game.data.recall.RecallExecutor;
import com.game.data.snapshot.SnapshotDiffService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维：玩家维度的接口（data-ops-spec §3.6，L958 孤儿角色摘除）。鉴权见 {@link AdminAuthFilter}。
 */
@RestController
public class PlayerOpsAdminController {

    public static final String PATH = "/admin/players";

    public record DetachOrphanBody(String reason) {}

    private final SnapshotDiffService diff;
    private final RecallExecutor recallExecutor;

    @Autowired
    public PlayerOpsAdminController(SnapshotDiffService diff, ObjectProvider<RecallExecutor> recallExecutor) {
        this(diff, recallExecutor == null ? null : recallExecutor.getIfAvailable());
    }

    public PlayerOpsAdminController(SnapshotDiffService diff) {
        this(diff, (RecallExecutor) null);
    }

    public PlayerOpsAdminController(SnapshotDiffService diff, RecallExecutor recallExecutor) {
        this.diff = diff;
        this.recallExecutor = recallExecutor;
    }

    /**
     * 某玩家的一份快照与已落盘当前状态的结构化差异。{@code snapshot}（快照号）与 {@code atMs}（取该时刻之前最近的一份，
     * 不含安全快照）二选一。
     */
    @GetMapping(PATH + "/{player}/snapshot-diff")
    public Map<String, Object> snapshotDiff(@PathVariable("player") String player,
                                            @RequestParam(name = "snapshot", required = false) String snapshot,
                                            @RequestParam(name = "atMs", required = false) Long atMs) {
        return diff.diff(OpsRequests.u64("player", player),
                snapshot == null ? null : OpsRequests.u64("snapshot", snapshot), atMs);
    }

    /** 摘除无写权行/无存档行的离线孤儿角色（需运维口令与操作人头，经 {@link AdminAuthFilter} 鉴权）。 */
    @PostMapping(PATH + "/{player}/detach-orphan")
    public Map<String, Object> detachOrphan(@PathVariable("player") String player,
                                            @RequestBody(required = false) DetachOrphanBody body,
                                            HttpServletRequest request) {
        if (recallExecutor == null) {
            throw new OpsException(HttpStatus.NOT_IMPLEMENTED, OpsException.NOT_IMPLEMENTED,
                    "当前实例未装配孤儿角色摘除执行器");
        }
        String reason = body == null || body.reason() == null ? null : OpsRequests.text("reason", body.reason());
        String operator = AdminAuthFilter.operator(request.getHeader(AdminAuthFilter.OPERATOR_HEADER));
        return recallExecutor.detachOrphanPlayer(OpsRequests.u64("player", player), operator, reason);
    }
}
