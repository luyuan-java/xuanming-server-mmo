package com.game.match.port;

import com.game.common.deadline.Deadline;
import com.game.discovery.RedisProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.OptionalOptions;
import org.redisson.client.RedisNoScriptException;
import org.redisson.client.codec.Codec;

/**
 * 客户端请求路径上 Redis 写的发出方式（mmorpg docs/design/client-request-ordering.md §6.1；PARITY「客户端请求按会话保序」）：157 建票 / 入队 /
 * 自愈、148 取消、152 发起切磋、151 消费邀请、163 抢观战标记、211 整队开战（与帮会活动开战）的整组建票。票据、切磋、观战三个存储共用这一条规则，不各写一份。
 *
 * <p><b>为什么</b>：gate 对同一会话的后端请求单飞，本次调用有了结局（应答，或 gate 的 5 s 调用超时）就放行下一条——放行的时刻是<b>回包</b>，
 * 不是等满 5 s。match 的请求截止 = 受理时刻 + 4.5 s（经 team / guild 转进来的调用再与调用方带来的预算附件取小），截止一到处理器就回包
 * （{@link Deadline#await} 不等在途的命令），调用方随即放行下一条。所以写只能在截止之前发出，之后不能再冒出来：
 * <ul>
 *   <li><b>不重发</b>（{@code retryAttempts = 0}）：全局缺省 {@code game.redis.retry-attempts = 1}，一条命令响应超时（2 s）后隔 200 ms 原样重发，
 *       截止前发出的写最晚约 4.2 s 之后才落地——那时 gate 早已放行下一条，「加入 → 取消」会执行成「取消 → 加入」。</li>
 *   <li><b>Redisson 的超时 = min(配置的 {@code game.redis.timeout-ms}, 剩余预算 − 一个定时器刻度)</b>：不重发时 Redisson 用同一个超时限制「等连接」
 *       （连接池占满 / 正在重连；Redisson 3.50 {@code RedisExecutor.scheduleConnectionTimeout}），等到连接就发出。只用全局的 2 s，截止前一刻排上的写
 *       可以在截止之后近 2 s 才真正发出去。这个放弃由 Redisson 的时间轮触发，到点最多晚一个刻度（{@link #TIMER_TICK_BOUND_NANOS}）：超时若只封顶到
 *       剩余预算，截止后一个刻度之内拿到连接的写照样发出，而那时 match 已经回包、gate 已经放行同一会话的下一条，GroupTickets 也可能已经按票号回滚完
 *       （迟到的整组建票留下 matched 孤儿票）。所以再扣掉一个刻度，放弃必定在截止之前触发；剩余不足一个刻度（扣完不到 1 ms）就不发，
 *       以依赖故障失败（「没有发出」）。<b>不直接取剩余预算</b>：同一个超时也是等响应的超时，剩余还多时照旧按配置值（2 s）判响应超时，
 *       调用方才有余下的预算做补偿——157 建票结局不明时的回滚（按票号 CAS 撤掉可能已建成的票）用的是同一个截止，等响应拖到截止就再也发不出去。</li>
 *   <li><b>NOSCRIPT 自己处理</b>（{@link #evalAsync}）：Redisson 缺省开着脚本缓存，{@code evalAsync} 遇 {@code NOSCRIPT} 会自己 {@code SCRIPT LOAD}
 *       再发一条 {@code EVALSHA}，两步各用一份完整超时、中间不看请求截止，真正执行写的那一条最坏在截止之后才发出；偏偏 Redis 重启后连接重建、
 *       脚本缓存清空，正是上一条要封住的「正在重连」场景。所以客户端写直接发 {@code EVALSHA}，遇 {@code NOSCRIPT} 先查截止、加载之后再查一次，
 *       都没过才重发，每次发出各自按上一条现取超时。</li>
 * </ul>
 * 截止已过时各存储根本不发（它们的 await 先查截止，{@link #evalAsync} 再守一道、连同不足一个刻度的剩余）。剩下的窗口：
 * <ul>
 *   <li>与 Go 版相同（§6.1「截止前已经发出的那一条撤不回来」）：已经写进连接的那一条在 Redis 卡顿时可能在回包之后才执行，
 *       靠脚本的按票号 / 按值 CAS、票据 TTL 与 153 权威查询兜底。</li>
 *   <li>经 Dubbo 转进来的调用（211 整队开战）：match 的截止从<b>自己收到</b>起算，比调用方按同一预算放弃的时刻晚一个单程时延加分派；
 *       调用方放弃即回包、gate 即放行，这一段本类封不住，要调用方在预算附件里扣掉时延余量（game-team 侧）。</li>
 *   <li>时间轮线程被 GC 停顿或调度延迟拖住时，放弃仍可能晚于截止；刻度上界只覆盖时间轮自身的粒度。</li>
 * </ul>
 *
 * <p>补偿与后台路径（回滚、清理、凑单、gather）不走这里：它们按条件 / 按值写、可重放，重发只会更稳，也不受请求截止约束。
 */
public final class RedisClientWrites {

    /**
     * {@code game.redis.timeout-ms} 的缺省值（取自 {@link RedisProperties} 的缺省，不另写一份）。只给不经 Spring 装配、直接 new 存储的地方
     * （单测与集成测试）用；生产装配一律经 {@link #commandTimeout(RedisProperties)} 传配置值。
     */
    public static final Duration DEFAULT_COMMAND_TIMEOUT = commandTimeout(new RedisProperties(null, null, null, null, null, null, null));

    /**
     * Redisson 时间轮刻度的上界（100 ms）：不重发时「等连接」的放弃由这个时间轮触发，到点最多晚一个刻度。Redisson 3.50
     * {@code ServiceManager.initTimer} 按 x = min(retryDelay 的首次延迟, timeout) 推刻度：x 非正取 10 ms，非整百取 max(10, (x % 100) / 2)，
     * 恰为 100 取 50 ms，其余整百取 100 ms——任何配置下都不超过 100 ms，所以取上界而不读配置。升级 Redisson 时要重核这条推导。
     */
    static final long TIMER_TICK_BOUND_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    private RedisClientWrites() {
    }

    /** 配置里单条命令等响应的超时（{@code game.redis.timeout-ms}，{@link RedisProperties} 已校验为正）。 */
    public static Duration commandTimeout(RedisProperties props) {
        return Duration.ofMillis(Objects.requireNonNull(props, "redisProperties").timeoutMs());
    }

    /** 存储构造时校验注入的命令超时：必须为正（{@code OptionalOptions.timeout} 取 0 会退回全局值，等于没封顶）。 */
    public static Duration requireCommandTimeout(Duration commandTimeout) {
        Objects.requireNonNull(commandTimeout, "commandTimeout");
        if (commandTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("客户端写的命令超时必须 ≥ 1 ms: " + commandTimeout);
        }
        return commandTimeout;
    }

    /**
     * 按客户端写发出一段脚本（{@link RScript.Mode#READ_WRITE}），返回它的结局；调用方照旧用 {@link Deadline#await} 在截止内等。
     *
     * <ol>
     *   <li>直接发 {@code EVALSHA}（本地按脚本正文算 SHA-1，与 Redis 的算法相同），不走 {@code evalAsync} 的自动重载（理由见类注释）。</li>
     *   <li>遇 {@code NOSCRIPT}（Redis 重启、主从切换、{@code SCRIPT FLUSH} 之后）先查截止：过了就以依赖故障失败——NOSCRIPT 时脚本没有执行，
     *       此时放弃是「没有提交」；没过才 {@code SCRIPT LOAD} 到执行这条写的节点（按第一个键路由，同 {@code EVALSHA}）。
     *       {@code SCRIPT LOAD} 不改业务数据，同样不重发、不越过截止。</li>
     *   <li>加载完再查截止，没过才重发一次 {@code EVALSHA}；重发又遇 {@code NOSCRIPT}（加载之后恰好又切主）就此失败，不打转。</li>
     * </ol>
     * 三次发出各自现取句柄：编解码 {@code codec}、不重发、Redisson 超时 = min({@code commandTimeout}, 那一刻的剩余 − 一个定时器刻度)。
     * {@code NOSCRIPT} 以外的错误原样失败，不加载、不再发。截止检查只管到「交给驱动」为止，已经写进连接的那一条见类注释的剩余窗口。
     *
     * <p>截止已过、或剩余扣掉一个定时器刻度后不足 1 ms 时什么都不发，返回的 future 以依赖故障失败（三处检查同一条规则）。第一次发出时同步抛出的异常
     * （客户端已关闭等）原样抛给调用方，由各存储按「发不出去」处理；之后的失败都在返回的 future 上。
     *
     * @param commandTimeout 配置的单条命令超时（{@link #commandTimeout(RedisProperties)}），必须为正
     * @param keys           脚本的 KEYS；第一个键决定发到哪个节点
     */
    public static <R> CompletableFuture<R> evalAsync(RedissonClient redis, Codec codec, Deadline d, Duration commandTimeout, String lua,
                                                     RScript.ReturnType type, List<Object> keys, Object... args) {
        return evalAsync(System::nanoTime, redis, codec, d, commandTimeout, lua, type, keys, args);
    }

    /**
     * 同 {@link #evalAsync(RedissonClient, Codec, Deadline, Duration, String, RScript.ReturnType, List, Object...)}；单调时钟可注入，
     * 测试借它钉住 NOSCRIPT 之后的两次截止检查与现取的超时。生产取 {@link System#nanoTime}，与 {@link Deadline} 同一个钟。
     */
    static <R> CompletableFuture<R> evalAsync(LongSupplier nanoClock, RedissonClient redis, Codec codec, Deadline d, Duration commandTimeout,
                                              String lua, RScript.ReturnType type, List<Object> keys, Object... args) {
        Objects.requireNonNull(nanoClock, "nanoClock");
        Objects.requireNonNull(redis, "redis");
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(d, "deadline");
        requireCommandTimeout(commandTimeout);
        Objects.requireNonNull(lua, "lua");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(keys, "keys");
        // 先读钟、后取剩余：换算出的截止只会比 d 的截止早一点（安全的一侧），之后的检查都按这一个时刻
        long now = nanoClock.getAsLong();
        Sender sender = new Sender(nanoClock, now + d.remainingNanos(), codec, commandTimeout);
        String sha = sha1Hex(lua);
        CompletableFuture<R> first = sender.<R>beforeDeadline("客户端写之前请求预算已用完或不足一个定时器刻度（没有发出）", null,
                options -> redis.getScript(options).<R>evalShaAsync(RScript.Mode.READ_WRITE, sha, type, keys, args));
        return first.exceptionallyCompose(error -> {
            Throwable cause = unwrap(error);
            if (!(cause instanceof RedisNoScriptException)) {
                return CompletableFuture.<R>failedFuture(cause);
            }
            return sender.<String>beforeDeadline("客户端写遇到 NOSCRIPT 时请求预算已用完或不足一个定时器刻度（没有重新加载、没有再发出）", cause,
                            options -> load(redis.getScript(options), lua, keys))
                    .thenCompose(loaded -> sender.<R>beforeDeadline("客户端写重新加载脚本之后请求预算已用完或不足一个定时器刻度（没有再发出）", cause,
                            options -> redis.getScript(options).<R>evalShaAsync(RScript.Mode.READ_WRITE, sha, type, keys, args)));
        });
    }

    /** 一次客户端写的截止（按 {@code nanoClock} 计）与句柄参数：每次发出前现查截止、现取超时。 */
    private record Sender(LongSupplier nanoClock, long untilNanos, Codec codec, Duration commandTimeout) {

        /**
         * 剩余扣掉一个定时器刻度后还有至少 1 ms 才调用 {@code send}，交给它此刻的句柄参数；否则不调用、以依赖故障失败（{@code cause} 可为 null）。
         * 扣刻度是为了让 Redisson 放弃等连接的时刻必定落在截止之前（见类注释）；不足 1 ms 时不再兜成 1 ms——那会让放弃晚于截止。
         */
        <T> CompletableFuture<T> beforeDeadline(String expiredMessage, Throwable cause, Function<OptionalOptions, ? extends CompletionStage<T>> send) {
            long leftNanos = untilNanos - nanoClock.getAsLong();
            long timeoutMs = Math.min(commandTimeout.toMillis(), TimeUnit.NANOSECONDS.toMillis(leftNanos - TIMER_TICK_BOUND_NANOS));
            if (timeoutMs < 1) {
                return CompletableFuture.failedFuture(new Deadline.DependencyException(expiredMessage, cause));
            }
            CompletionStage<T> stage = send.apply(options(codec, timeoutMs));
            if (stage == null) {
                return CompletableFuture.failedFuture(new Deadline.DependencyException("客户端写没有拿到 future"));
            }
            return stage.toCompletableFuture();
        }
    }

    /** 句柄参数：{@code codec}、不重发、Redisson 超时 = {@code timeoutMs}（调用方保证 ≥ 1 ms：0 会退回全局值）。 */
    private static OptionalOptions options(Codec codec, long timeoutMs) {
        return OptionalOptions.defaults().codec(codec).retryAttempts(0).timeout(Duration.ofMillis(timeoutMs));
    }

    /** 加载到执行这条写的那个节点（按第一个键路由，同 EVALSHA）。调用方的键都是 {@code RedisKeys} 生成的 {@code String}。 */
    private static CompletionStage<String> load(RScript handle, String lua, List<Object> keys) {
        if (keys.isEmpty()) {
            return handle.scriptLoadAsync(lua);
        }
        return handle.scriptLoadAsync(String.valueOf(keys.get(0)), lua);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /** 脚本的 SHA-1（UTF-8 字节、小写十六进制）：Redis 脚本缓存的键，与 SCRIPT LOAD 的返回值相同。每次现算（微秒级），免得为它维护一张缓存表。 */
    private static String sha1Hex(String lua) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(lua.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 必须提供 SHA-1", e); // 每个 JDK 都必须提供 SHA-1，走不到
        }
    }
}
