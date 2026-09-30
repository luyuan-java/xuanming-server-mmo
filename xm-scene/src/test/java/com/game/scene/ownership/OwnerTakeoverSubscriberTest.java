package com.game.scene.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.OwnerTakeover;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RTopic;
import org.redisson.api.listener.MessageListener;

class OwnerTakeoverSubscriberTest {

    private final RTopic topic = mock(RTopic.class);
    private final List<long[]> requests = new ArrayList<>();
    private final OwnerTakeoverSubscriber subscriber =
            new OwnerTakeoverSubscriber(topic, (playerId, epoch) -> requests.add(new long[] {playerId, epoch}));

    @Test
    @SuppressWarnings("unchecked")
    void 订阅后收到接管请求_交给场景逻辑_停止时取消订阅() {
        when(topic.addListener(eq(byte[].class), any(MessageListener.class))).thenReturn(7);
        subscriber.start();
        ArgumentCaptor<MessageListener<byte[]>> listener = ArgumentCaptor.forClass(MessageListener.class);
        verify(topic).addListener(eq(byte[].class), listener.capture());

        listener.getValue().onMessage("xm:owner-takeover",
                OwnerTakeover.newBuilder().setPlayerId(1001).setOwnerEpoch(4).build().toByteArray());

        assertThat(requests).singleElement().satisfies(r -> assertThat(r).containsExactly(1001L, 4L));

        subscriber.stop();
        verify(topic).removeListener(7);
    }

    @Test
    void 非法消息丢弃() {
        subscriber.accept(new byte[] {0x0A, (byte) 0xFF});
        subscriber.accept(OwnerTakeover.newBuilder().setPlayerId(1001).build().toByteArray());
        subscriber.accept(OwnerTakeover.newBuilder().setOwnerEpoch(4).build().toByteArray());

        assertThat(requests).isEmpty();
    }
}
