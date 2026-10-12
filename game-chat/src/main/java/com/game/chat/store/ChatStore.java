package com.game.chat.store;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 聊天的 Redis 操作（全部单键，Cluster 安全；mmorpg go/chat chat_logic.go 的五段 Lua / 命令，语义逐条相同）。
 * 全部异步、不阻塞调用线程。
 */
public interface ChatStore {

    /** {@code SET key value NX EX ttl}：占到返回 true。 */
    CompletionStage<Boolean> claim(String key, String value, long ttlSeconds);

    /** 读字符串键；不存在为 null。 */
    CompletionStage<String> get(String key);

    /** 值仍等于 {@code expected} 时改成 {@code done} 并重置 TTL（旧请求不能完结过期后新建的占位）。 */
    CompletionStage<Boolean> markDone(String key, String expected, String done, long ttlSeconds);

    /** 值仍等于 {@code expected} 时删除（失败路径释放本次的占位）。 */
    CompletionStage<Boolean> release(String key, String expected);

    /** INCR，首次（或存量无 TTL）设过期；返回当前计数。 */
    CompletionStage<Long> incrWithTtl(String key, long ttlSeconds);

    /** LPUSH + LTRIM 0..maxEntries-1 + EXPIRE ttl（一段 Lua）。 */
    CompletionStage<Void> append(String key, byte[] message, int maxEntries, long ttlSeconds);

    /** LRANGE 0..limit-1（新在前）。 */
    CompletionStage<List<byte[]>> range(String key, int limit);
}
