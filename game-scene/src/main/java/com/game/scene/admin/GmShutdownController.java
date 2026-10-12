package com.game.scene.admin;

import com.game.common.token.GmShutdownHandler;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GM 签名停机（同 mmorpg Scene.GmGracefulShutdown）的 HTTP 适配，只在管理端口上：{@code GET /gm/identity} 给签名工具取
 * 本进程身份，{@code POST /gm/graceful-shutdown}（信封与原因全在请求头里，没有请求体）。受理逻辑与鉴权在
 * {@link GmShutdownHandler}（只收本机来的请求，签名绑 {@code 区:节点号:实例}）。
 *
 * <p>受理：回 {@code {"affected_count": 在线玩家数}}（逻辑线程没及时应答时为 -1），应答写出之后再走正常停机——摘目录、断链、
 * 逻辑线程上写回全部玩家、等存储池排空（{@link com.game.scene.SceneNode#stop()}）。
 */
@RestController
public class GmShutdownController {

    public static final String IDENTITY_PATH = "/gm/identity";
    public static final String PATH = "/gm/graceful-shutdown";
    public static final String METHOD = "Scene.GmGracefulShutdown";

    /** 在应答写出之后让进程走正常停机。 */
    @FunctionalInterface
    public interface ProcessExit {
        void exitSoon();
    }

    private final GmShutdownHandler handler;
    private final ProcessExit exit;

    public GmShutdownController(GmShutdownHandler gmShutdownHandler, ProcessExit exit) {
        this.handler = gmShutdownHandler;
        this.exit = exit;
    }

    @GetMapping(IDENTITY_PATH)
    public ResponseEntity<Map<String, Object>> identity(HttpServletRequest request) {
        GmShutdownHandler.Reply reply = handler.identity(request.getRemoteAddr());
        return ResponseEntity.status(reply.status()).body(reply.body());
    }

    @PostMapping(PATH)
    public ResponseEntity<Map<String, Object>> shutdown(HttpServletRequest request) {
        GmShutdownHandler.Reply reply = handler.shutdown(new GmShutdownHandler.Request(request.getRemoteAddr(),
                request.getHeader(GmShutdownHandler.OPERATOR_HEADER), request.getHeader(GmShutdownHandler.TIMESTAMP_HEADER),
                request.getHeader(GmShutdownHandler.NONCE_HEADER), request.getHeader(GmShutdownHandler.SIGNATURE_HEADER),
                request.getHeader(GmShutdownHandler.REASON_HEADER)));
        if (reply.accepted()) {
            exit.exitSoon();
        }
        return ResponseEntity.status(reply.status()).body(reply.body());
    }
}
