package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** 覆盖 L625：AOI 战斗交手关注、10 s 脱战与死亡撤销、满表挤占与名额空出补回附近人、NPC 仇恨名单（≤ 40）、压力裁表与指标。 */
class AoiCombatAndLoadTest {

    @Test
    void 战斗交手挤占满表_脱战10秒或死亡立即撤销并补回附近候选() {
        ViewIndex view = new ViewIndex(1);
        CombatEngagementTracker tracker = new CombatEngagementTracker();
        ScenePlayer observer = ViewIndexTest.player(1, 0, 0, 0);
        ScenePlayer nearbyNpc = ViewIndexTest.player(2, 5, 0, 0);
        ScenePlayer farEnemy = ViewIndexTest.player(3, 500, 0, 0);
        view.enter(observer);
        view.enter(nearbyNpc);
        view.enter(farEnemy);
        assertThat(view.watching(observer)).containsExactly(nearbyNpc);

        // 远距离交手：战斗权重 100 > 普通空间权重 10，挤掉 nearbyNpc，不看距离
        ViewChanges changes = new ViewChanges();
        tracker.onDamage(farEnemy, observer, false, 1_000L, view, changes);
        assertThat(view.watching(observer)).containsExactly(farEnemy);
        assertThat(view.watching(farEnemy)).containsExactly(observer);
        assertThat(changes.of(observer).removed()).containsExactly(nearbyNpc);
        assertThat(changes.of(observer).added()).containsExactly(farEnemy);

        // 未满 10 s 仍保持战斗关注
        ViewChanges mid = new ViewChanges();
        tracker.tickDecay(10_999L, view, mid);
        assertThat(view.watching(observer)).containsExactly(farEnemy);

        // 满 10 s 脱战撤销：名额空出后无需移动即补回 nearbyNpc
        ViewChanges decayed = new ViewChanges();
        tracker.tickDecay(11_000L, view, decayed);
        assertThat(view.watching(observer)).containsExactly(nearbyNpc);
        assertThat(decayed.of(observer).removed()).containsExactly(farEnemy);
        assertThat(decayed.of(observer).added()).containsExactly(nearbyNpc);

        // 再次交手后目标死亡：立即撤销并补回 nearbyNpc
        tracker.onDamage(farEnemy, observer, false, 20_000L, view, new ViewChanges());
        assertThat(view.watching(observer)).containsExactly(farEnemy);
        ViewChanges deathChanges = new ViewChanges();
        tracker.onDamage(observer, farEnemy, true, 20_500L, view, deathChanges);
        assertThat(view.watching(observer)).containsExactly(nearbyNpc);
        assertThat(tracker.activeEngagementCount()).isZero();
    }

    @Test
    void NPC仇恨名单最多40人两两互关_治疗打怪者并入名单_玩家混战不传播() {
        ViewIndex view = new ViewIndex(100);
        CombatEngagementTracker tracker = new CombatEngagementTracker();
        ScenePlayer[] raiders = new ScenePlayer[42];
        for (int i = 0; i < raiders.length; i++) {
            raiders[i] = ViewIndexTest.player(100 + i, 500 + i * 200, 0, 0);
            view.enter(raiders[i]);
        }
        long npcId = 9001L;
        // 前 40 人进入同一 NPC 仇恨名单，两两互相建立跨网格关注；第 41 人被上限 40 拒入
        for (int i = 0; i < 41; i++) {
            tracker.onNpcHostileEngagement(npcId, raiders[i], 1_000L, view, new ViewChanges());
        }
        assertThat(tracker.npcHateRosterSize(npcId)).isEqualTo(CombatEngagementTracker.MAX_NPC_HATE_ROSTER);
        assertThat(view.watching(raiders[0])).contains(raiders[39]).doesNotContain(raiders[40]);

        // 另一个 NPC（只有 2 人）：治疗者给正在打该 NPC 的人回血（actualHeal > 0），治疗者并入名单与另一打怪者互关
        long npc2 = 9002L;
        ScenePlayer tank = ViewIndexTest.player(201, 10_000, 0, 0);
        ScenePlayer dps = ViewIndexTest.player(202, 10_300, 0, 0);
        ScenePlayer healer = ViewIndexTest.player(203, 10_600, 0, 0);
        view.enter(tank);
        view.enter(dps);
        view.enter(healer);
        tracker.onNpcHostileEngagement(npc2, tank, 2_000L, view, new ViewChanges());
        tracker.onNpcHostileEngagement(npc2, dps, 2_000L, view, new ViewChanges());
        // 0 治疗量不触发
        tracker.onHeal(healer, tank, 0L, 2_100L, view, new ViewChanges());
        assertThat(view.watching(healer)).isEmpty();
        // 有效治疗：healer 并入 npc2 名单，与 tank 和 dps 两两互关
        tracker.onHeal(healer, tank, 150L, 2_200L, view, new ViewChanges());
        assertThat(view.watching(healer)).containsExactlyInAnyOrder(tank, dps);
        assertThat(view.watching(dps)).contains(healer);
    }

    @Test
    void 服务器压力裁表按权重低优先与先远后近裁剪_回升补回并暴露三项指标() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AoiLoadMonitor monitor = new AoiLoadMonitor(10, 1.0, meters);
        assertThat(meters.get("scene_aoi_load_ema_ratio").gauge().value()).isZero();
        assertThat(meters.get("scene_aoi_load_pressure_level").gauge().value()).isZero();
        assertThat(meters.get("scene_aoi_load_effective_capacity").gauge().value()).isEqualTo(10.0);

        // 防抖：单次高载样本不立即变档，连续两次达标才切到 10 档（容量降至 max(1, 10/5) = 2）
        assertThat(monitor.recordStep(50.0, 50.0, 1_000L)).isFalse();
        assertThat(monitor.recordStep(50.0, 50.0, 1_050L)).isTrue();
        assertThat(monitor.pressureLevel()).isEqualTo(10);
        assertThat(monitor.effectiveCapacity()).isEqualTo(2);
        assertThat(meters.get("scene_aoi_load_pressure_level").gauge().value()).isEqualTo(10.0);
        assertThat(meters.get("scene_aoi_load_effective_capacity").gauge().value()).isEqualTo(2.0);

        // 1 秒内不重复变档（限频）
        assertThat(monitor.recordStep(10.0, 50.0, 1_200L)).isFalse();
        assertThat(monitor.recordStep(10.0, 50.0, 1_500L)).isFalse();
        assertThat(monitor.pressureLevel()).isEqualTo(10);
        // 超过 1 秒且恢复到 0.5 以下：回到 0 档满容 10
        assertThat(monitor.recordStep(10.0, 50.0, 2_100L)).isTrue();
        assertThat(monitor.pressureLevel()).isZero();
        assertThat(monitor.effectiveCapacity()).isEqualTo(10);

        // 验证 ViewIndex 按「权重从低到高、同权重先远后近」裁表并在回升后补回
        ViewIndex view = new ViewIndex(10);
        ScenePlayer obs = ViewIndexTest.player(1, 0, 0, 0);
        ScenePlayer nearNormal = ViewIndexTest.player(2, 5, 0, 0);
        ScenePlayer farNormal = ViewIndexTest.player(3, 40, 0, 0);
        ScenePlayer combatTarget = ViewIndexTest.player(4, 45, 0, 0);
        view.enter(obs);
        view.enter(nearNormal);
        view.enter(farNormal);
        view.enter(combatTarget);
        view.addExplicitInterest(obs, combatTarget,
                new ViewIndex.ExplicitSource(ViewIndex.KIND_COMBAT, 1L, ViewIndex.WEIGHT_COMBAT, false),
                new ViewChanges());
        assertThat(view.watching(obs)).containsExactlyInAnyOrder(nearNormal, farNormal, combatTarget);

        // 收缩到容量 2：先淘汰权重最低(10)且最远(40m)的 farNormal，保留 combatTarget(100) 与 nearNormal(5m)
        ViewChanges trim = new ViewChanges();
        view.setEffectiveCapacity(2, trim);
        assertThat(view.watching(obs)).containsExactlyInAnyOrder(nearNormal, combatTarget);
        assertThat(trim.of(obs).removed()).containsExactly(farNormal);

        // 容量回升到 10：自动补回 farNormal
        ViewChanges recover = new ViewChanges();
        view.setEffectiveCapacity(10, recover);
        assertThat(view.watching(obs)).containsExactlyInAnyOrder(nearNormal, farNormal, combatTarget);
        assertThat(recover.of(obs).added()).containsExactly(farNormal);
    }
}
