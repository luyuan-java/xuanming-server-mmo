package com.game.battle.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.battle.metrics.BattleMetrics;
import com.game.battle.protocol.BattleFrames;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.presence.PlayerPushes;
import com.game.discovery.proto.GatePush;
import com.game.discovery.proto.PlayerPresence;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleStartS2C;
import com.game.proto.MessageContent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.misc.CompletableFutureWrapper;

/** 大厅公告经 gate 回落：一组公告一次交给 {@code pushAllToPlayer}（177 → 143 保序，battle-node-spec §7.7），结局只计数。 */
class PresenceLobbyAnnouncerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BattleMetrics metrics = new BattleMetrics(registry);
    private final PlayerPushes pushes = mock(PlayerPushes.class);
    private final PresenceLobbyAnnouncer announcer = new PresenceLobbyAnnouncer(pushes, metrics);

    private static final MessageContent ASSIGNED = BattleFrames.push(177, BattleAssignedS2C.newBuilder().setBattleId(5).build());
    private static final MessageContent START = BattleFrames.push(143, BattleStartS2C.newBuilder().setBattleId(5).build());

    private double outcome(String name) {
        return registry.get("xm.battle.lobby.push.outcomes").tag("outcome", name).counter().count();
    }

    @Test
    void 两条公告一次交出_顺序不变_送达计sent() {
        when(pushes.pushAllToPlayer(9001L, List.of(ASSIGNED, START)))
                .thenReturn(CompletableFuture.completedFuture(PlayerPushes.Outcome.SENT));

        announcer.announce(9001L, List.of(ASSIGNED, START));

        verify(pushes).pushAllToPlayer(9001L, List.of(ASSIGNED, START));
        assertThat(outcome("sent")).isEqualTo(1);
    }

    @Test
    void 不在线与gate不可达只计数() {
        when(pushes.pushAllToPlayer(anyLong(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(PlayerPushes.Outcome.OFFLINE))
                .thenReturn(CompletableFuture.completedFuture(PlayerPushes.Outcome.GATE_UNREACHABLE));

        announcer.announce(1L, List.of(ASSIGNED));
        announcer.announce(2L, List.of(ASSIGNED));

        assertThat(outcome("offline")).isEqualTo(1);
        assertThat(outcome("gate_unreachable")).isEqualTo(1);
    }

    @Test
    void Redis故障与发起时抛异常都只计error_不往外抛() {
        when(pushes.pushAllToPlayer(anyLong(), anyList()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("测试：Redis 故障")))
                .thenThrow(new IllegalStateException("测试：Redisson 已关闭"));

        announcer.announce(1L, List.of(ASSIGNED));
        announcer.announce(2L, List.of(ASSIGNED));

        assertThat(outcome("error")).isEqualTo(2);
    }

    @Test
    void 空列表什么也不做() {
        announcer.announce(1L, List.of());
        verify(pushes, never()).pushAllToPlayer(anyLong(), anyList());
    }

    // ================================================================ 同号 gate 跨 zone（spectate-spec §2.7 的 Z5、§2.8）

    /**
     * 两个 zone 各有一台 1 号 gate（节点号按 zone 租约），两台 gate 发出的会话号也可以相同。公告经<b>真的</b> {@link PlayerPushes} 发
     * （只把 Redis 客户端与在线目录换成替身）：在线目录说玩家挂在 zone 2 的 1 号 gate 上，消息就发布到 {@code xm:gate-push:2:1}。
     * 只按 gate 节点号拼频道会发到 {@code xm:gate-push:1:1}——zone 1 的那台 gate 按实例过滤把它<b>丢掉而不是送错</b>，177 / 143 就这么没了。
     */
    @Test
    void 玩家挂在zone2的1号gate上_公告发布到zone2的频道_带那台gate的实例号_不是zone1的同号频道() throws Exception {
        PlayerPresence inZone2 = PlayerPresence.newBuilder().setPlayerId(9002).setZoneId(2).setGateNodeId(1).setGateInstanceId("gate-z2-inst")
                .setSessionId((1 << 17) | 1).setOwnerEpoch(3).build();
        PlayerPresenceDirectory directory = mock(PlayerPresenceDirectory.class);
        when(directory.findStrictAsync(9002L)).thenReturn(CompletableFuture.completedFuture(Optional.of(inZone2)));
        RedissonClient redis = mock(RedissonClient.class);
        RTopic topic = mock(RTopic.class);
        when(redis.getTopic(anyString(), any(Codec.class))).thenReturn(topic);
        when(topic.publishAsync(any())).thenReturn(new CompletableFutureWrapper<>(1L));
        PresenceLobbyAnnouncer real = new PresenceLobbyAnnouncer(new PlayerPushes(redis, directory), metrics);

        real.announce(9002L, List.of(ASSIGNED, START));

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        verify(redis).getTopic(channel.capture(), any(Codec.class));
        assertThat(channel.getValue()).as("频道 = (在线目录的 zone, gate 节点号)").isEqualTo("xm:gate-push:2:1").isNotEqualTo("xm:gate-push:1:1");
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(topic).publishAsync(published.capture());
        GatePush push = GatePush.parseFrom((byte[]) published.getValue());
        assertThat(push.getGateInstanceId()).as("gate 按它过滤：同号的别的进程收到也会丢").isEqualTo("gate-z2-inst");
        assertThat(push.getTargetsList()).singleElement().satisfies(target -> {
            assertThat(target.getSessionId()).isEqualTo((1 << 17) | 1);
            assertThat(target.getPlayerId()).isEqualTo(9002L);
        });
        assertThat(push.getMessageBatch().getMessageContentsList()).as("177 → 143 保序、一次发布")
                .containsExactly(ASSIGNED.toByteString(), START.toByteString());
        assertThat(outcome("sent")).isEqualTo(1);
    }
}
