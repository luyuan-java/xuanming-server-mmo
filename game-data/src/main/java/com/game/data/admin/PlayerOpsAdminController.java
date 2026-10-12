package com.game.data.admin;

import com.game.data.ops.OpsRequests;
import com.game.data.snapshot.SnapshotDiffService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维：玩家维度的接口（data-ops-spec §3.6）。批次 7.2a 只有快照差异（98 / 116 的 Java 对应）；欠款（7.2c）以后也挂在这个前缀下。
 * 鉴权见 {@link AdminAuthFilter}。
 */
@RestController
public class PlayerOpsAdminController {

    public static final String PATH = "/admin/players";

    private final SnapshotDiffService diff;

    public PlayerOpsAdminController(SnapshotDiffService diff) {
        this.diff = diff;
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
}
