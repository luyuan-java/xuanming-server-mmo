package com.game.discovery.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.game.common.GameNamespace;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

class WorldNodeLeaseEvidenceTest {
    @Test @SuppressWarnings("unchecked")
    void 只读原节点租约键_空值也是持有_异常保留为异常() {
        var keys = new RedisKeys(GameNamespace.of("leasecheck"));
        var redis = mock(RedissonClient.class);
        RScript script=mock(RScript.class);
        when(redis.getScript(StringCodec.INSTANCE)).thenReturn(script);
        var store = new RedissonWorldChannelStore(redis, keys);
        when(script.eval(RScript.Mode.READ_WRITE,"return redis.call('GET',KEYS[1])",RScript.ReturnType.VALUE,
                java.util.List.of(keys.nodeId(NodeTypes.SCENE,3,7)))).thenReturn(null, "original-instance", "replacement-instance", "");
        assertThat(store.sceneNodeLeaseHeld(3, 7)).isFalse();
        assertThat(store.sceneNodeLeaseHeld(3, 7)).isTrue();
        assertThat(store.sceneNodeLeaseHeld(3, 7)).isTrue();
        assertThat(store.sceneNodeLeaseHeld(3, 7)).isTrue();
        when(script.eval(RScript.Mode.READ_WRITE,"return redis.call('GET',KEYS[1])",RScript.ReturnType.VALUE,
                java.util.List.of(keys.nodeId(NodeTypes.SCENE,3,7)))).thenThrow(new IllegalStateException("unreachable"));
        assertThatThrownBy(() -> store.sceneNodeLeaseHeld(3, 7)).hasMessage("unreachable");
    }
}
