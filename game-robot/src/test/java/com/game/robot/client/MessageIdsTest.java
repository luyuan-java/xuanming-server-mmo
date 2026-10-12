package com.game.robot.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MessageIdsTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();

    @Test
    void 探针用到的消息号都能按服务名加方法名解析_且互不相同() {
        MessageIds ids = MessageIds.resolve(REGISTRY);
        List<Integer> all = List.of(ids.login(), ids.createPlayer(), ids.enterGame(), ids.sendTip(),
                ids.notifyEnterScene(), ids.notifyActorCreate(), ids.notifyActorListCreate(), ids.notifyActorDestroy(),
                ids.notifyActorListDestroy(), ids.listSkills(), ids.syncBaseAttribute(), ids.moveStart(),
                ids.moveSync(), ids.moveStop(), ids.notifyMoveAck());
        Set<Integer> distinct = new HashSet<>(all);
        assertThat(distinct).hasSize(all.size());
        assertThat(all).allSatisfy(id -> assertThat(id).isPositive());
    }

    @Test
    void 三条移动上行是客户端可发的玩家服务_应答类型是_Empty() {
        MessageIds ids = MessageIds.resolve(REGISTRY);
        for (int id : List.of(ids.moveStart(), ids.moveSync(), ids.moveStop())) {
            MessageMethod method = REGISTRY.byId(id).orElseThrow();
            assertThat(method.clientService()).isTrue();
            assertThat(method.playerService()).isTrue();
            assertThat(method.responsePrototype().getDescriptorForType().getName()).isEqualTo("Empty");
            assertThat(ids.isMoveInput(id)).isTrue();
        }
        assertThat(ids.isMoveInput(ids.notifyMoveAck())).isFalse();
        assertThat(ids.isMoveInput(ids.syncBaseAttribute())).isFalse();
    }
}
