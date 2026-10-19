package com.game.data.tools.playerbundle;

import java.util.List;
import java.util.Map;

/**
 * 排障工具对 Redis 的全部访问（导出只读、导入只删本玩家的几把键）。抽成接口是为了单测注入替身；生产实现是 {@link RedissonPlayerRedis}。
 */
interface PlayerRedis extends AutoCloseable {

    /**
     * 逐把键读出类型、TTL 与值。每把键在一次原子操作里读（类型、TTL、值出自同一时刻）；键与键之间不保证同一时刻
     * （各键 hash tag 不同，集群部署下一条命令带不了跨槽的键）。读失败的键在 {@link RawEntry#error} 里给出原因，不抛异常。
     */
    List<RawEntry> read(List<String> keys);

    /** {@code INFO server} 的键值（取 run_id 作实例身份）。 */
    Map<String, String> infoServer();

    /** 删除这些键（导入第二段：清掉本地同号玩家残留的、会影响加载链路的状态）；返回实际删掉的个数。 */
    long delete(List<String> keys);

    @Override
    void close();

    /**
     * 一把键的原始读数。
     *
     * @param type   Redis TYPE（{@code none} = 不存在）
     * @param pttlMs -1 = 无 TTL，-2 = 不存在
     * @param string string 类型的原字节
     * @param hash   hash 类型的全部字段（字段名按 UTF-8，值原字节）
     * @param zset   zset 类型的成员与分数（按分数升序；至多 {@link RedissonPlayerRedis#ZSET_LIMIT} 个）
     */
    record RawEntry(String key, String type, long pttlMs, byte[] string, Map<String, byte[]> hash, List<ZMember> zset,
                    String error) {

        static RawEntry failed(String key, String error) {
            return new RawEntry(key, null, -2, null, null, null, error);
        }
    }

    /** 有序集合的一个成员。 */
    record ZMember(String member, double score) {
    }
}
