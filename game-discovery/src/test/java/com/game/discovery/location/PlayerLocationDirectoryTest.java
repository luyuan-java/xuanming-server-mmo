package com.game.discovery.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.game.common.token.GateTokenIssuer;
import com.game.discovery.RedisKeys;
import com.game.discovery.proto.PlayerLocation;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.misc.CompletableFutureWrapper;

/**
 * 待落点写口 {@link PlayerLocationDirectory#awaitPlacementAsync} 的参数契约（批次 5.4，zone-travel-spec §5.9；缺省执行，不连 Redis）：
 * 不合法的参数在发出任何 Redis 命令之前被拒；合法的写发出的是「重连租约」那段脚本、状态 {@code l}、调用方给的 TTL。
 * Redis 客户端是替身，只记下脚本调用的参数。真 Redis 上的读写（乱序收敛、各读者看到什么）见位置记录的集成测试。
 */
class PlayerLocationDirectoryTest {

    private static final long PLAYER = 9_000_000_123L;
    private static final long EPOCH = 7;

    /** 一次脚本调用：键与 ARGV（epoch, seq, state, value, ttl_ms）。 */
    private record Eval(List<Object> keys, List<byte[]> args) {

        String arg(int index) {
            return new String(args.get(index), StandardCharsets.US_ASCII);
        }
    }

    private final RedissonClient redis = mock(RedissonClient.class);
    private final List<Eval> evals = new ArrayList<>();
    /** 脚本的返回值：1 = 写生效，0 = 已有更新的写。 */
    private volatile long scriptReply = 1;

    private PlayerLocationDirectory recording() {
        RScript script = mock(RScript.class);
        when(redis.getScript(any(Codec.class))).thenReturn(script);
        when(script.evalAsync(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any(Object[].class)))
                .thenAnswer(call -> {
                    List<byte[]> args = new ArrayList<>();
                    Object[] raw = call.getArguments();
                    for (int i = 4; i < raw.length; i++) {
                        args.add((byte[]) raw[i]);
                    }
                    evals.add(new Eval(call.getArgument(3), args));
                    return new CompletableFutureWrapper<Object>((Object) Long.valueOf(scriptReply));
                });
        return new PlayerLocationDirectory(redis);
    }

    /** 待落点：目标 zone 2、节点号 0、场景号 0、要落的地图 3、源节点交出前的 epoch。 */
    private static PlayerLocation pending() {
        return PlayerLocation.newBuilder().setPlayerId(PLAYER).setZoneId(2).setSceneConfigId(3).setOwnerEpoch(EPOCH).build();
    }

    @Test
    void 待落点的存活上限与重定向票据的有效期同值() {
        assertThat(PlayerLocationDirectory.AWAIT_PLACEMENT_MAX_TTL).isEqualTo(Duration.ofSeconds(300));
        assertThat(PlayerLocationDirectory.AWAIT_PLACEMENT_MAX_TTL).isEqualTo(GateTokenIssuer.REDIRECT_TICKET_TTL);
    }

    @Test
    void 合法的待落点写成重连租约状态_带调用方给的TTL与续接的序号() throws Exception {
        PlayerLocationDirectory directory = recording();

        Boolean applied = directory.awaitPlacementAsync(pending(), 5, Duration.ofSeconds(287)).toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertThat(applied).isTrue();
        assertThat(evals).hasSize(1);
        Eval eval = evals.get(0);
        assertThat(eval.keys()).containsExactly(RedisKeys.playerLocation(PLAYER));
        assertThat(eval.args()).hasSize(5);
        assertThat(eval.arg(0)).as("e = 源节点交出前持有的 epoch").isEqualTo("7");
        assertThat(eval.arg(1)).as("q = 续接的写序号").isEqualTo("5");
        assertThat(eval.arg(2)).as("s = l：读者眼里是重连租约，不是新状态").isEqualTo("l");
        assertThat(PlayerLocation.parseFrom(eval.args().get(3))).as("v 原样是传入的待落点").isEqualTo(pending());
        assertThat(eval.arg(4)).as("TTL 用调用方给的，不是固定的 30 s 重连租约").isEqualTo("287000");
    }

    @Test
    void 待落点与断线重连租约只差值与TTL_走的是同一段脚本() throws Exception {
        PlayerLocationDirectory directory = recording();
        PlayerLocation here = pending().toBuilder().setZoneId(1).setSceneNodeId(4).setSceneId(900_001).build();

        directory.leaseAsync(here, 5).toCompletableFuture().get(5, TimeUnit.SECONDS);
        directory.awaitPlacementAsync(pending(), 6, Duration.ofSeconds(1)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(evals).hasSize(2);
        assertThat(evals.get(0).arg(2)).isEqualTo("l");
        assertThat(evals.get(1).arg(2)).isEqualTo("l");
        assertThat(evals.get(0).arg(4)).isEqualTo("30000");
        assertThat(evals.get(1).arg(4)).as("下限 1 s 由调用方截，这里原样用").isEqualTo("1000");
    }

    @Test
    void 上下界本身合法_1毫秒与300秒都发得出去() throws Exception {
        PlayerLocationDirectory directory = recording();

        directory.awaitPlacementAsync(pending(), 1, Duration.ofMillis(1)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        directory.awaitPlacementAsync(pending(), 2, PlayerLocationDirectory.AWAIT_PLACEMENT_MAX_TTL).toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertThat(evals).hasSize(2);
        assertThat(evals.get(0).arg(4)).isEqualTo("1");
        assertThat(evals.get(1).arg(4)).isEqualTo("300000");
    }

    @Test
    void 已有更新的写时返回false_不当成失败() throws Exception {
        PlayerLocationDirectory directory = recording();
        scriptReply = 0;

        assertThat(directory.awaitPlacementAsync(pending(), 5, Duration.ofSeconds(60)).toCompletableFuture()
                .get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void 存活时长不为正或超过上限即拒_不发任何Redis命令() {
        PlayerLocationDirectory directory = new PlayerLocationDirectory(redis);

        for (Duration bad : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(999_999),
                PlayerLocationDirectory.AWAIT_PLACEMENT_MAX_TTL.plusMillis(1), Duration.ofHours(1)}) {
            assertThatThrownBy(() -> directory.awaitPlacementAsync(pending(), 5, bad)).as("ttl=%s", bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("存活时长");
        }
        verifyNoInteractions(redis);
    }

    @Test
    void 带着节点号或场景号的记录不是待落点_拒写() {
        PlayerLocationDirectory directory = new PlayerLocationDirectory(redis);

        // 写进去会是一条能活 5 分钟、指着某个实例的「重连租约」：login 会按它回原实例
        assertThatThrownBy(() -> directory.awaitPlacementAsync(pending().toBuilder().setSceneNodeId(4).build(), 5,
                Duration.ofSeconds(60))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("node=4");
        assertThatThrownBy(() -> directory.awaitPlacementAsync(pending().toBuilder().setSceneId(900_001).build(), 5,
                Duration.ofSeconds(60))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scene=900001");
        verifyNoInteractions(redis);
    }

    @Test
    void 缺玩家号或目标zone的待落点_拒写() {
        PlayerLocationDirectory directory = new PlayerLocationDirectory(redis);

        assertThatThrownBy(() -> directory.awaitPlacementAsync(pending().toBuilder().clearPlayerId().build(), 5,
                Duration.ofSeconds(60))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("player=0");
        assertThatThrownBy(() -> directory.awaitPlacementAsync(pending().toBuilder().clearZoneId().build(), 5,
                Duration.ofSeconds(60))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("zone=0");
        verifyNoInteractions(redis);
    }

    @Test
    void 地图为0的待落点可以写_表示落目标zone的默认主世界() throws Exception {
        PlayerLocationDirectory directory = recording();

        assertThat(directory.awaitPlacementAsync(pending().toBuilder().clearSceneConfigId().build(), 5, Duration.ofSeconds(60))
                .toCompletableFuture().get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(PlayerLocation.parseFrom(evals.get(0).args().get(3)).getSceneConfigId()).isZero();
    }
}
