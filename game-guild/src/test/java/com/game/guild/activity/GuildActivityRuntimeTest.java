package com.game.guild.activity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.game.common.GameNamespace;
import com.game.common.KafkaNames;
import com.game.discovery.RedisKeys;
import com.game.guild.store.GuildActivityStore;
import com.game.proto.BattleActivityContext;
import com.game.proto.contracts.kafka.BattleResultEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.misc.CompletableFutureWrapper;
import org.springframework.test.util.ReflectionTestUtils;

/** 恢复单条失败不阻断其他原件；未证明的战报不释放ACTIVE、不确认Redis记录。 */
class GuildActivityRuntimeTest {
    private final GuildActivityStore store = mock(GuildActivityStore.class);
    private final RedissonClient redis = mock(RedissonClient.class);
    private final GameNamespace namespace = GameNamespace.of("game");
    private final RedisKeys keys = new RedisKeys(namespace);
    private final GuildActivityRuntime runtime = new GuildActivityRuntime(store, redis, keys, new KafkaNames(namespace),
            "127.0.0.1:1", 1, false, true, new SimpleMeterRegistry(), (guild, actor, players) -> {});

    private BattleResultEvent event(long id) {
        return BattleResultEvent.newBuilder().setBattleId(id)
                .setActivityContext(BattleActivityContext.newBuilder().setKindValue(1).setGuildId(11)).build();
    }
    private RBucket<byte[]> bucket(long id, byte[] raw) {
        @SuppressWarnings("unchecked") RBucket<byte[]> bucket = mock(RBucket.class);
        when(redis.<byte[]>getBucket(keys.battleActivityResult(id), ByteArrayCodec.INSTANCE)).thenReturn(bucket);
        when(bucket.getAsync()).thenReturn(new CompletableFutureWrapper<>(raw));
        when(bucket.compareAndSetAsync(any(byte[].class), isNull())).thenReturn(new CompletableFutureWrapper<>(true));
        return bucket;
    }
    private void pending(long... ids) throws Exception {
        when(store.unsettled(any(), anyLong())).thenReturn(java.util.Arrays.stream(ids)
                .mapToObj(id -> new GuildActivityStore.Battle(id, 11, new byte[0], 1)).toList());
        ReflectionTestUtils.setField(runtime, "running", true);
    }

    @Test void 未证明战报不调用隔离也不销账() throws Exception {
        var event = event(1);
        doThrow(new GuildActivityStore.UnprovenResult("残缺终局")).when(store).settle(eq(event), anyLong(), any());
        assertThrows(GuildActivityStore.UnprovenResult.class, () -> runtime.settle(event));
        verify(store, never()).poisonBattle(anyLong(), anyString(), anyInt(), anyLong(), any(), anyLong(), any());
        verifyNoInteractions(redis);
    }

    @Test void 真正终局的确定性失败隔离提交后才销账() throws Exception {
        var event = event(1);
        var bucket = bucket(1, event.toByteArray());
        doThrow(new GuildActivityStore.InvalidResult("已验证但奖励配置错误")).when(store).settle(eq(event), anyLong(), any());
        runtime.settle(event);
        var order = inOrder(store, bucket);
        order.verify(store).settle(eq(event), anyLong(), any());
        order.verify(store).poisonBattle(eq(1L), anyString(), anyInt(), anyLong(), aryEq(event.toByteArray()), anyLong(), any());
        order.verify(bucket).compareAndSetAsync(aryEq(event.toByteArray()), isNull());
        verify(store, never()).members(anyLong(), any());
    }

    @Test void 隔离写入失败保留Redis原件() throws Exception {
        var event = event(1);
        doThrow(new GuildActivityStore.InvalidResult("已验证但配置错误")).when(store).settle(eq(event), anyLong(), any());
        doThrow(new SQLException("隔离事务失败")).when(store).poisonBattle(anyLong(), anyString(), anyInt(), anyLong(), any(), anyLong(), any());
        assertThrows(SQLException.class, () -> runtime.settle(event));
        verifyNoInteractions(redis);
    }

    @Test void 首条Redis失败仍推进水位并结算同批下一条() throws Exception {
        pending(1, 2);
        var first = bucket(1, null);
        when(first.getAsync()).thenReturn(new CompletableFutureWrapper<>(CompletableFuture.<byte[]>failedFuture(new SQLException("读取失败"))));
        var secondEvent = event(2);
        var second = bucket(2, secondEvent.toByteArray());
        runtime.tick();
        var order = inOrder(store, first, second);
        order.verify(store).deferBattleInspection(eq(1L), anyLong(), any());
        order.verify(first).getAsync();
        order.verify(store).deferBattleInspection(eq(2L), anyLong(), any());
        order.verify(second).getAsync();
        order.verify(store).settle(eq(secondEvent), anyLong(), any());
        verify(store, never()).expireBattle(eq(1L), anyLong(), any());
    }

    @Test void 坏protobuf只审计不销账_不阻断下一条() throws Exception {
        pending(1, 2);
        byte[] malformed = {(byte) 0x80};
        var first = bucket(1, malformed);
        var second = event(2);
        bucket(2, second.toByteArray());
        runtime.tick();
        verify(store).poison(eq("redis-raw-activity"), eq(0), eq(1L), aryEq(malformed), anyLong(), any());
        verify(first, never()).compareAndSetAsync(any(), any());
        verify(store, never()).poisonBattle(eq(1L), anyString(), anyInt(), anyLong(), any(), anyLong(), any());
        verify(store).settle(eq(second), anyLong(), any());
    }

    @Test void 首条未证明战报和排序写入故障均不阻断下一条() throws Exception {
        pending(1, 2, 3);
        doThrow(new SQLException("水位写入失败")).when(store).deferBattleInspection(eq(1L), anyLong(), any());
        var incomplete = event(2);
        var second = bucket(2, incomplete.toByteArray());
        doThrow(new GuildActivityStore.UnprovenResult("终局缺字段")).when(store).settle(eq(incomplete), anyLong(), any());
        var valid = event(3);
        bucket(3, valid.toByteArray());
        runtime.tick();
        verify(store).deferBattleInspection(eq(2L), anyLong(), any());
        verify(second, never()).compareAndSetAsync(any(), any());
        verify(store).settle(eq(valid), anyLong(), any());
    }

    @Test void 开战恢复与退避同时失败不阻断下一份原件() throws Exception {
        pending();
        when(store.launches(any(), anyLong())).thenReturn(List.of(1L, 2L));
        doThrow(new SQLException("原件暂不可读")).when(store).leaseLaunch(eq(1L), anyLong(), any());
        doThrow(new SQLException("退避暂不可写")).when(store).deferLaunch(eq(1L), anyLong(), any());
        runtime.tick();
        verify(store).leaseLaunch(eq(2L), anyLong(), any());
    }
}
