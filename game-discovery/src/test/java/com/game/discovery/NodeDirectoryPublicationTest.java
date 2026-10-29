package com.game.discovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.game.api.proto.GateNodeInfo;
import com.game.common.GameNamespace;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;

/** 无Redis也验证本地意图在发送前登记，回包顺序不能倒退删除所用序域。 */
class NodeDirectoryPublicationTest {
    private final RedissonClient redis=mock(RedissonClient.class);
    private final RScript script=mock(RScript.class);
    private final List<Object[]> publishes=new CopyOnWriteArrayList<>();
    private final List<Object[]> removes=new CopyOnWriteArrayList<>();
    private final NodeDirectory<GateNodeInfo> directory;
    NodeDirectoryPublicationTest(){
        when(redis.getScript(ByteArrayCodec.INSTANCE)).thenReturn(script);
        directory=new NodeDirectory<>(redis,new RedisKeys(GameNamespace.of("publicationtest")),"gate",GateNodeInfo.parser());
        doAnswer(call->{removes.add((Object[])call.getRawArguments()[4]);return 1L;}).when(script)
                .eval(eq(RScript.Mode.READ_WRITE),eq(ZoneNodeDirectory.REMOVE),eq(RScript.ReturnType.INTEGER),anyList(),any(Object[].class));
    }
    private GateNodeInfo info(int count){return GateNodeInfo.newBuilder().setZoneId(1).setNodeId(7)
            .setInstanceId("current").setLeaseEpoch(9).setPlayerCount(count).build();}
    private static String value(Object[] args,int index){return new String((byte[])args[index],StandardCharsets.UTF_8);}

    @Test void 首次发布未确认仍能摘除_删除使用更大序号和原身份(){
        doAnswer(call->{publishes.add((Object[])call.getRawArguments()[4]);throw new IllegalStateException("response unknown");})
                .when(script).eval(eq(RScript.Mode.READ_WRITE),eq(ZoneNodeDirectory.PUBLISH),eq(RScript.ReturnType.INTEGER),anyList(),any(Object[].class));
        assertThatThrownBy(()->directory.publish(1,7,info(1),Duration.ofSeconds(15))).hasMessageContaining("unknown");
        directory.remove(1,7);
        assertThat(publishes).hasSize(1);assertThat(value(publishes.getFirst(),6)).isEqualTo("1");
        assertThat(removes).hasSize(1);assertThat(value(removes.getFirst(),1)).isEqualTo("9");
        assertThat(value(removes.getFirst(),2)).isEqualTo("current");assertThat(value(removes.getFirst(),3)).isEqualTo("2");
    }

    @Test void 旧成功回包不能覆盖新意图_摘除和再次发布继续递增()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        doAnswer(call->{publishes.add((Object[])call.getRawArguments()[4]);if(calls.incrementAndGet()==1){
            entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("测试没有释放旧发布");}return 1L;})
                .when(script).eval(eq(RScript.Mode.READ_WRITE),eq(ZoneNodeDirectory.PUBLISH),eq(RScript.ReturnType.INTEGER),anyList(),any(Object[].class));
        var worker=Executors.newSingleThreadExecutor();
        try{
            var first=worker.submit(()->directory.publish(1,7,info(1),Duration.ofSeconds(15)));
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            directory.publish(1,7,info(2),Duration.ofSeconds(15));release.countDown();first.get(2,TimeUnit.SECONDS);
            directory.remove(1,7);directory.publish(1,7,info(3),Duration.ofSeconds(15));
            assertThat(publishes).extracting(args->value(args,6)).containsExactly("1","2","4");
            assertThat(removes).hasSize(1);assertThat(value(removes.getFirst(),3)).isEqualTo("3");
        }finally{release.countDown();worker.shutdownNow();assertThat(worker.awaitTermination(2,TimeUnit.SECONDS)).isTrue();}
    }
}
