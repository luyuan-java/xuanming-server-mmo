package com.game.gate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.game.api.ClientMessageService;
import com.game.api.proto.GateNodeInfo;
import com.game.common.GameNamespace;
import com.game.common.RunMode;
import com.game.common.token.GateTokens;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeIdLease;
import com.game.discovery.RedisKeys;
import com.game.gate.metrics.GateMetrics;
import com.game.gate.session.SessionRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

/** 受控在途发布：停服必须在其后写删除意图，不能cancel(false)后抢先摘除。 */
class GateDirectoryStopTest {
    @Test void 在途发布完成后才摘除_停止后不再发布()throws Exception{
        var node=new GateNode(mock(RedissonClient.class),new RedisKeys(GameNamespace.of("gatestop")),
                MessageIdRegistry.loadFromClasspath(),GateTokens.ofUtf8("gate-directory-stop-test"),null,
                mock(ClientMessageService.class),Map.of(),new GateProperties(null,null,null,null,null,null,null,null,null,null,null,null),
                2,"127.0.0.1",Path.of("config-data/tables"),GateMetrics.noop(),RunMode.PROD);
        var lease=mock(NodeIdLease.class);when(lease.nodeId()).thenReturn(7);when(lease.leaseEpoch()).thenReturn(9L);
        var sessions=mock(SessionRegistry.class);
        @SuppressWarnings("unchecked") NodeDirectory<GateNodeInfo> directory=mock(NodeDirectory.class);
        var entered=new CountDownLatch(1);var allowCompletion=new CountDownLatch(1);var stopEntered=new CountDownLatch(1);
        var order=new CopyOnWriteArrayList<String>();
        doAnswer(call->{entered.countDown();if(!allowCompletion.await(5,TimeUnit.SECONDS))throw new AssertionError("没有释放目录发布");
            order.add("published");return null;}).when(directory).publish(eq(2),eq(7),any(),eq(Duration.ofSeconds(15)));
        doAnswer(call->{order.add("removed");return null;}).when(directory).remove(2,7);
        var task=mock(ScheduledFuture.class);when(task.cancel(false)).thenAnswer(call->{stopEntered.countDown();return true;});
        ReflectionTestUtils.setField(node,"lease",lease);ReflectionTestUtils.setField(node,"registry",sessions);
        ReflectionTestUtils.setField(node,"gateDirectory",directory);ReflectionTestUtils.setField(node,"publishTask",task);
        var workers=Executors.newFixedThreadPool(2);
        try{
            var publishing=workers.submit(()->{ReflectionTestUtils.invokeMethod(node,"publish");});
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            var stopping=workers.submit(()->{ReflectionTestUtils.invokeMethod(node,"stopAccepting",true);});
            assertThat(stopEntered.await(2,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->stopping.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            verify(directory,never()).remove(anyInt(),anyInt());
            allowCompletion.countDown();publishing.get(2,TimeUnit.SECONDS);stopping.get(2,TimeUnit.SECONDS);
            ReflectionTestUtils.invokeMethod(node,"publish");
            assertThat(order).containsExactly("published","removed");
            verify(directory,times(1)).publish(eq(2),eq(7),any(),eq(Duration.ofSeconds(15)));
            verify(directory,times(1)).remove(2,7);
        }finally{allowCompletion.countDown();workers.shutdownNow();assertThat(workers.awaitTermination(2,TimeUnit.SECONDS)).isTrue();}
    }
}
