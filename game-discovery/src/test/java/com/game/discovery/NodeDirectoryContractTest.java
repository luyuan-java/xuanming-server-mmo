package com.game.discovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.game.api.proto.GateNodeInfo;
import com.game.common.GameNamespace;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.misc.CompletableFutureWrapper;

class NodeDirectoryContractTest {
    private final RedisKeys keys=new RedisKeys(GameNamespace.of("directorytest"));
    @Test void 同步列表保留Redis原异常_异步调用不抛在调用线程也不吞故障() {
        var redis=mock(RedissonClient.class);var script=mock(RScript.class);when(redis.getScript(ByteArrayCodec.INSTANCE)).thenReturn(script);
        var failed=new IllegalStateException("redis unavailable");
        when(script.<Object>evalAsync(eq(RScript.Mode.READ_WRITE),anyString(),eq(RScript.ReturnType.MULTI),anyList(),any()))
                .thenReturn(new CompletableFutureWrapper<>(CompletableFuture.failedFuture(failed)));
        var directory=new NodeDirectory<>(redis,keys,"gate",GateNodeInfo.parser());
        assertThatThrownBy(()->directory.list(1)).isSameAs(failed);
        assertThatThrownBy(()->directory.listAsync(1).join()).isInstanceOf(CompletionException.class).hasCause(failed);
        assertThatThrownBy(()->directory.findAsync(1,7).join()).isInstanceOf(CompletionException.class).hasCause(failed);
    }
    @Test void 退休快照立即返回空且不读取旧目录_全部键同槽() {
        var redis=mock(RedissonClient.class);var script=mock(RScript.class);when(redis.getScript(ByteArrayCodec.INSTANCE)).thenReturn(script);
        when(script.<Object>evalAsync(eq(RScript.Mode.READ_WRITE),anyString(),eq(RScript.ReturnType.MULTI),anyList(),any()))
                .thenAnswer(call->{
                    List<Object> names=call.getArgument(3);assertThat(names).hasSize(6);
                    assertThat(names).allMatch(name->name.toString().contains("{z:4294967295}"));
                    return new CompletableFutureWrapper<Object>(List.of(1L,List.of(),List.of()));
                });
        var directory=new NodeDirectory<>(redis,keys,"gate",GateNodeInfo.parser());
        assertThat(directory.listAsync(-1).join()).isEmpty();assertThat(directory.findAsync(-1,7).join()).isEmpty();
        verify(redis,never()).getMapCache(anyString(),any(org.redisson.client.codec.Codec.class));
    }
}
