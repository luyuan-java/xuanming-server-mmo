package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.battle.BattleRedis;
import com.game.scene.battle.BattleFreeze.Phase;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 按锁重建冻结的取值规则（scene-battle-spec §1.2、§7.8 第 3 步；基线 {@code pb.cpp:608-637}；§13.1 FreezeFromLockTest）：
 * 纯函数 {@link BattleFreeze#fromLock}，不碰世界、不碰 Redis。
 */
class FreezeFromLockTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long BATTLE = 0x8000_0000_0000_0007L;

    private static Map<String, String> lock(String... keyValues) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            fields.put(keyValues[i], keyValues[i + 1]);
        }
        return fields;
    }

    @Test
    void 备战锁字段齐全_重建PREPARING_期限原样_有效期限看备战期限() {
        BattleFreeze freeze = BattleFreeze.fromLock(BATTLE, lock("b", Long.toUnsignedString(BATTLE), "n", "21", "s", "P",
                "d", String.valueOf(NOW + 300_000), "p", String.valueOf(NOW + 60_000)), 120, NOW);

        assertThat(freeze.battleId()).isEqualTo(BATTLE);
        assertThat(freeze.battleNodeId()).isEqualTo(21);
        assertThat(freeze.phase()).isEqualTo(Phase.PREPARING);
        assertThat(freeze.deadlineMs()).isEqualTo(NOW + 300_000);
        assertThat(freeze.prepareDeadlineMs()).isEqualTo(NOW + 60_000);
        assertThat(freeze.effectiveDeadlineMs()).as("PREPARING 的有效期限是备战期限").isEqualTo(NOW + 60_000);
    }

    @Test
    void 战斗锁字段齐全_重建FIGHTING_有效期限看战斗期限() {
        BattleFreeze freeze = BattleFreeze.fromLock(BATTLE, lock("b", Long.toUnsignedString(BATTLE), "n", "21", "s", "F",
                "d", String.valueOf(NOW + 300_000), "p", String.valueOf(NOW + 60_000)), 360, NOW);

        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(freeze.deadlineMs()).isEqualTo(NOW + 300_000);
        assertThat(freeze.prepareDeadlineMs()).as("FIGHTING 的 p 原样带着").isEqualTo(NOW + 60_000);
        assertThat(freeze.effectiveDeadlineMs()).isEqualTo(NOW + 300_000);
    }

    @Test
    void 重建出来的冻结不是本实例备战的_FIGHTING视为续期已确认_PREPARING没有_其余运行态标记为假() {
        BattleFreeze fighting = BattleFreeze.fromLock(BATTLE, lock("s", "F", "d", String.valueOf(NOW + 1)), 60, NOW);
        BattleFreeze preparing = BattleFreeze.fromLock(BATTLE, lock("s", "P", "d", String.valueOf(NOW + 1)), 60, NOW);

        assertThat(fighting.preparedHere()).as("确认 / 复核时还要推 144").isFalse();
        assertThat(preparing.preparedHere()).isFalse();
        assertThat(fighting.lockExtended()).as("锁上已是 F = 之前的确认续过期，重复确认零 Redis").isTrue();
        assertThat(preparing.lockExtended()).isFalse();
        for (BattleFreeze freeze : new BattleFreeze[] {fighting, preparing}) {
            assertThat(freeze.lockPending()).isFalse();
            assertThat(freeze.cancelRequested()).isFalse();
            assertThat(freeze.rescuing()).isFalse();
        }
    }

    @Test
    void s缺失或不认识_一律按FIGHTING() {
        String d = String.valueOf(NOW + 300_000);

        assertThat(BattleFreeze.fromLock(BATTLE, lock("b", "7", "d", d), 60, NOW).phase()).as("s 缺失").isEqualTo(Phase.FIGHTING);
        assertThat(BattleFreeze.fromLock(BATTLE, lock("s", "", "d", d), 60, NOW).phase()).as("s 为空").isEqualTo(Phase.FIGHTING);
        assertThat(BattleFreeze.fromLock(BATTLE, lock("s", "X", "d", d), 60, NOW).phase()).as("不认识的值").isEqualTo(Phase.FIGHTING);
        assertThat(BattleFreeze.fromLock(BATTLE, lock("s", "p", "d", d), 60, NOW).phase()).as("小写 p 不是 P").isEqualTo(Phase.FIGHTING);
        assertThat(BattleFreeze.fromLock(BATTLE, lock("s", BattleRedis.STATE_PREPARING, "d", d), 60, NOW).phase())
                .isEqualTo(Phase.PREPARING);
    }

    @Test
    void d缺失或为0或非法_按锁的剩余TTL反推() {
        assertThat(BattleFreeze.fromLock(BATTLE, lock("s", "F"), 95, NOW).deadlineMs()).as("d 缺失").isEqualTo(NOW + 95_000);
        assertThat(BattleFreeze.fromLock(BATTLE, lock("s", "F", "d", "0"), 95, NOW).deadlineMs()).as("d = 0").isEqualTo(NOW + 95_000);
        assertThat(BattleFreeze.fromLock(BATTLE, lock("s", "F", "d", "abc"), 95, NOW).deadlineMs()).as("d 非数字")
                .isEqualTo(NOW + 95_000);
        assertThat(BattleFreeze.fromLock(BATTLE, lock("s", "F", "d", "-5"), 95, NOW).deadlineMs()).as("d 带符号不是无符号十进制")
                .isEqualTo(NOW + 95_000);
    }

    @Test
    void d缺失且锁没有TTL或已不存在_期限取现在() {
        assertThat(BattleFreeze.fromLock(BATTLE, lock("s", "F"), -1, NOW).deadlineMs()).as("TTL = -1（永不过期）").isEqualTo(NOW);
        assertThat(BattleFreeze.fromLock(BATTLE, lock("s", "F"), -2, NOW).deadlineMs()).as("TTL = -2（键不存在）").isEqualTo(NOW);
        assertThat(BattleFreeze.fromLock(BATTLE, lock("s", "F"), 0, NOW).deadlineMs()).isEqualTo(NOW);
    }

    @Test
    void 备战锁缺p_取d_d也缺时取反推出来的那个() {
        BattleFreeze withDeadline = BattleFreeze.fromLock(BATTLE, lock("s", "P", "d", String.valueOf(NOW + 300_000)), 120, NOW);
        assertThat(withDeadline.prepareDeadlineMs()).isEqualTo(NOW + 300_000);
        assertThat(withDeadline.effectiveDeadlineMs()).isEqualTo(NOW + 300_000);

        BattleFreeze zeroP = BattleFreeze.fromLock(BATTLE, lock("s", "P", "d", String.valueOf(NOW + 300_000), "p", "0"), 120, NOW);
        assertThat(zeroP.prepareDeadlineMs()).as("p = 0 同缺失").isEqualTo(NOW + 300_000);

        BattleFreeze neither = BattleFreeze.fromLock(BATTLE, lock("s", "P"), 120, NOW);
        assertThat(neither.deadlineMs()).isEqualTo(NOW + 120_000);
        assertThat(neither.prepareDeadlineMs()).isEqualTo(NOW + 120_000);
    }

    @Test
    void 战斗锁缺p_保持0_有效期限仍是战斗期限() {
        BattleFreeze freeze = BattleFreeze.fromLock(BATTLE, lock("s", "F", "d", String.valueOf(NOW + 300_000)), 360, NOW);

        assertThat(freeze.prepareDeadlineMs()).isZero();
        assertThat(freeze.effectiveDeadlineMs()).isEqualTo(NOW + 300_000);
    }

    @Test
    void n缺失或非法_节点号为0() {
        assertThat(BattleFreeze.fromLock(BATTLE, lock("s", "F"), 60, NOW).battleNodeId()).isZero();
        assertThat(BattleFreeze.fromLock(BATTLE, lock("s", "F", "n", "x"), 60, NOW).battleNodeId()).isZero();
        assertThat(BattleFreeze.fromLock(BATTLE, lock("s", "F", "n", "4294967295"), 60, NOW).battleNodeId())
                .as("uint32 上限按位收进 int").isEqualTo(-1);
    }

    @Test
    void battle_id以调用方给的为准_不看锁里的b_期限按无符号解析() {
        BattleFreeze freeze = BattleFreeze.fromLock(BATTLE, lock("b", "999", "s", "F", "d", "18446744073709551615"), 60, NOW);

        assertThat(freeze.battleId()).isEqualTo(BATTLE);
        assertThat(Long.toUnsignedString(freeze.deadlineMs())).isEqualTo("18446744073709551615");
    }

    @Test
    void 空表_没有任何字段_按FIGHTING且期限按TTL反推() {
        BattleFreeze freeze = BattleFreeze.fromLock(BATTLE, Map.of(), 30, NOW);

        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(freeze.deadlineMs()).isEqualTo(NOW + 30_000);
        assertThat(freeze.prepareDeadlineMs()).isZero();
        assertThat(freeze.battleNodeId()).isZero();
    }
}
