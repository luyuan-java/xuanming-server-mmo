package com.game.chat.store;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 聊天的 Redis 操作（全部单键，Cluster 安全；mmorpg go/chat chat_logic.go 的五段 Lua / 命令，语义逐条相同）。
 * 全部异步、不阻塞调用线程。
 *
 * <p>带 {@code timeout} 的三个是发言请求路径上的写（占幂等键、限速计数、写历史）：实现<b>不得重发</b>，等连接与等回复都不超过
 * {@code timeout}（调用方给的是发出时的剩余预算），从调用起过了 {@code timeout} 不再发出任何命令（脚本遇 NOSCRIPT 时的加载与重发也在这之内；
 * NOSCRIPT 说明那条没有执行，重发不是重复写）。gate 对同一会话的后端请求逐条发出，chat 回包或 gate 的 5 s 调用超时之后就放行下一条；
 * 被原样重发、或截止之后才拿到连接发出的写会落在下一条之后（mmorpg client-request-ordering.md §6.1）。完结 / 释放幂等键是提交后记账与补偿，
 * 按值比对、可重放，不受请求截止约束。
 */
public interface ChatStore {

    /** {@code SET key value NX EX ttl}：占到返回 true。客户端写：不重发，超时 {@code timeout}。 */
    CompletionStage<Boolean> claim(String key, String value, long ttlSeconds, Duration timeout);

    /** 读字符串键；不存在为 null。 */
    CompletionStage<String> get(String key);

    /** 值仍等于 {@code expected} 时改成 {@code done} 并重置 TTL（旧请求不能完结过期后新建的占位）。 */
    CompletionStage<Boolean> markDone(String key, String expected, String done, long ttlSeconds);

    /** 值仍等于 {@code expected} 时删除（失败路径释放本次的占位）。 */
    CompletionStage<Boolean> release(String key, String expected);

    /** INCR，首次（或存量无 TTL）设过期；返回当前计数。客户端写：不重发，超时 {@code timeout}。 */
    CompletionStage<Long> incrWithTtl(String key, long ttlSeconds, Duration timeout);

    /** LPUSH + LTRIM 0..maxEntries-1 + EXPIRE ttl（一段 Lua）。客户端写（发言的提交点）：不重发，超时 {@code timeout}。 */
    CompletionStage<Void> append(String key, byte[] message, int maxEntries, long ttlSeconds, Duration timeout);

    /** LRANGE 0..limit-1（新在前）。 */
    CompletionStage<List<byte[]>> range(String key, int limit);
}
