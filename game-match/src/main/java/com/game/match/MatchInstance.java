package com.game.match;

/**
 * 本进程实例的标识（每次启动随机生成一个 UUID）。两处用同一个值：发号租约的持有者、凑单锁的持有者（按持有者释放）。只进 Redis 与日志，不进指标标签。
 *
 * @param id 非空
 */
public record MatchInstance(String id) {

    public MatchInstance {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("实例标识不能为空");
        }
    }
}
