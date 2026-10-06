package com.game.discovery.battle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 锁 TTL 的纯函数 {@link BattleRedis#lockTtlSec}（基线 {@code LockTtlSecFor}，{@code pb.cpp:229-234}；scene-battle-spec §7.2 键表 TTL 列、§13.1）：
 * {@code max(0, 有效期限 − now) / 1000}（整数截断）+ 60。
 */
class LockTtlTest {

    private static final long NOW = 1_760_000_000_000L;

    @Test
    void 期限已过_只剩余量60秒() {
        assertThat(BattleRedis.lockTtlSec(NOW - 1, NOW)).isEqualTo(60);
        assertThat(BattleRedis.lockTtlSec(NOW - 3_600_000, NOW)).isEqualTo(60);
        assertThat(BattleRedis.lockTtlSec(0, NOW)).isEqualTo(60);
    }

    @Test
    void 期限恰好是现在_60秒() {
        assertThat(BattleRedis.lockTtlSec(NOW, NOW)).isEqualTo(60);
    }

    @Test
    void 剩余毫秒按整秒截断_不四舍五入() {
        assertThat(BattleRedis.lockTtlSec(NOW + 1, NOW)).isEqualTo(60);
        assertThat(BattleRedis.lockTtlSec(NOW + 999, NOW)).isEqualTo(60);
        assertThat(BattleRedis.lockTtlSec(NOW + 1000, NOW)).isEqualTo(61);
        assertThat(BattleRedis.lockTtlSec(NOW + 1999, NOW)).isEqualTo(61);
        assertThat(BattleRedis.lockTtlSec(NOW + 2000, NOW)).isEqualTo(62);
    }

    @Test
    void 备战期限96秒_锁TTL156秒() {
        // node-spec §10.4：备战期限最长 96 s，锁按 96 + 60 = 156 s 过期，确认补发窗口 180 s 盖得住
        assertThat(BattleRedis.lockTtlSec(NOW + 96_000, NOW)).isEqualTo(156);
    }

    @Test
    void 余量就是LOCK_EXTRA_TTL_SEC() {
        assertThat(BattleRedis.lockTtlSec(NOW, NOW)).isEqualTo(BattleRedis.LOCK_EXTRA_TTL_SEC);
        assertThat(BattleRedis.lockTtlSec(NOW + 30_000, NOW)).isEqualTo(30 + BattleRedis.LOCK_EXTRA_TTL_SEC);
    }

    @Test
    void 结果永远不小于余量_EXPIRE不会收到非正数() {
        // EXPIRE 0 / 负数会把键直接删掉：备战写锁回 "0" 而锁其实没了。任何输入下 TTL 都 ≥ 60
        for (long deadline : new long[] {Long.MIN_VALUE, -1L, 0L, 1L, NOW - 1, NOW, NOW + 1}) {
            assertThat(BattleRedis.lockTtlSec(deadline, NOW)).as("deadline=%d", deadline).isGreaterThanOrEqualTo(60);
        }
    }
}
