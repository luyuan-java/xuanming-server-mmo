package com.game.team.store;

import com.game.common.deadline.Deadline;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * {@link TeamStore} 需要的 Redis 操作（生产实现 {@link RedissonTeamRedis}；单测可换成假实现制造故障或记录调用）。
 *
 * <p>实现必须：参数与回复走字节（pb 是任意字节，不能经 UTF-8）；{@link TeamScript#writes()} 为 true 的脚本以 READ_WRITE 执行；
 * 异步返回、不阻塞调用线程，失败以异常完成的 stage 表达（不同步抛出）。
 */
public interface TeamRedis {

    /**
     * 执行一段组队脚本。
     *
     * @param keys 键（{@code RedisKeys#team*} 生成的字符串）
     * @param args 参数（任意字节；数字一律 ASCII 十进制）
     * @return {@link TeamScript#multi()} 为 true 时是 {@code List<Object>}（整数 {@code Long}、字符串 {@code byte[]}），否则是 {@code Long}
     */
    CompletionStage<Object> eval(TeamScript script, List<Object> keys, List<byte[]> args);

    /**
     * 执行一段<b>写</b>脚本（{@link TeamStore} 的全部写都走这里，返回值同 {@link #eval}）：驱动在响应超时后不得原样重发，
     * 等连接、NOSCRIPT 之后的重新加载与再发出也都不得越过 {@code deadline}。截止已过时 {@link TeamStore} 根本不调到这里。
     *
     * <p>为什么（客户端请求按会话保序，mmorpg docs/design/client-request-ordering.md §6.1）：gate 对同一会话的后端请求单飞，本次调用有了结局
     * （应答、调用失败，或 gate 的 5 s 调用超时）就放行下一条。全局 Redisson 配置 {@code game.redis.retry-attempts = 1} 会在 2 s 响应超时后隔 200 ms
     * 原样重发同一段 EVAL，截止前发出的写最晚约 2.2 s 之后才落地，正好落在下一条之后（「入队 → 退队」执行成「退队（不在队、回成功）→ 入队」）。
     *
     * <p>缺省实现退回 {@link #eval}，只给不经驱动的测试替身用；生产实现（{@link RedissonTeamRedis}）必须覆盖。包装真实现的装饰器（指标、追踪、
     * 测试夹具的拦截）也必须转发本方法，只覆盖 {@link #eval} 的话写会悄悄退回带重发的全局句柄——现有的服务级真 Redis 夹具
     * （{@code TeamServiceFixture.intercepting}、{@code TeamMatchFixture.counting}）就属于这种情况，写句柄只由 TeamStore 级集成测试覆盖。
     *
     * @param deadline 这次写的截止（请求预算，或 EndMatch 每轮的独立预算）
     */
    default CompletionStage<Object> evalWrite(TeamScript script, List<Object> keys, List<byte[]> args, Deadline deadline) {
        return eval(script, keys, args);
    }

    /** {@code HGET key field}；键或字段不存在为 null。 */
    CompletionStage<byte[]> hget(String key, String field);
}
