package com.game.discovery;

import static org.assertj.core.api.Assertions.*;
import com.game.api.proto.GateNodeInfo;
import com.game.api.proto.SceneNodeInfo;
import com.game.common.GameNamespace;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.CompositeCodec;
import org.redisson.config.Config;

/** 真Redis核v2迁移、永久退休和迟到删除；每例独立命名空间，测试清理不用于生产维护。 */
@EnabledIfSystemProperty(named="game.it.redis",matches=".+")
class ZoneDirectoryRedisIntegrationTest {
    private static RedissonClient redis;
    private RedisKeys keys;
    @BeforeAll static void connect(){var config=new Config();config.useSingleServer().setAddress(System.getProperty("game.it.redis")).setDatabase(13);redis=Redisson.create(config);}
    @AfterAll static void close(){redis.shutdown();}
    @BeforeEach void setup(){keys=new RedisKeys(GameNamespace.of("zd"+UUID.randomUUID().toString().replace("-","").substring(0,20)));}
    @AfterEach void cleanup(){redis.getKeys().deleteByPattern(keys.prefix()+"*");}
    private NodeDirectory<GateNodeInfo> gates(){return new NodeDirectory<>(redis,keys,"gate",GateNodeInfo.parser());}
    private GateNodeInfo info(String instance){return GateNodeInfo.newBuilder().setZoneId(1).setNodeId(7).setInstanceId(instance).build();}
    private void legacy(int node,byte[] bytes){redis.<String,byte[]>getMapCache(keys.nodeDirectory("gate",1),new CompositeCodec(StringCodec.INSTANCE,ByteArrayCodec.INSTANCE)).fastPut(Integer.toString(node),bytes,60,java.util.concurrent.TimeUnit.SECONDS);}
    @Test void 未迁移条目回退旧目录_迁移后原件优先_摘除与过期永不回退旧原件() {
        var directory=gates();legacy(7,info("legacy").toByteArray());
        assertThat(directory.list(1)).containsExactly(info("legacy"));
        directory.publish(1,7,info("v2"),Duration.ofMinutes(1));assertThat(directory.list(1)).containsExactly(info("v2"));
        directory.remove(1,7);assertThat(directory.list(1)).isEmpty();
        directory.publish(1,7,info("v2"),Duration.ofMinutes(1));
        redis.getScoredSortedSet(keys.zoneNodeExpiry("gate",1),StringCodec.INSTANCE).add(1,"7");
        assertThat(directory.findAsync(1,7).join()).isEmpty();assertThat(directory.list(1)).isEmpty();
    }
    @Test void 旧实例迟到摘除不能删除新实例_无成功发布原件也不能删除() {
        var old=gates();var current=gates();old.publish(1,7,info("old"),Duration.ofMinutes(1));current.publish(1,7,info("new"),Duration.ofMinutes(1));
        old.remove(1,7);gates().remove(1,7);assertThat(current.list(1)).containsExactly(info("new"));
        current.remove(1,7);assertThat(current.list(1)).isEmpty();
    }
    @Test void 代次高水位精确比较大整数_摘除过期后仍拒旧代和同代异实例() {
        var old=gates();var current=gates();long newer=9_007_199_254_740_993L;
        var first=info("old").toBuilder().setLeaseEpoch(newer-1).build();
        var next=info("new").toBuilder().setLeaseEpoch(newer).build();
        old.publish(1,7,first,Duration.ofMinutes(1));current.publish(1,7,next,Duration.ofMinutes(1));
        assertThatThrownBy(()->old.publish(1,7,first,Duration.ofMinutes(1))).hasMessageContaining("旧代");
        assertThatThrownBy(()->old.publish(1,7,first.toBuilder().setLeaseEpoch(newer).build(),Duration.ofMinutes(1))).hasMessageContaining("异实例");
        old.remove(1,7);assertThat(current.list(1)).containsExactly(next);
        current.remove(1,7);
        assertThatThrownBy(()->old.publish(1,7,first,Duration.ofMinutes(1))).hasMessageContaining("旧代");
        assertThatThrownBy(()->old.publish(1,7,info("zero"),Duration.ofMinutes(1))).hasMessageContaining("旧代");
        current.publish(1,7,next,Duration.ofMinutes(1));
        redis.getScoredSortedSet(keys.zoneNodeExpiry("gate",1),StringCodec.INSTANCE).add(1,"7");
        assertThatThrownBy(()->old.publish(1,7,first,Duration.ofMinutes(1))).hasMessageContaining("旧代");
        assertThat(redis.getMap(keys.zoneNodeSeen("gate",1),StringCodec.INSTANCE).get("7")).isEqualTo(newer+"|new");
    }
    @Test void 退休原件永久挡全部按区目录_旧二进制遗留条目不可复活_全局不受影响() {
        var directory=gates();directory.publish(1,7,info("v2"),Duration.ofMinutes(1));legacy(8,info("legacy").toByteArray());
        redis.getBucket(keys.worldRetirement(1),StringCodec.INSTANCE).set("approved-original");
        assertThat(directory.listAsync(1).join()).isEmpty();assertThat(directory.findAsync(1,8).join()).isEmpty();
        assertThatThrownBy(()->directory.publish(1,7,info("late"),Duration.ofMinutes(1))).isInstanceOf(IllegalStateException.class);
        var scenes=new NodeDirectory<>(redis,keys,"scene",SceneNodeInfo.parser());
        assertThatThrownBy(()->scenes.publish(1,9,SceneNodeInfo.getDefaultInstance(),Duration.ofMinutes(1))).isInstanceOf(IllegalStateException.class);
        directory.publish(0,7,info("global"),Duration.ofMinutes(1));assertThat(directory.listAsync(0).join()).containsExactly(info("global"));
        assertThat(redis.getBucket(keys.worldRetirement(1),StringCodec.INSTANCE).remainTimeToLive()).isEqualTo(-1);
    }
    @Test void 严格异步不把坏原件当空列表_同步保留逐条跳过约定() {
        var directory=gates();legacy(7,new byte[]{(byte)255});legacy(8,info("good").toByteArray());
        assertThat(directory.list(1)).containsExactly(info("good"));
        assertThatThrownBy(()->directory.listAsync(1).join()).isInstanceOf(CompletionException.class).hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->directory.findAsync(1,7).join()).isInstanceOf(CompletionException.class).hasCauseInstanceOf(IllegalStateException.class);
    }
    @Test void 同代旧发布和摘除乱序不能覆盖新快照_删除墓碑挡未知命令复活() {
        var raw=new ZoneNodeDirectory(redis,keys,"gate");var first=info("same").toBuilder().setLeaseEpoch(9).setPlayerCount(1).build();
        var second=first.toBuilder().setPlayerCount(2).build();
        raw.publish(1,7,first.toByteArray(),Duration.ofMinutes(1),9,"same",1);
        raw.publish(1,7,second.toByteArray(),Duration.ofMinutes(1),9,"same",2);
        assertThatThrownBy(()->raw.publish(1,7,first.toByteArray(),Duration.ofMinutes(1),9,"same",1)).hasMessageContaining("旧发布序号");
        assertThat(gates().list(1)).containsExactly(second);
        raw.remove(1,7,9,"same",3);
        assertThatThrownBy(()->raw.publish(1,7,second.toByteArray(),Duration.ofMinutes(1),9,"same",2)).hasMessageContaining("旧发布序号");
        assertThat(gates().list(1)).isEmpty();
        assertThat(redis.getMap(keys.zoneNodeSeen("gate",1),StringCodec.INSTANCE).get("seq:7")).isEqualTo("3");
        assertThat(redis.getMap(keys.zoneNodeSeen("gate",1),StringCodec.INSTANCE).get("state:7")).isEqualTo("removed");
        raw.publish(1,7,second.toByteArray(),Duration.ofMinutes(1),9,"same",4);
        raw.remove(1,7,9,"same",3);assertThat(gates().list(1)).containsExactly(second);
        // 发布从未到达Redis，摘除仍须先立墓碑；之后重放未知发布不能出现条目。
        raw.remove(1,8,9,"same",2);
        assertThatThrownBy(()->raw.publish(1,8,first.toByteArray(),Duration.ofMinutes(1),9,"same",1)).hasMessageContaining("旧发布序号");
        assertThat(gates().findAsync(1,8).join()).isEmpty();
    }
    @Test void 发布序号超过double精确范围仍逐个比较_同序重试不延长TTL或改载荷() {
        var raw=new ZoneNodeDirectory(redis,keys,"gate");long sequence=9_007_199_254_740_993L;
        var first=info("same").toBuilder().setLeaseEpoch(9).build();var next=first.toBuilder().setPlayerCount(1).build();
        raw.publish(1,7,first.toByteArray(),Duration.ofMinutes(1),9,"same",sequence-1);
        raw.publish(1,7,next.toByteArray(),Duration.ofMinutes(1),9,"same",sequence);
        Double expiry=redis.getScoredSortedSet(keys.zoneNodeExpiry("gate",1),StringCodec.INSTANCE).getScore("7");
        raw.publish(1,7,next.toByteArray(),Duration.ofHours(1),9,"same",sequence);
        assertThat(redis.getScoredSortedSet(keys.zoneNodeExpiry("gate",1),StringCodec.INSTANCE).getScore("7")).isEqualTo(expiry);
        assertThatThrownBy(()->raw.publish(1,7,first.toByteArray(),Duration.ofMinutes(1),9,"same",sequence-1)).hasMessageContaining("旧发布序号");
        assertThatThrownBy(()->raw.publish(1,7,first.toByteArray(),Duration.ofMinutes(1),9,"same",sequence)).hasMessageContaining("旧发布序号");
        assertThat(gates().list(1)).containsExactly(next);
        assertThat(redis.getMap(keys.zoneNodeSeen("gate",1),StringCodec.INSTANCE).get("seq:7")).isEqualTo(Long.toString(sequence));
    }
}
