package com.game.data.merge.retire;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import com.game.api.proto.GateNodeInfo;
import com.game.api.proto.SceneNodeInfo;
import com.game.common.GameNamespace;
import com.game.data.merge.pb.ZoneMergeRunRow;
import com.game.discovery.NodeDirectory;
import com.game.discovery.RedisKeys;
import com.game.discovery.world.*;
import com.google.protobuf.ByteString;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.RedissonObject;
import org.redisson.api.RedissonClient;
import org.redisson.api.RScript;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.config.Config;

/** 真Redis：调用前SQL冻结证明由ZoneMergeStore另测；这里验证永久屏障、并发写者与分批清理的Lua。 */
@EnabledIfSystemProperty(named="game.it.redis",matches=".+")
class RetiredZoneRedisIntegrationTest {
    private static RedissonClient redis;
    private RedisKeys keys;private ZoneMergeRunRow run;private RetiredZoneRedis maintenance;
    private final String digest="a".repeat(64);
    @BeforeAll static void connect(){var config=new Config();config.useSingleServer().setAddress(System.getProperty("game.it.redis")).setDatabase(13);redis=Redisson.create(config);}
    @AfterAll static void disconnect(){redis.shutdown();}
    @BeforeEach void setup(){
        keys=new RedisKeys(GameNamespace.of("rz"+UUID.randomUUID().toString().replace("-","").substring(0,20)));
        run=ZoneMergeRunRow.newBuilder().setRunId(UUID.randomUUID().toString()).setGeneration(UUID.randomUUID().toString())
                .setSourceZone(1).setTargetZone(2).setMode(1).setState(5).setPlanHash(ByteString.copyFrom(new byte[32])).build();
        maintenance=new RetiredZoneRedis(redis,keys);
        var gates=new NodeDirectory<>(redis,keys,"gate",GateNodeInfo.parser());
        gates.publish(1,1,GateNodeInfo.newBuilder().setInstanceId("gate-1").setLeaseEpoch(3).build(),Duration.ofMinutes(1));gates.remove(1,1);
        var scenes=new NodeDirectory<>(redis,keys,"scene",SceneNodeInfo.parser());
        scenes.publish(1,2,SceneNodeInfo.newBuilder().setInstanceId("scene-2").setLeaseEpoch(9).build(),Duration.ofMinutes(1));scenes.remove(1,2);
    }
    @AfterEach void cleanup(){redis.getKeys().deleteByPattern(keys.prefix()+"*");}
    private RetiredZoneRedis.Report execute(boolean apply)throws Exception{return maintenance.execute(run,digest,Set.of("gate","scene"),apply,()->{});}
    @Test void 计划不写_应用清热状态但永久保留原件和高水位_迟到所有写面均拒绝()throws Exception {
        var world=new RedissonWorldChannelStore(redis,keys);var directory=new RedissonWorldChannelDirectoryStore(redis,keys);
        assertThat(world.tryAcquireLeader(1,"sm",Duration.ofMinutes(1))).isTrue();
        assertThat(world.write(1,"sm",new WorldPlanBatch(0).setDesired(7,2)).status()).isEqualTo(WorldPlanWriteResult.Status.WRITTEN);
        assertThat(directory.publish(1,"sm",1,7,new byte[]{1,2},Duration.ofMinutes(1))).isTrue();
        world.reserveScene(1,100,9,Duration.ofMinutes(1));world.registerZone(1);world.registerZone(2);
        redis.getBucket(keys.nodeIdEpoch("scene",1,2),StringCodec.INSTANCE).set("9");
        var planned=execute(false);assertThat(planned.applied()).isFalse();assertThat(redis.getKeys().countExists(keys.worldRetirement(1))).isZero();
        var applied=execute(true);assertThat(applied.remainingKeys()).isZero();assertThat(applied.version()).isGreaterThan(1);
        assertThat(redis.getBucket(keys.worldRetirement(1),StringCodec.INSTANCE).get()).isEqualTo(applied.proof());
        assertThat(redis.getBucket(keys.worldRetirement(1)).remainTimeToLive()).isEqualTo(-1);
        assertThat(redis.getBucket(keys.nodeIdEpoch("scene",1,2),StringCodec.INSTANCE).get()).isEqualTo("9");
        assertThat(redis.getMap(keys.zoneNodeSeen("scene",1),StringCodec.INSTANCE).get("2")).isEqualTo("9|scene-2");
        assertThat(world.readPlan(1).channels()).isEmpty();assertThat(world.snapshot(1).desired()).isEmpty();assertThat(directory.read(1,7)).isNull();
        assertThat(world.zones()).containsExactly(2);
        assertThat(world.tryAcquireLeader(1,"new",Duration.ofMinutes(1))).isFalse();assertThat(world.renewLeader(1,"sm",Duration.ofMinutes(1))).isFalse();
        assertThat(world.write(1,"sm",new WorldPlanBatch(applied.version()).setDesired(7,5)).status()).isEqualTo(WorldPlanWriteResult.Status.FENCED);
        assertThat(directory.publish(1,"sm",applied.version(),7,new byte[]{3},Duration.ofMinutes(1))).isFalse();
        assertThatThrownBy(()->world.reserveScene(1,100,9,Duration.ofMinutes(1))).hasMessageContaining("retired");
        assertThatThrownBy(()->world.reserve(1,List.of(new ReservationCandidate(100,0)),9,Duration.ofMinutes(1))).hasMessageContaining("retired");
        assertThat(execute(true).remainingKeys()).isZero();
        assertThatThrownBy(()->maintenance.execute(run,"b".repeat(64),Set.of("gate","scene"),true,()->{})).hasMessageContaining("原件不同");
    }
    @Test void 活节点租约未退出目录类型不符和坏版本均拒绝且不生成屏障() {
        redis.getBucket(keys.nodeId("scene",1,2),StringCodec.INSTANCE).set("held");
        assertThatThrownBy(()->execute(true)).hasMessageContaining("租约");redis.getKeys().delete(keys.nodeId("scene",1,2));
        assertThatThrownBy(()->maintenance.execute(run,digest,Set.of("scene"),true,()->{})).hasMessageContaining("目录类型");
        // 新发布者重新取得租约；不能拿已结束发布者的同一代次和实例重置发布序号。
        var gate=new NodeDirectory<>(redis,keys,"gate",GateNodeInfo.parser());gate.publish(1,1,GateNodeInfo.newBuilder().setInstanceId("gate-1").setLeaseEpoch(4).build(),Duration.ofMinutes(1));
        assertThatThrownBy(()->execute(true)).hasMessageContaining("活节点");gate.remove(1,1);
        redis.getBucket(keys.worldPlanVersion(1),StringCodec.INSTANCE).set("bad");assertThatThrownBy(()->execute(true)).isInstanceOf(NumberFormatException.class);
        assertThat(redis.getKeys().countExists(keys.worldRetirement(1))).isZero();
    }
    @Test void 清理最多256键_同原件续跑保留永久版本_不会碰其它区或全局()throws Exception {
        for(int i=1;i<=270;i++)redis.getBucket(keys.worldChannelDirectory(1,i),StringCodec.INSTANCE).set("hot");
        redis.getBucket(keys.worldChannels(2),StringCodec.INSTANCE).set("other");redis.getBucket(keys.worldChannels(0),StringCodec.INSTANCE).set("global");
        var first=execute(true);assertThat(first.deletedKeys()).isEqualTo(256);assertThat(first.remainingKeys()).isGreaterThanOrEqualTo(14);
        var second=execute(true);assertThat(second.remainingKeys()).isZero();assertThat(second.version()).isGreaterThan(first.version());
        assertThat(redis.getBucket(keys.worldChannels(2),StringCodec.INSTANCE).get()).isEqualTo("other");
        assertThat(redis.getBucket(keys.worldChannels(0),StringCodec.INSTANCE).get()).isEqualTo("global");
    }
    @Test void 原子BEGIN复核防扫描后复活_失败不写屏障() {
        var gate=new NodeDirectory<>(redis,keys,"gate",GateNodeInfo.parser());
        var client=mock(RedissonClient.class,delegatesTo(redis));
        var scripts=mock(RScript.class,delegatesTo(redis.getScript(StringCodec.INSTANCE)));
        when(client.getScript(StringCodec.INSTANCE)).thenReturn(scripts);
        // 返回最后一项“无活节点”读结果之后发布新gate，BEGIN仍须在同一Lua里复核并拒绝。
        doAnswer(invocation->{
            Object result=redis.getScript(StringCodec.INSTANCE).eval(invocation.<RScript.Mode>getArgument(0),invocation.<String>getArgument(1),invocation.<RScript.ReturnType>getArgument(2),invocation.<List<Object>>getArgument(3));
            gate.publish(1,1,GateNodeInfo.newBuilder().setInstanceId("gate-1").setLeaseEpoch(4).build(),Duration.ofMinutes(1));return result;
        }).when(scripts).eval(eq(RScript.Mode.READ_WRITE),contains("ZCOUNT"),eq(RScript.ReturnType.INTEGER),eq(List.of(keys.zoneNodeExpiry("scene",1))));
        assertThatThrownBy(()->new RetiredZoneRedis(client,keys).execute(run,digest,Set.of("gate","scene"),true,()->{})).hasMessageContaining("live zone node");
        assertThat(redis.getKeys().countExists(keys.worldRetirement(1))).isZero();
    }
    @Test void 清理中失工具令牌停在原永久屏障_不删除任何热键也不删新持有者() {
        lostFence(false);
        assertThat(redis.getBucket(keys.worldLeader(1),StringCodec.INSTANCE).get()).isEqualTo("replacement");
    }
    @Test void 清理中计划版本变化拒绝CAS_版本永不回退() {
        lostFence(true);
        assertThat(redis.getBucket(keys.worldPlanVersion(1),StringCodec.INSTANCE).get()).isEqualTo("999");
    }
    private void lostFence(boolean version) {
        redis.getBucket(keys.worldChannelDirectory(1,7),StringCodec.INSTANCE).set("keep");
        var client=mock(RedissonClient.class,delegatesTo(redis));var scripts=mock(RScript.class,delegatesTo(redis.getScript(StringCodec.INSTANCE)));
        when(client.getScript(StringCodec.INSTANCE)).thenReturn(scripts);
        doAnswer(call->{
            redis.getBucket(version?keys.worldPlanVersion(1):keys.worldLeader(1),StringCodec.INSTANCE).set(version?"999":"replacement");
            Object[] args=java.util.Arrays.copyOfRange(call.getArguments(),4,call.getArguments().length);
            return redis.getScript(StringCodec.INSTANCE).eval(call.<RScript.Mode>getArgument(0),call.<String>getArgument(1),call.<RScript.ReturnType>getArgument(2),call.<List<Object>>getArgument(3),args);
        }).when(scripts).eval(eq(RScript.Mode.READ_WRITE),contains("retirement worker fenced"),eq(RScript.ReturnType.INTEGER),anyList(),any(),any(),any(),any());
        assertThatThrownBy(()->new RetiredZoneRedis(client,keys).execute(run,digest,Set.of("gate","scene"),true,()->{}))
                .hasMessageContaining(version?"plan version changed":"retirement worker fenced");
        assertThat(redis.getBucket(keys.worldRetirement(1),StringCodec.INSTANCE).get()).isEqualTo(RetiredZoneRedis.identity(run,digest));
        assertThat(redis.getBucket(keys.worldChannelDirectory(1,7),StringCodec.INSTANCE).get()).isEqualTo("keep");
    }
    @Test void 只读计划保留旧目录过期值和全部TTL元数据_不构造RMapCache()throws Exception{
        String legacy=keys.nodeDirectory("gate",1);
        String timeout=RedissonObject.prefixName("redisson__timeout__set",legacy);
        String idle=RedissonObject.prefixName("redisson__idle__set",legacy);
        redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
                "redis.call('HSET',KEYS[1],'8',struct.pack('dLc0',5000,3,'old'));redis.call('ZADD',KEYS[2],1,'8');redis.call('ZADD',KEYS[3],1,'8');return 1",
                RScript.ReturnType.INTEGER,List.of(legacy,timeout,idle));
        byte[] before=dump(legacy);byte[] beforeTimeout=dump(timeout);byte[] beforeIdle=dump(idle);
        var guarded=mock(RedissonClient.class,delegatesTo(redis));
        doThrow(new AssertionError("plan不得创建有eviction任务的RMapCache")).when(guarded)
                .getMapCache(anyString(),any(org.redisson.client.codec.Codec.class));
        var report=new RetiredZoneRedis(guarded,keys).execute(run,digest,Set.of("gate","scene"),false,()->{});
        assertThat(report.applied()).isFalse();assertThat(dump(legacy)).containsExactly(before);
        assertThat(dump(timeout)).containsExactly(beforeTimeout);assertThat(dump(idle)).containsExactly(beforeIdle);
        assertThat(redis.getKeys().countExists(keys.worldRetirement(1))).isZero();
        verify(guarded,never()).getMapCache(anyString(),any(org.redisson.client.codec.Codec.class));
    }
    @Test void 旧目录活条目不续maxIdle_损坏原件和故障也不能当空目录(){
        String legacy=keys.nodeDirectory("gate",1);String idle=RedissonObject.prefixName("redisson__idle__set",legacy);
        redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
                "local t=redis.call('TIME');local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000);redis.call('HSET',KEYS[1],'8',struct.pack('dLc0',60000,3,'old'));redis.call('ZADD',KEYS[2],now+30000,'8');return 1",
                RScript.ReturnType.INTEGER,List.of(legacy,idle));
        byte[] before=dump(legacy);byte[] beforeIdle=dump(idle);
        assertThat(maintenance.legacyHasLiveEntries(legacy)).isTrue();
        assertThatThrownBy(()->execute(false)).hasMessageContaining("旧目录仍有节点");
        assertThat(dump(legacy)).containsExactly(before);assertThat(dump(idle)).containsExactly(beforeIdle);
        redis.getMap(legacy,StringCodec.INSTANCE).put("8","bad");
        assertThatThrownBy(()->execute(false)).hasMessageContaining("malformed");
        assertThat(redis.getKeys().countExists(keys.worldRetirement(1))).isZero();
    }
    private byte[] dump(String key){return redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
            "return redis.call('DUMP',KEYS[1])",RScript.ReturnType.VALUE,List.of(key));}
}
