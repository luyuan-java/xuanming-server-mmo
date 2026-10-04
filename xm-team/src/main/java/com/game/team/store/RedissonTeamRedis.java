package com.game.team.store;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;

/**
 * {@link TeamRedis} 的 Redisson 实现（team-spec §6.5）。
 *
 * <ul>
 *   <li>编解码一律 ByteArrayCodec：TeamRecord / TeamInfo 是任意字节（含 0x00、非法 UTF-8），StringCodec 会把它们弄坏（§8.2 第 3 条）。</li>
 *   <li>单条 {@code evalAsync}，不用 RBatch：批里的脚本遇到 NOSCRIPT 不会重新加载；单条调用会自动 SCRIPT LOAD 后重试。</li>
 *   <li>模式取自 {@link TeamScript#writes()}：写脚本 READ_WRITE（含 S_INVITE_LIST），只读脚本 READ_ONLY（Redis 7 上发 EVALSHA_RO）。</li>
 *   <li>Redisson 在响应超时后会重发同一段 EVAL（缺省 retry-attempts=1）：S_COMMIT 靠 ver CAS 收敛（重发回 {@code {0}}，
 *       {@link TeamStore#mutate} 重读重算）；S_HEAL_ORPHAN 重发回 0（只丢一次「已治愈」标记）；其余脚本幂等（§6.4）。</li>
 * </ul>
 */
public final class RedissonTeamRedis implements TeamRedis {

    private final RedissonClient redis;

    public RedissonTeamRedis(RedissonClient redis) {
        this.redis = redis;
    }

    @Override
    public CompletionStage<Object> eval(TeamScript script, List<Object> keys, List<byte[]> args) {
        try {
            RScript.Mode mode = script.writes() ? RScript.Mode.READ_WRITE : RScript.Mode.READ_ONLY;
            RScript.ReturnType type = script.multi() ? RScript.ReturnType.MULTI : RScript.ReturnType.INTEGER;
            return redis.getScript(ByteArrayCodec.INSTANCE).<Object>evalAsync(mode, script.lua(), type, keys,
                    args.toArray());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletionStage<byte[]> hget(String key, String field) {
        try {
            return redis.<byte[], byte[]>getMap(key, ByteArrayCodec.INSTANCE)
                    .getAsync(field.getBytes(StandardCharsets.US_ASCII));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
