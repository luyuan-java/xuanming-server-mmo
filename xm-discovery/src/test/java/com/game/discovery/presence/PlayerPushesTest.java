package com.game.discovery.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.discovery.presence.PlayerPushes.Outcome;
import com.game.discovery.proto.GatePush;
import com.game.discovery.proto.PlayerPresence;
import com.game.discovery.proto.PushTarget;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.misc.CompletableFutureWrapper;

/**
 * 推送的寻址（spectate-spec §2.7 的 Z5、§2.8「同号节点跨 zone 碰撞」；缺省执行，不连 Redis）：gate 节点号按 zone 租约，zone 1 与 zone 2
 * 各有一台 1 号 gate，两台发出的会话号也可以相同（{@code 节点号 << 17 | 序号}）。频道必须是 {@code xm:gate-push:<在线目录的 zone>:<gate 节点号>}，
 * 多人推送的分组键必须含 zone——只按节点号会把 zone 2 玩家的消息发到 zone 1 的同号 gate（那台 gate 按实例过滤把它<b>丢掉而不是送错</b>，
 * 表现是 177 / 143 / 大厅 150 / 组队与帮会的通知静悄悄地没了）。
 *
 * <p>Redis 客户端与在线目录是替身：只登记了两个 zone 的 1 号 gate 的频道，发布到别的频道名会拿到 null 而当场失败。
 * 真 Redis 上的同一组断言见 {@code GatePushCrossZoneIntegrationTest}。
 */
class PlayerPushesTest {

    private static final long A = 1001;
    private static final long B = 1002;
    private static final long C = 1003;
    /** 两台 1 号 gate 发出的第一个会话号。 */
    private static final int SAME_SESSION = (1 << 17) | 1;
    private static final String ZONE1_GATE1 = "xm:gate-push:1:1";
    private static final String ZONE2_GATE1 = "xm:gate-push:2:1";
    private static final BytesValue CONTENT = BytesValue.of(ByteString.copyFromUtf8("evt"));

    private final RedissonClient redis = mock(RedissonClient.class);
    private final PlayerPresenceDirectory directory = mock(PlayerPresenceDirectory.class);
    private final PlayerPushes pushes = new PlayerPushes(redis, directory);
    /** 频道名 → 发布到它上面的消息，按发布顺序。 */
    private final Map<String, List<GatePush>> published = new LinkedHashMap<>();
    /** 频道名 → 订阅者数（发布的返回值）；缺省 1。 */
    private final Map<String, Long> subscribers = new LinkedHashMap<>();

    @BeforeEach
    void channels() {
        channel(ZONE1_GATE1);
        channel(ZONE2_GATE1);
    }

    private void channel(String name) {
        RTopic topic = mock(RTopic.class);
        when(topic.publishAsync(any())).thenAnswer(call -> {
            published.computeIfAbsent(name, k -> new ArrayList<>()).add(GatePush.parseFrom((byte[]) call.getArgument(0)));
            return new CompletableFutureWrapper<>(subscribers.getOrDefault(name, 1L));
        });
        when(redis.getTopic(eq(name), any(Codec.class))).thenReturn(topic);
    }

    private static PlayerPresence at(long playerId, int zoneId, String gateInstance) {
        return PlayerPresence.newBuilder().setPlayerId(playerId).setZoneId(zoneId).setGateNodeId(1).setGateInstanceId(gateInstance)
                .setSessionId(SAME_SESSION).setOwnerEpoch(1).build();
    }

    private void online(PlayerPresence... presences) {
        Map<Long, PlayerPresence> all = new LinkedHashMap<>();
        for (PlayerPresence presence : presences) {
            all.put(presence.getPlayerId(), presence);
            when(directory.findStrictAsync(presence.getPlayerId())).thenReturn(CompletableFuture.completedFuture(Optional.of(presence)));
            when(directory.findAsync(presence.getPlayerId())).thenReturn(CompletableFuture.completedFuture(Optional.of(presence)));
        }
        when(directory.findAllAsync(anyCollection())).thenReturn(CompletableFuture.completedFuture(all));
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static String target(PushTarget target) {
        return Integer.toUnsignedString(target.getSessionId()) + ":" + Long.toUnsignedString(target.getPlayerId());
    }

    @Test
    void 单人推送_频道按在线目录的zone与gate节点号_两个zone的同号gate各是各的频道() throws Exception {
        online(at(A, 1, "gate-z1-inst"), at(B, 2, "gate-z2-inst"));

        assertThat(await(pushes.pushToPlayer(B, CONTENT))).isEqualTo(Outcome.SENT);

        assertThat(published).as("B 挂在 zone 2 的 1 号 gate 上：只发到 zone 2 的频道").containsOnlyKeys(ZONE2_GATE1);
        GatePush toB = published.get(ZONE2_GATE1).get(0);
        assertThat(toB.getGateInstanceId()).isEqualTo("gate-z2-inst");
        assertThat(toB.getMessageContent()).isEqualTo(CONTENT.toByteString());
        assertThat(toB.getTargetsList()).extracting(PlayerPushesTest::target).containsExactly(SAME_SESSION + ":1002");

        assertThat(await(pushes.pushToPlayer(A, CONTENT))).isEqualTo(Outcome.SENT);

        assertThat(published.get(ZONE1_GATE1)).singleElement().satisfies(toA -> {
            assertThat(toA.getGateInstanceId()).isEqualTo("gate-z1-inst");
            assertThat(toA.getTargetsList()).extracting(PlayerPushesTest::target).as("会话号与 B 的相同，频道与实例不同").containsExactly(SAME_SESSION + ":1001");
        });
        assertThat(published.get(ZONE2_GATE1)).as("A 的推送没有落到 zone 2 的频道").hasSize(1);
    }

    @Test
    void 按序推送_整批发到玩家所在zone的频道() throws Exception {
        online(at(B, 2, "gate-z2-inst"));
        BytesValue assigned = BytesValue.of(ByteString.copyFromUtf8("177"));
        BytesValue start = BytesValue.of(ByteString.copyFromUtf8("143"));

        assertThat(await(pushes.pushAllToPlayer(B, List.of(assigned, start)))).isEqualTo(Outcome.SENT);

        assertThat(published).containsOnlyKeys(ZONE2_GATE1);
        GatePush push = published.get(ZONE2_GATE1).get(0);
        assertThat(push.getActionCase()).isEqualTo(GatePush.ActionCase.MESSAGE_BATCH);
        assertThat(push.getMessageBatch().getMessageContentsList()).containsExactly(assigned.toByteString(), start.toByteString());
        assertThat(push.getGateInstanceId()).isEqualTo("gate-z2-inst");
    }

    @Test
    void 多人推送_两个zone的同号gate分成两组_各发各的频道_目标不混在一条里() throws Exception {
        online(at(A, 1, "gate-z1-inst"), at(B, 2, "gate-z2-inst"), at(C, 2, "gate-z2-inst").toBuilder().setSessionId(SAME_SESSION + 1).build());

        Map<Long, Outcome> outcomes = await(pushes.pushToPlayers(List.of(A, B, C), CONTENT));

        assertThat(outcomes).containsEntry(A, Outcome.SENT).containsEntry(B, Outcome.SENT).containsEntry(C, Outcome.SENT).hasSize(3);
        assertThat(published).containsOnlyKeys(ZONE1_GATE1, ZONE2_GATE1);
        assertThat(published.get(ZONE1_GATE1)).as("zone 1 的 1 号 gate：一条，只带 A").singleElement().satisfies(push -> {
            assertThat(push.getGateInstanceId()).isEqualTo("gate-z1-inst");
            assertThat(push.getTargetsList()).extracting(PlayerPushesTest::target).containsExactly(SAME_SESSION + ":1001");
        });
        assertThat(published.get(ZONE2_GATE1)).as("zone 2 的 1 号 gate：一条，带 B 与 C").singleElement().satisfies(push -> {
            assertThat(push.getGateInstanceId()).isEqualTo("gate-z2-inst");
            assertThat(push.getTargetsList()).extracting(PlayerPushesTest::target)
                    .containsExactlyInAnyOrder(SAME_SESSION + ":1002", (SAME_SESSION + 1) + ":1003");
        });
    }

    @Test
    void zone2的1号gate没有订阅者_只有那边的玩家是GATE_UNREACHABLE_zone1的同号gate照常送达() throws Exception {
        online(at(A, 1, "gate-z1-inst"), at(B, 2, "gate-z2-inst"));
        subscribers.put(ZONE2_GATE1, 0L);

        Map<Long, Outcome> outcomes = await(pushes.pushToPlayers(List.of(A, B), CONTENT));

        assertThat(outcomes).containsEntry(A, Outcome.SENT).containsEntry(B, Outcome.GATE_UNREACHABLE);
    }

    @Test
    void 踢下线同样按在线目录的zone找频道() throws Exception {
        online(at(A, 1, "gate-z1-inst"), at(B, 2, "gate-z2-inst"));

        assertThat(await(pushes.kick(B, 2017))).isEqualTo(Outcome.SENT);

        assertThat(published).containsOnlyKeys(ZONE2_GATE1);
        GatePush kick = published.get(ZONE2_GATE1).get(0);
        assertThat(kick.getKickTipId()).isEqualTo(2017);
        assertThat(kick.getGateInstanceId()).isEqualTo("gate-z2-inst");
        assertThat(kick.getTargetsList()).extracting(PlayerPushesTest::target).containsExactly(SAME_SESSION + ":1002");
    }

    @Test
    void 不在线的玩家不发布到任何频道() throws Exception {
        when(directory.findStrictAsync(B)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        assertThat(await(pushes.pushToPlayer(B, CONTENT))).isEqualTo(Outcome.OFFLINE);

        assertThat(published).isEmpty();
    }
}
