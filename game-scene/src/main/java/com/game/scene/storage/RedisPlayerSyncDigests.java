package com.game.scene.storage;

import com.game.discovery.RedisKeys;
import com.game.proto.SyncDigestS2C;
import com.game.scene.world.PlayerSyncDigests;
import com.game.scene.world.SyncDigestParser;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于共享 Redis Hash（{@code <ns>:player:{<player_id>}:sync_digest}）的同步摘要异步读源。
 * 回调始终切换回场景逻辑线程，任何 Redis 故障或字段契约畸形都 fail-closed 返回 {@code UNAVAILABLE}。
 */
public final class RedisPlayerSyncDigests implements PlayerSyncDigests {

    private static final Logger log = LoggerFactory.getLogger(RedisPlayerSyncDigests.class);

    private final RedissonClient redis;
    private final RedisKeys keys;
    private final Executor logic;

    public RedisPlayerSyncDigests(RedissonClient redis, RedisKeys keys, Executor logic) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.logic = Objects.requireNonNull(logic, "logic");
    }

    @Override
    public void read(long playerId, Consumer<SyncDigestS2C> onResultOnLogic) {
        if (playerId == 0) {
            onResultOnLogic.accept(SyncDigestParser.unavailable());
            return;
        }
        String key = keys.playerSyncDigest(playerId);
        try {
            RMap<String, String> map = redis.getMap(key, StringCodec.INSTANCE);
            map.readAllMapAsync().whenComplete((fields, error) -> logic.execute(() -> {
                if (error != null) {
                    log.warn("[PlayerLanding] sync digest read failed; UNAVAILABLE pushed player_id={} err={}",
                            Long.toUnsignedString(playerId), error.getMessage());
                    onResultOnLogic.accept(SyncDigestParser.unavailable());
                    return;
                }
                Optional<SyncDigestS2C> parsed = SyncDigestParser.parse(fields == null ? Map.of() : fields);
                if (parsed.isEmpty()) {
                    log.error("[PlayerLanding] malformed sync digest hash; UNAVAILABLE pushed player_id={} fields={}",
                            Long.toUnsignedString(playerId), fields == null ? 0 : fields.size());
                    onResultOnLogic.accept(SyncDigestParser.unavailable());
                    return;
                }
                onResultOnLogic.accept(parsed.get());
            }));
        } catch (RuntimeException e) {
            log.warn("[PlayerLanding] sync digest read dispatch failed; UNAVAILABLE pushed player_id={} err={}",
                    Long.toUnsignedString(playerId), e.getMessage());
            onResultOnLogic.accept(SyncDigestParser.unavailable());
        }
    }
}
