package com.game.inbox.rules;

/**
 * 收件箱三张表的 shard 列（mmorpg {@code docs/design/player-inbox-service.md} §4.2；PARITY 收件箱行第 ④ 条）：
 * {@code shard = mix64(player_id) % 64}，{@code mix64} 是 splitmix64 的终结函数，按无符号 64 位取模。
 *
 * <p><b>这是建表契约，也是全服务唯一的计算入口</b>：SQL 里一律传 {@link #rowShard} 的结果，不在别处取模。为什么先混合再取模：存量 / 回退的
 * bwmarrin SnowFlake 低 9 位是 step，同一毫秒只建一个角色时 step = 0，直接 {@code % 64} 会把这批玩家全压进 shard 0；Java 版的
 * player_id 来自自己的雪花号源，位布局同样不该影响分布。mix64 与位布局无关。上线之后再改公式等于迁移全部主键。
 *
 * <p>与 mmorpg Go {@code inbox.RowShard} 逐值相同（{@code InboxShardsTest} 用同一组金样钉住）。线程安全（纯函数）。
 */
public final class InboxShards {

    /** 模数（建表契约，与 mmorpg {@code inbox.ShardCount} 同值；改它等于改全部存量行的主键）。 */
    public static final int SHARD_COUNT = 64;

    private InboxShards() {
    }

    /** player_id 所在的分片：mix64(player_id) 按无符号取模 64。 */
    public static int rowShard(long playerId) {
        return (int) Long.remainderUnsigned(mix64(playerId), SHARD_COUNT);
    }

    /**
     * splitmix64 的终结函数（Steele、Lea、Flood 2014；Stafford Mix13 参数）：两轮「无符号右移异或 + 乘奇数常量」，是 64 位上的双射，mix64(0) = 0。
     * Java 的 long 乘法按 2^64 回绕，与 Go uint64 逐位相同。
     */
    public static long mix64(long x) {
        x ^= x >>> 30;
        x *= 0xbf58476d1ce4e5b9L;
        x ^= x >>> 27;
        x *= 0x94d049bb133111ebL;
        x ^= x >>> 31;
        return x;
    }
}
