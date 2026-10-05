package com.game.discovery;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
 *
 * @param <T> 节点信息消息类型
 */
public final class NodeDirectory<T extends Message> {

    private static final Logger log = LoggerFactory.getLogger(NodeDirectory.class);

    private final RedissonClient redis;
    private final String nodeType;
    private final Parser<T> parser;

    public NodeDirectory(RedissonClient redis, String nodeType, Parser<T> parser) {
        this.redis = redis;
        this.nodeType = nodeType;
        this.parser = parser;
    }

    public void publish(int zoneId, int nodeId, T info, Duration ttl) {
        map(zoneId).fastPut(Integer.toString(nodeId), info.toByteArray(), ttl.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void remove(int zoneId, int nodeId) {
        map(zoneId).fastRemove(Integer.toString(nodeId));
    }

    /**
     * 按节点号读一条未过期的条目（异步，不阻塞调用线程）。没有（从未发布、已摘除或 TTL 已过）为空；
     * 读失败或条目解析失败时 future 异常完成——单条读是给「必须分清没有与故障」的读者（资产通道定位）用的，不像 {@link #list} 那样跳过坏条目。
     */
    public CompletableFuture<Optional<T>> findAsync(int zoneId, int nodeId) {
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
        for (byte[] bytes : map(zoneId).readAllValues()) {
            try {
                out.add(parser.parseFrom(bytes));
            } catch (InvalidProtocolBufferException e) {
                log.warn("节点目录条目解析失败 type={} zone={}", nodeType, zoneId, e);
            }
        }
        return out;
    }

    private RMapCache<String, byte[]> map(int zoneId) {
        return redis.getMapCache(RedisKeys.nodeDirectory(nodeType, zoneId),
                new CompositeCodec(StringCodec.INSTANCE, ByteArrayCodec.INSTANCE));
    }
}
