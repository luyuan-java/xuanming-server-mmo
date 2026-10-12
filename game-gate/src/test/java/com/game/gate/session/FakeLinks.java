package com.game.gate.session;

import com.game.api.proto.NodeLinkFrame;
import com.game.gate.link.SceneLinks;
import java.util.ArrayList;
import java.util.List;

/** 链路发送面替身：记录发往各 scene 节点的帧，返回测试指定的代次。 */
final class FakeLinks implements SceneLinks {

    record Sent(int sceneNodeId, NodeLinkFrame frame) {
    }

    final List<Sent> sent = new ArrayList<>();
    long generation = 11;

    @Override
    public long send(int sceneNodeId, NodeLinkFrame frame) {
        sent.add(new Sent(sceneNodeId, frame));
        return generation;
    }

    Sent last() {
        return sent.get(sent.size() - 1);
    }
}
