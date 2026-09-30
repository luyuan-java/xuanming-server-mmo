package com.game.gateway.serverlist;

import com.game.gateway.zone.ZoneCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/server-list}：区服列表。robot 只在 {@code zone_id: 0} 时调用，只读 {@code zones[].zone_id}
 * 与 {@code zones[].recommended}（取第一个推荐区，没有就取第一个）。
 *
 * <p>本批状态只来自配置（运维手工状态），不叠加节点健康探测：区服 OPEN 但一台 gate 都没有时这里仍显示 OPEN，
 * 真正能否进由 assign-gate 实时选 gate 时 fail-closed 判定。
 */
@RestController
@RequestMapping("/api")
public class ServerListController {

    private final ServerListResponse response;

    public ServerListController(ZoneCatalog zones) {
        // 区服目录启动后不变，应答预先算好。
        this.response = new ServerListResponse(zones.all().stream().map(ZoneInfo::of).toList());
    }

    @GetMapping("/server-list")
    public ServerListResponse serverList() {
        return response;
    }
}
