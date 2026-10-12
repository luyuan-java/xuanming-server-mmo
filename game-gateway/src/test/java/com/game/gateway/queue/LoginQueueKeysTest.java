package com.game.gateway.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.api.proto.GateNodeInfo;
import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;

/**
 * 登录排队传给 Lua 的 KEYS 与 ARGV 前缀取注入的 {@link RedisKeys}（脚本正文不含命名空间，放行槽与元数据的键由脚本按 ARGV 前缀拼出）：
 * 缺省 game 与以前逐字节相同，另一个命名空间的实例在同一个 JVM 里各用各的前缀。不连 Redis：{@link RScript} 是替身，只记下传进去的参数。
 */
class LoginQueueKeysTest {

    private static final int ZONE = 7;
    private static final GateNodeInfo GATE = GateNodeInfo.newBuilder().setZoneId(ZONE).setNodeId(3).build();

    /** 一次 eval 的 KEYS 与 ARGV（ARGV 都是字节数组，按 US-ASCII 还原；放行槽是 protobuf 字节，照样还原、不看它）。 */
    private record Call(List<Object> keys, List<String> argv) {
    }

    /** 一轮入队、放行、轮询：排队号与三次 eval 的参数。 */
    private record Round(String queueId, Call enqueue, Call dispatch, Call lookup) {
    }

    private static Round run(String ns) {
        RedissonClient redis = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redis.getScript(ByteArrayCodec.INSTANCE)).thenReturn(script);
        List<Call> calls = new ArrayList<>();
        Deque<List<?>> results = new ArrayDeque<>(List.of(List.of(0L, 1L), List.of(1L, 0L), List.of(3L)));
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    Object[] args = invocation.getArguments();
                    Object[] argv = args.length == 5 && args[4] instanceof Object[] raw ? raw : Arrays.copyOfRange(args, 4, args.length);
                    List<Object> keys = invocation.getArgument(3);
                    calls.add(new Call(keys, Arrays.stream(argv).map(v -> new String((byte[]) v, StandardCharsets.US_ASCII)).toList()));
                    return results.poll();
                });

        LoginQueue queue = new LoginQueue(redis, new RedisKeys(GameNamespace.of(ns)), () -> 1_000L, Duration.ofMinutes(10),
                Duration.ofSeconds(60));
        String queueId = queue.enqueue(ZONE).queueId();
        assertThat(queue.dispatch(ZONE, 10, 5, GATE)).isEqualTo(new LoginQueue.Dispatched(1, 0));
        assertThat(queue.lookup(ZONE, queueId, "r1")).isInstanceOf(LoginQueue.Expired.class);
        assertThat(calls).hasSize(3);
        return new Round(queueId, calls.get(0), calls.get(1), calls.get(2));
    }

    @Test
    void 缺省game_KEYS与ARGV前缀与以前逐字节相同() {
        Round r = run("game");
        assertThat(r.enqueue().keys()).containsExactly("game:login-queue:7", "game:login-queue-meta:" + r.queueId());
        assertThat(r.dispatch().keys()).containsExactly("game:login-queue:7", "game:login-queue-admitted:7");
        assertThat(r.dispatch().argv().subList(5, 7)).as("放行脚本的 ARGV[6] / ARGV[7]：放行槽与元数据的键前缀")
                .containsExactly("game:login-queue-admit:", "game:login-queue-meta:");
        assertThat(r.lookup().keys()).containsExactly("game:login-queue-admit:" + r.queueId(), "game:login-queue-admitted:7",
                "game:login-queue:7", "game:login-queue-meta:" + r.queueId());
    }

    @Test
    void 另一个命名空间的实例_全部键与ARGV前缀换成它的前缀() {
        Round r = run("nstest");
        assertThat(r.enqueue().keys()).containsExactly("nstest:login-queue:7", "nstest:login-queue-meta:" + r.queueId());
        assertThat(r.dispatch().keys()).containsExactly("nstest:login-queue:7", "nstest:login-queue-admitted:7");
        assertThat(r.dispatch().argv().subList(5, 7)).containsExactly("nstest:login-queue-admit:", "nstest:login-queue-meta:");
        assertThat(r.lookup().keys()).containsExactly("nstest:login-queue-admit:" + r.queueId(), "nstest:login-queue-admitted:7",
                "nstest:login-queue:7", "nstest:login-queue-meta:" + r.queueId());
    }
}
