package com.game.discovery;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.CompositeCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 游戏节点在线目录（Redis）：每个节点定期把自己的信息写成一个带 TTL 的条目，读方拿到的都是未过期条目。
 *
 * <p>Nacos 只做 Dubbo 注册中心；gate / scene 这类游戏节点的在线信息（人数、场景列表、链路地址）放这里，
 * 这样本地 profile 不需要 Nacos 服务端也能跑通。
 *
 * <p>契约：条目在最后一次 {@link #publish} 之后 {@code ttl} 内可见；节点异常退出后最多 {@code ttl} 才消失，
 * 读方必须容忍拿到刚死的节点（连接失败按不可用处理）。线程安全。
 * <p>非零区统一写同槽v2；未迁移节点可读旧目录，已迁移节点即使过期也不回退。永久退休后忽略全部旧目录。
 * v2写者须复用同一个发布对象；lease_epoch/instance_id拒绝旧代及同代异实例，同代发布序号拒绝迟到快照与摘除后的复活。
 * 每次意图发出前分配序号，网络未知仍保留；相同实例重建发布对象必须先取得新租约代次，不能重置已有序域。
 * 滚动迁移须先升级所有读方再启用v2写方，或维护停机一起升级；旧读方看不到v2新条目。
 *
 * @param <T> 节点信息消息类型
 */
public final class NodeDirectory<T extends Message> {

    private static final Logger log = LoggerFactory.getLogger(NodeDirectory.class);

    private final RedissonClient redis;
    private final RedisKeys redisKeys;
    private final String nodeType;
    private final Parser<T> parser;
    private final ZoneNodeDirectory zones;
    private record NodeKey(int zone,int node) { }
    private record Publication(long epoch,String instance,long sequence) { }
    /** 发出前登记意图；远端结果未知仍占用序号，迟到成功绝不回写旧本地状态。 */
    private final ConcurrentHashMap<NodeKey,Publication> publications = new ConcurrentHashMap<>();

    public NodeDirectory(RedissonClient redis, RedisKeys keys, String nodeType, Parser<T> parser) {
        this.redis = redis;
        this.redisKeys = Objects.requireNonNull(keys, "keys");
        this.nodeType = nodeType;
        this.parser = parser;
        this.zones = new ZoneNodeDirectory(redis, keys, nodeType);
    }

    public void publish(int zoneId, int nodeId, T info, Duration ttl) {
        if (zoneId != 0) {
            byte[] original=info.toByteArray();
            var epochField=info.getDescriptorForType().findFieldByName("lease_epoch");
            var instanceField=info.getDescriptorForType().findFieldByName("instance_id");
            long epoch=epochField==null?0:(Long)info.getField(epochField);
            String instance=instanceField==null?"":(String)info.getField(instanceField);
            Publication attempt=publications.compute(new NodeKey(zoneId,nodeId),(key,previous)->{
                if(previous!=null&&(epoch<previous.epoch()||epoch>0&&epoch==previous.epoch()&&!instance.equals(previous.instance())))
                    throw new IllegalStateException("旧代或同代异实例禁止发布节点目录");
                long sequence=previous!=null&&epoch==previous.epoch()&&instance.equals(previous.instance())
                        ?Math.incrementExact(previous.sequence()):1;
                return new Publication(epoch,instance,sequence);
            });
            zones.publish(zoneId,nodeId,original,ttl,epoch,instance,attempt.sequence());
            return;
        }
        map(zoneId).fastPut(Integer.toString(nodeId), info.toByteArray(), ttl.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void remove(int zoneId, int nodeId) {
        if (zoneId != 0) {
            Publication remove=publications.computeIfPresent(new NodeKey(zoneId,nodeId),(key,previous)->
                    new Publication(previous.epoch(),previous.instance(),Math.incrementExact(previous.sequence())));
            // 未知发布也必须以更大的序号封住；墓碑保留，旧实例/旧序号不能撤销或复活新原件。
            if(remove!=null)zones.remove(zoneId,nodeId,remove.epoch(),remove.instance(),remove.sequence());
            return;
        }
        map(zoneId).fastRemove(Integer.toString(nodeId));
    }

    /**
     * 按节点号读一条未过期的条目（异步，不阻塞调用线程）。没有（从未发布、已摘除或 TTL 已过）为空；
     * 读失败或条目解析失败时 future 异常完成——单条读是给「必须分清没有与故障」的读者（资产通道定位）用的，不像 {@link #list} 那样跳过坏条目。
     */
    public CompletableFuture<Optional<T>> findAsync(int zoneId, int nodeId) {
        if (zoneId != 0) return rawZone(zoneId, nodeId).thenApply(values -> values.values().stream().findFirst().map(this::parseStrict));
        CompletableFuture<byte[]> read;
        try {
            read = map(zoneId).getAsync(Integer.toString(nodeId)).toCompletableFuture();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return read.thenApply(bytes -> {
            if (bytes == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(parser.parseFrom(bytes));
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalStateException("节点目录条目解析失败 type=" + nodeType + " zone=" + zoneId + " node=" + nodeId, e);
            }
        });
    }

    /** 本 zone 下所有未过期的节点；单条解析失败只跳过并告警，不影响其他节点。 */
    public List<T> list(int zoneId) {
        List<T> out = new ArrayList<>();
        java.util.Map<String,byte[]> values;
        try { values=rawValues(zoneId).join(); }
        catch(CompletionException failure) {
            if(failure.getCause() instanceof RuntimeException cause)throw cause;
            throw failure;
        }
        for (byte[] bytes : values.values()) {
            try {
                out.add(parser.parseFrom(bytes));
            } catch (InvalidProtocolBufferException e) {
                log.warn("节点目录条目解析失败 type={} zone={}", nodeType, zoneId, e);
            }
        }
        return out;
    }

    /** 严格异步目录快照；坏原件/读取故障均异常完成，调用者自行施加本次业务截止时刻。 */
    public CompletableFuture<List<T>> listAsync(int zoneId) {
        return rawValues(zoneId).thenApply(values -> values.values().stream().map(this::parseStrict).toList());
    }

    private T parseStrict(byte[] bytes) {
        try { return parser.parseFrom(bytes); }
        catch (InvalidProtocolBufferException failure) { throw new IllegalStateException("节点目录原件解析失败 type="+nodeType, failure); }
    }

    private CompletableFuture<java.util.Map<String,byte[]>> rawValues(int zone) {
        try { return zone==0 ? map(zone).readAllMapAsync().toCompletableFuture() : rawZone(zone,null); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }

    private CompletableFuture<java.util.Map<String,byte[]>> rawZone(int zone,Integer node) {
        try {
            return zones.read(zone,node).thenCompose(snapshot -> {
                if(snapshot.retired())return CompletableFuture.completedFuture(java.util.Map.<String,byte[]>of());
                CompletableFuture<java.util.Map<String,byte[]>> legacy;
                if(node==null) legacy=map(zone).readAllMapAsync().toCompletableFuture();
                else if(snapshot.seen().contains(Integer.toString(node))) legacy=CompletableFuture.completedFuture(java.util.Map.of());
                else legacy=map(zone).getAsync(Integer.toString(node)).toCompletableFuture().thenApply(value ->
                        value==null?java.util.Map.of():java.util.Map.of(Integer.toString(node),value));
                return legacy.thenCompose(old -> zones.retired(zone).thenApply(retired -> {
                    if(retired)return java.util.Map.<String,byte[]>of();
                    java.util.Map<String,byte[]> merged=new java.util.TreeMap<>();
                    old.forEach((id,value)->{if(!snapshot.seen().contains(id))merged.put(id,value);});
                    merged.putAll(snapshot.values());return merged;
                }));
            });
        } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }

    private RMapCache<String, byte[]> map(int zoneId) {
        return redis.getMapCache(redisKeys.nodeDirectory(nodeType, zoneId),
                new CompositeCodec(StringCodec.INSTANCE, ByteArrayCodec.INSTANCE));
    }
}
