package com.game.gate.session;

import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerEnterResult;
import com.game.api.proto.PlayerKicked;
import com.game.api.proto.PlayerTransfer;
import com.game.api.proto.ToClient;
import com.game.gate.link.SceneLinkListener;
import com.game.proto.MessageContent;
import com.google.protobuf.InvalidProtocolBufferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 链路事件 → 会话：按会话号找到会话，投递到会话所属 EventLoop 上交给 {@link ClientDispatcher}。
 * 本类在链路 I/O 线程上运行，只读会话表，不碰会话状态。
 *
 * <p>找不到会话（会话已断开并从会话表释放——断线流程很快就 {@code registry.release}）时，普通下行整帧丢弃；
 * 但带着一份「只有这帧知道」的归属的事件——改绑指令（{@code PlayerTransfer}）与没送到 scene 的进场帧——
 * 不能丢：交给 {@link ClientDispatcher} 不经会话直接请 login 带围栏放弃（scene-handoff-spec §5.7、G10）。
 */
public final class SceneEventRouter implements SceneLinkListener {

    private static final Logger log = LoggerFactory.getLogger(SceneEventRouter.class);

    private final SessionRegistry registry;
    private final ClientDispatcher dispatcher;

    public SceneEventRouter(SessionRegistry registry, ClientDispatcher dispatcher) {
        this.registry = registry;
        this.dispatcher = dispatcher;
    }

    @Override
    public void onToClient(int sceneNodeId, long linkGen, ToClient message) {
        MessageContent content;
        try {
            // 先解析一遍再下发：向客户端发损坏的 body 会让 robot 的读循环永久卡死（robot 契约 §3.1）。
            content = MessageContent.parseFrom(message.getMessageContent());
        } catch (InvalidProtocolBufferException e) {
            log.warn("scene 下行不是合法的 MessageContent，整条丢弃 node={} gen={} sessions={}",
                    sceneNodeId, linkGen, message.getSessionIdsCount());
            return;
        }
        for (int sessionId : message.getSessionIdsList()) {
            ClientSession session = registry.get(sessionId);
            if (session != null) {
                session.execute(() -> dispatcher.onToClient(session, sceneNodeId, linkGen, content));
            }
        }
    }

    @Override
    public void onPlayerEnterResult(int sceneNodeId, long linkGen, PlayerEnterResult result) {
        ClientSession session = registry.get(result.getSessionId());
        if (session != null) {
            session.execute(() -> dispatcher.onPlayerEnterResult(session, sceneNodeId, linkGen, result));
        }
    }

    @Override
    public void onPlayerKicked(int sceneNodeId, long linkGen, PlayerKicked kicked) {
        ClientSession session = registry.get(kicked.getSessionId());
        if (session != null) {
            session.execute(() -> dispatcher.onPlayerKicked(session, sceneNodeId, linkGen, kicked));
        }
    }

    @Override
    public void onPlayerTransfer(int sceneNodeId, long linkGen, PlayerTransfer transfer) {
        ClientSession session = registry.get(transfer.getSessionId());
        if (session == null
                || !session.execute(() -> dispatcher.onPlayerTransfer(session, sceneNodeId, linkGen, transfer))) {
            dispatcher.onPlayerTransferWithoutSession(sceneNodeId, linkGen, transfer);
        }
    }

    @Override
    public void onEnterUndeliverable(int sceneNodeId, long linkGen, PlayerEnter enter) {
        ClientSession session = registry.get(enter.getSessionId());
        if (session == null
                || !session.execute(() -> dispatcher.onEnterUndeliverable(session, sceneNodeId, linkGen, enter))) {
            dispatcher.onEnterUndeliverableWithoutSession(sceneNodeId, linkGen, enter);
        }
    }

    @Override
    public void onLinkDown(int sceneNodeId, long linkGen) {
        // 少见事件，全表扫一遍；是否绑定在这条链路上由会话线程自己判断。
        for (ClientSession session : registry.all()) {
            session.execute(() -> dispatcher.onSceneLinkDown(session, sceneNodeId, linkGen));
        }
    }
}
