package com.game.match.admin;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.match.spectate.SpectateFeaturedService;
import com.game.proto.match.SetSpectateFeaturedRequest;
import com.google.protobuf.InvalidProtocolBufferException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 赛事局标记的运营入口（mmorpg battle-spectate-tiered-fanout.md §15.6；C++ / Go 版是内部 gRPC {@code SetSpectateFeatured}，Java 版运维口统一走管理端口：
 * 过滤器 {@link MatchAdminAuthFilter} 已校验令牌与操作人，操作留审计日志）。请求与应答都是契约 protobuf，prod 也开放（不在 dev 前缀之下）。
 */
@RestController
public final class SpectateFeaturedController {

    public static final String PATH = "/admin/match/spectate/featured";
    private static final String PROTOBUF = "application/x-protobuf";

    private final SpectateFeaturedService service;

    public SpectateFeaturedController(SpectateFeaturedService service) {
        this.service = service;
    }

    @PostMapping(value = PATH, consumes = PROTOBUF, produces = PROTOBUF)
    public ResponseEntity<byte[]> set(@RequestBody byte[] body) {
        SetSpectateFeaturedRequest request;
        try {
            request = SetSpectateFeaturedRequest.parseFrom(body);
        } catch (InvalidProtocolBufferException e) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(service.set(request, Deadline.after(MatchBudgets.DEFAULT_REQUEST_BUDGET_MS)).toByteArray());
    }
}
