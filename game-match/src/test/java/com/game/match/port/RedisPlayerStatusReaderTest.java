package com.game.match.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.discovery.proto.PlayerLocation;
import com.game.discovery.proto.PlayerPresence;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 玩家状态的生产读口（{@link RedisPlayerStatusReader}）：三样读各自的「失败」形态（future 异常完成、等超时、同步抛出、返回空值、位置读的 ERROR 状态）
 * 一律变成 {@code DependencyException}，绝不折成「没有锁 / 不在线 / 没有位置」；正常值原样带回；读的是调用方给的那名玩家。
 */
class RedisPlayerStatusReaderTest {

    private static final long PLAYER = Long.MIN_VALUE + 4301;
    private static final PlayerPresence PRESENCE = PlayerPresence.newBuilder().setPlayerId(PLAYER).setZoneId(2).setGateNodeId(5)
            .setGateInstanceId("gate-x").setSessionId(77).build();
    private static final HolderRead ONLINE = new HolderRead(LocationStatus.ONLINE,
            PlayerLocation.newBuilder().setPlayerId(PLAYER).setZoneId(2).setSceneNodeId(9).build(), null);

    private final List<Long> asked = new ArrayList<>();

    private RedisPlayerStatusReader reader(CompletableFuture<Boolean> lock, CompletableFuture<Optional<PlayerPresence>> presence,
                                           CompletableFuture<HolderRead> holder) {
        return new RedisPlayerStatusReader(
                id -> {
                    asked.add(id);
                    return lock;
                },
                id -> {
                    asked.add(id);
                    return presence;
                },
                id -> {
                    asked.add(id);
                    return holder;
                });
    }

    private static <T> CompletableFuture<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static <T> CompletableFuture<T> failed() {
        return CompletableFuture.failedFuture(new IllegalStateException("Unable to send command! Node source: NodeSource [slot=0]"));
    }

    @Test
    void 正常值原样带回_读的是调用方给的玩家() {
        RedisPlayerStatusReader reader = reader(done(true), done(Optional.of(PRESENCE)), done(ONLINE));
        Deadline d = Deadline.after(1000);

        assertThat(reader.inBattle(PLAYER, d)).isTrue();
        assertThat(reader.presence(PLAYER, d)).contains(PRESENCE);
        assertThat(reader.location(PLAYER, d)).isSameAs(ONLINE);
        assertThat(asked).containsExactly(PLAYER, PLAYER, PLAYER);
    }

    @Test
    void 没有锁_不在线_三种无持有者的位置状态_都是正常结果不是故障() {
        Deadline d = Deadline.after(1000);

        assertThat(reader(done(false), done(Optional.empty()), done(ONLINE)).inBattle(PLAYER, d)).isFalse();
        assertThat(reader(done(false), done(Optional.empty()), done(ONLINE)).presence(PLAYER, d)).isEmpty();
        for (LocationStatus status : List.of(LocationStatus.RECONNECT_LEASE, LocationStatus.LOGGED_OUT, LocationStatus.MISSING)) {
            HolderRead read = reader(done(false), done(Optional.empty()), done(new HolderRead(status, null, null))).location(PLAYER, d);
            assertThat(read.status()).isEqualTo(status);
            assertThat(read.location()).isNull();
        }
    }

    @Test
    void 读战斗锁失败_抛依赖异常_不当成没有锁() {
        assertThatThrownBy(() -> reader(failed(), done(Optional.empty()), done(ONLINE)).inBattle(PLAYER, Deadline.after(1000)))
                .isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("读战斗锁").hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reader(done(null), done(Optional.empty()), done(ONLINE)).inBattle(PLAYER, Deadline.after(1000)))
                .as("空值也是故障").isInstanceOf(Deadline.DependencyException.class);
    }

    @Test
    void 读在线目录失败或条目损坏_抛依赖异常_不当成不在线() {
        assertThatThrownBy(() -> reader(done(false), failed(), done(ONLINE)).presence(PLAYER, Deadline.after(1000)))
                .isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("读在线目录");
        assertThatThrownBy(() -> reader(done(false), done(null), done(ONLINE)).presence(PLAYER, Deadline.after(1000)))
                .isInstanceOf(Deadline.DependencyException.class);
    }

    @Test
    void 位置读的ERROR状态转成异常_带上原因_不当成没有位置() {
        HolderRead error = new HolderRead(LocationStatus.ERROR, null, "在线记录解析失败");

        assertThatThrownBy(() -> reader(done(false), done(Optional.empty()), done(error)).location(PLAYER, Deadline.after(1000)))
                .isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("在线记录解析失败");
        assertThatThrownBy(() -> reader(done(false), done(Optional.empty()), failed()).location(PLAYER, Deadline.after(1000)))
                .isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("读位置记录");
        assertThatThrownBy(() -> reader(done(false), done(Optional.empty()), done(new HolderRead(LocationStatus.ONLINE, null, null)))
                .location(PLAYER, Deadline.after(1000))).as("ONLINE 却没有位置值").isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> reader(done(false), done(Optional.empty()), done(null)).location(PLAYER, Deadline.after(1000)))
                .isInstanceOf(Deadline.DependencyException.class);
    }

    @Test
    void 等过了请求预算_抛依赖异常_在预算附近返回而不是一直等() {
        RedisPlayerStatusReader reader = reader(new CompletableFuture<>(), new CompletableFuture<>(), new CompletableFuture<>());
        long started = System.nanoTime();

        assertThatThrownBy(() -> reader.inBattle(PLAYER, Deadline.after(60))).isInstanceOf(Deadline.DependencyException.class)
                .hasMessageContaining("超过请求预算");
        assertThatThrownBy(() -> reader.presence(PLAYER, Deadline.after(60))).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> reader.location(PLAYER, Deadline.after(60))).isInstanceOf(Deadline.DependencyException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(150L, 3000L);
    }

    @Test
    void 发起读时同步抛出的异常_同样变成依赖异常() {
        RedisPlayerStatusReader reader = new RedisPlayerStatusReader(
                id -> {
                    throw new IllegalStateException("Redisson is shutdown");
                },
                id -> null,
                id -> {
                    throw new IllegalStateException("Redisson is shutdown");
                });

        assertThatThrownBy(() -> reader.inBattle(PLAYER, Deadline.after(1000))).isInstanceOf(Deadline.DependencyException.class)
                .hasRootCauseMessage("Redisson is shutdown");
        assertThatThrownBy(() -> reader.presence(PLAYER, Deadline.after(1000))).as("没有返回 future").isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> reader.location(PLAYER, Deadline.after(1000))).isInstanceOf(Deadline.DependencyException.class);
    }
}
