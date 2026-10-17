package com.game.team.store;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.redisson.api.RFuture;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.OptionalOptions;
import org.redisson.client.RedisNoScriptException;
import org.redisson.client.codec.ByteArrayCodec;

/**
 * {@link TeamRedis} 的 Redisson 实现（team-spec §6.5）。
 *
 * <ul>
 *   <li>编解码一律 ByteArrayCodec：TeamRecord / TeamInfo 是任意字节（含 0x00、非法 UTF-8），StringCodec 会把它们弄坏（§8.2 第 3 条）。</li>
 *   <li>单条调用，不用 RBatch：批里的脚本遇到 NOSCRIPT 不会重新加载。读脚本走 {@code evalAsync}，由 Redisson 自动 SCRIPT LOAD 后重试；
 *       写脚本自己处理 NOSCRIPT（见 {@link #evalWrite}）。</li>
 *   <li>模式取自 {@link TeamScript#writes()}：写脚本 READ_WRITE（含 S_INVITE_LIST），只读脚本 READ_ONLY（Redis 7 上发 EVALSHA_RO）。</li>
 *   <li>写脚本走 {@link #evalWrite}：不重发、Redisson 超时取剩余预算（理由见 {@link TeamRedis#evalWrite}）。读脚本仍走全局配置，
 *       响应超时后会被原样重发（缺省 retry-attempts=1），读没有副作用。回复丢失时写脚本仍可能已经执行：S_COMMIT 靠 ver CAS 收敛，
 *       开战锁靠按 token 确认（§6.4）。</li>
 * </ul>
 */
public final class RedissonTeamRedis implements TeamRedis {

    /** 各脚本的 SHA-1（与 Redis / Redisson 的算法相同：UTF-8 字节、小写十六进制），写脚本按它发 EVALSHA。 */
    private static final Map<TeamScript, String> SHA1 = sha1All();

    private final RedissonClient redis;

    public RedissonTeamRedis(RedissonClient redis) {
        this.redis = redis;
    }

    @Override
    public CompletionStage<Object> eval(TeamScript script, List<Object> keys, List<byte[]> args) {
        try {
            return redis.getScript(ByteArrayCodec.INSTANCE).<Object>evalAsync(mode(script), script.lua(), type(script), keys,
                    args.toArray());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 写脚本：发 EVALSHA；遇到 NOSCRIPT（Redis 重启、主从切换、SCRIPT FLUSH 之后脚本缓存为空）先重查截止，SCRIPT LOAD 之后再查一次，
     * 都没过才重新发出，三次发出各自用此刻的剩余预算做 Redisson 超时。
     *
     * <p>为什么不交给 Redisson 自动回退（3.50 {@code CommandAsyncService.evalAsync} 的 NOSCRIPT 分支）：它的 SCRIPT LOAD 与第二次 EVALSHA
     * 沿用第一次发出时取下的超时、各自重新计时，也不看截止；连接池吃紧时第二次 EVALSHA 可以在截止之后好几秒才发出，越过 gate 放行同一会话下一条
     * 的时刻。NOSCRIPT 时脚本没有执行，此时放弃是「没有提交」，调用方按依赖故障处理。截止检查只管到「交给驱动」为止：已经写进连接的那条命令
     * 在 Redis 卡顿时仍可能晚执行（client-request-ordering.md §6.1 的剩余窗口）。第一次发出前的截止由 {@link TeamStore} 查。
     */
    @Override
    public CompletionStage<Object> evalWrite(TeamScript script, List<Object> keys, List<byte[]> args, Deadline deadline) {
        try {
            return evalSha(script, keys, args, deadline).exceptionallyCompose(error -> {
                Throwable cause = unwrap(error);
                if (!(cause instanceof RedisNoScriptException)) {
                    return CompletableFuture.<Object>failedFuture(cause);
                }
                if (deadline.expired()) {
                    return CompletableFuture.<Object>failedFuture(
                            new DependencyException(script + " 遇到 NOSCRIPT 时截止已过（没有重新加载、没有再发出）", cause));
                }
                return load(script, keys, deadline).<Object>thenCompose(loaded -> {
                    if (deadline.expired()) {
                        return CompletableFuture.<Object>failedFuture(
                                new DependencyException(script + " 重新加载之后截止已过（没有再发出）", cause));
                    }
                    return evalSha(script, keys, args, deadline);
                });
            });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 写脚本句柄的参数：编解码同读；{@code retryAttempts = 0}；Redisson 超时 = 此刻的剩余预算（至少 1 ms，每次发出前现取）。
     * 不重发时 Redisson 用同一个超时限制「等连接」（3.50 {@code RedisExecutor.scheduleConnectionTimeout}：attempts 为 0 时按 responseTimeout 定时），
     * 沿用全局 2 s 的话，截止前一刻排上的写可以在截止之后近 2 s 才真正发出；取剩余预算后，到截止还没拿到连接的写被放弃、不再发出。
     * 不重发与 game-match 客户端写的脚本句柄同一条规则（team 不依赖 game-match，在这里各写一份）；超时取法不同：这里只取剩余预算，不再与全局
     * {@code game.redis.timeout-ms} 取小——发出时刻的上界只由截止决定，代价是单条写等应答最长可到请求剩余（≤ 3.5 s），调用方仍按截止停等。
     */
    static OptionalOptions writeOptions(Deadline deadline) {
        return OptionalOptions.defaults().codec(ByteArrayCodec.INSTANCE).retryAttempts(0)
                .timeout(Duration.ofMillis(Math.max(1, deadline.remainingMillis())));
    }

    private CompletableFuture<Object> evalSha(TeamScript script, List<Object> keys, List<byte[]> args, Deadline deadline) {
        return redis.getScript(writeOptions(deadline))
                .<Object>evalShaAsync(mode(script), SHA1.get(script), type(script), keys, args.toArray())
                .toCompletableFuture();
    }

    /** 加载到执行这条写的那个节点（按第一个键路由，同 EVALSHA）；SCRIPT LOAD 本身不改业务数据，同样不重发、不越过截止。 */
    private RFuture<String> load(TeamScript script, List<Object> keys, Deadline deadline) {
        RScript handle = redis.getScript(writeOptions(deadline));
        if (keys.isEmpty()) {
            return handle.scriptLoadAsync(script.lua());
        }
        Object first = keys.get(0);
        String key = first instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(first);
        return handle.scriptLoadAsync(key, script.lua());
    }

    private static RScript.Mode mode(TeamScript script) {
        return script.writes() ? RScript.Mode.READ_WRITE : RScript.Mode.READ_ONLY;
    }

    private static RScript.ReturnType type(TeamScript script) {
        return script.multi() ? RScript.ReturnType.MULTI : RScript.ReturnType.INTEGER;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable t = error;
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static Map<TeamScript, String> sha1All() {
        Map<TeamScript, String> shas = new EnumMap<>(TeamScript.class);
        try {
            for (TeamScript script : TeamScript.values()) {
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                shas.put(script, HexFormat.of().formatHex(digest.digest(script.lua().getBytes(StandardCharsets.UTF_8))));
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 必须提供 SHA-1", e);
        }
        return shas;
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
