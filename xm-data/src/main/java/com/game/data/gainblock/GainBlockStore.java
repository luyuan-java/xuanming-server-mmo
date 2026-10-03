package com.game.data.gainblock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.discovery.RedisKeys;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 全服产出封禁名单的读写（Redis Hash {@code xm:gain-block:{category}}，字段 = 被封的 id，值 = 元数据 JSON），
 * 每次改动后发变更通知（{@code xm:gain-block-changed}），全部 scene 节点收到就重读。本服务是名单的唯一写者。
 * Redis 客户端第一次用到时才创建（Redis 不可用不影响本服务启动与审计消费）；调用失败抛 Redisson 的运行时异常。
 */
public final class GainBlockStore {

    /** 目前支持的类别（物品随背包批次加）。 */
    public static final String CURRENCY = "currency";

    /** 一条封禁。 */
    public record Entry(int id, String operator, long timeMs, String reason) {
    }

    private static final Logger log = LoggerFactory.getLogger(GainBlockStore.class);

    private record Meta(String operator, long timeMs, String reason) {
    }

    private final ObjectProvider<RedissonClient> redis;
    private final ObjectMapper json;

    public GainBlockStore(ObjectProvider<RedissonClient> redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    /** 某类别的全部封禁，按 id 升序。元数据解不出的条目照样列出（操作人等为空）。 */
    public List<Entry> list(String category) {
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, String> e : map(category).readAllEntrySet()) {
            int id;
            try {
                id = Integer.parseInt(e.getKey());
            } catch (NumberFormatException ex) {
                continue;
            }
            Meta meta = parse(e.getValue());
            out.add(new Entry(id, meta.operator(), meta.timeMs(), meta.reason()));
        }
        out.sort(Comparator.comparingInt(Entry::id));
        return out;
    }

    /** 封禁（已封禁则覆盖元数据）并通知。 */
    public Entry block(String category, int id, String operator, long timeMs, String reason) {
        Meta meta = new Meta(operator, timeMs, reason);
        map(category).fastPut(Integer.toString(id), write(meta));
        notifyChanged(category);
        return new Entry(id, operator, timeMs, reason);
    }

    /** 解除封禁并通知；返回之前是否在名单上。 */
    public boolean unblock(String category, int id) {
        boolean removed = map(category).fastRemove(Integer.toString(id)) > 0;
        notifyChanged(category);
        return removed;
    }

    private RMap<String, String> map(String category) {
        return redis.getObject().getMap(RedisKeys.gainBlocks(category), StringCodec.INSTANCE);
    }

    /** 通知是尽力而为：名单已经写进去了，发不出去时 scene 靠周期重读（缺省 10s）跟上，不让调用方误以为没封上。 */
    private void notifyChanged(String category) {
        try {
            redis.getObject().getTopic(RedisKeys.gainBlockChangedTopic(), StringCodec.INSTANCE).publish(category);
        } catch (RuntimeException e) {
            log.warn("全服产出封禁变更通知发送失败（scene 将在周期重读时跟上）category={}", category, e);
        }
    }

    private String write(Meta meta) {
        try {
            return json.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Meta parse(String value) {
        try {
            Meta meta = json.readValue(value, Meta.class);
            return meta == null ? new Meta("", 0, "") : meta;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return new Meta("", 0, "");
        }
    }
}
