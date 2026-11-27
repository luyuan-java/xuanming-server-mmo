package com.game.match.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.workshard.WorkShard;
import com.game.match.global.PoolSegmentPlanner.PassPhase;
import com.game.match.global.PoolSegmentPlanner.Role;
import com.game.match.global.PoolSegmentPlanner.Segment;
import com.game.match.global.PoolSegmentPlanner.TemplateSplitConfig;
import com.game.match.global.PvpTeamMatchPlanner.MatchPlan;
import com.game.match.global.PvpTeamMatchPlanner.Unit;
import com.game.match.support.MatchModes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-05 人数档真人 PVP 计分组排（PvpTeamMatchPlanner）与 2026-11-12 全服匹配热池拆分（PoolSegmentPlanner）。 */
class PvpTeamAndHotPoolSegmentTest {

    @Test
    void 队伍分加成_车队对等曲线_分边最优化与提交点极差复核() {
        long now = 100_000L;
        long bonus = PvpTeamMatchPlanner.DEFAULT_TEAM_BONUS_CENTI; // +25 分/多一人
        long step = PvpTeamMatchPlanner.DEFAULT_PREMADE_STEP_MS;   // 10s

        // 1. 队伍分 = 成员平均分 + (size - 1) * 2500
        Unit threeStack = new Unit("u3", 3, 150_000L, now - 20_000L, List.of(1L, 2L, 3L));
        assertThat(threeStack.teamRatingCenti(bonus)).isEqualTo(155_000L);

        // 2. 3V3 车队对等：3 人车队 vs 3 名单排（diff = 2）
        //    单排仅等 5 秒 -> allowedDiff = 1 + 5000/10000 = 1 < 2 -> 拒绝成局
        Unit s1Early = new Unit("s1", 1, 154_000L, now - 5_000L, List.of(4L));
        Unit s2Early = new Unit("s2", 1, 154_500L, now - 5_000L, List.of(5L));
        Unit s3Early = new Unit("s3", 1, 155_000L, now - 5_000L, List.of(6L));
        assertThat(PvpTeamMatchPlanner.planMatch(3, threeStack, List.of(s1Early, s2Early, s3Early),
                now, bonus, step, w -> 20_000L)).isEmpty();

        //    单排均等满 10 秒 -> allowedDiff = 1 + 10000/10000 = 2 >= 2 -> 允许成局且 3 人车队不可拆保持同侧
        Unit s1Ready = new Unit("s1", 1, 154_000L, now - 10_000L, List.of(4L));
        Unit s2Ready = new Unit("s2", 1, 154_500L, now - 12_000L, List.of(5L));
        Unit s3Ready = new Unit("s3", 1, 155_000L, now - 15_000L, List.of(6L));
        MatchPlan plan = PvpTeamMatchPlanner.planMatch(3, threeStack, List.of(s1Ready, s2Ready, s3Ready),
                now, bonus, step, w -> 20_000L).orElseThrow();
        assertThat(plan.premadeDiff()).isEqualTo(2);
        var teams = plan.memberTeams();
        assertThat(teams.get(1L)).isEqualTo(teams.get(2L)).isEqualTo(teams.get(3L));
        assertThat(teams.get(4L)).isNotEqualTo(teams.get(1L));

        // 3. 2V2 车队对等 diff <= 1 立即可成（2人队 vs 2单排，diff=1，等 0 秒即成）
        Unit twoStack = new Unit("u2", 2, 150_000L, now, List.of(10L, 11L));
        Unit soloA = new Unit("sa", 1, 152_000L, now, List.of(12L));
        Unit soloB = new Unit("sb", 1, 153_000L, now, List.of(13L));
        MatchPlan p2v2 = PvpTeamMatchPlanner.planMatch(2, twoStack, List.of(soloA, soloB),
                now, bonus, step, w -> 10_000L).orElseThrow();
        assertThat(p2v2.premadeDiff()).isEqualTo(1);

        // 4. 提交点极差复核：组内极差超出最长等待者容差被拒
        Unit high = new Unit("hi", 1, 180_000L, now - 1_000L, List.of(20L));
        Unit low = new Unit("lo", 1, 150_000L, now - 5_000L, List.of(21L));
        assertThat(PvpTeamMatchPlanner.commitSpreadValid(List.of(high, low), now, bonus, w -> 10_000L)).isFalse();
        assertThat(PvpTeamMatchPlanner.commitSpreadValid(List.of(high, low), now, bonus, w -> 30_000L)).isTrue();
    }

    @Test
    void 热池拆分配置越界拒启_段枚举与三阶段选择_多副本HRW分工无重无漏_与角色指标() {
        // 1. 越界配置拒启
        assertThatThrownBy(() -> new TemplateSplitConfig(MatchModes.PVP_TEAM, 101, 2, 0, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemplateSplitConfig(MatchModes.PVP_TEAM, 101, 2, 65, 5))
                .isInstanceOf(IllegalArgumentException.class);
        TemplateSplitConfig badMerge = new TemplateSplitConfig(MatchModes.PVP_TEAM, 101, 2, 4, 30);
        assertThatThrownBy(() -> badMerge.validateAgainstCrossRegionWait(30))
                .isInstanceOf(IllegalArgumentException.class);

        // 2. 合法配置：2 个机房 × 4 子池 = 8 LOCAL_PREFERRED + 1 CROSS_REGION + 1 FALLBACK = 10 段
        TemplateSplitConfig cfg = new TemplateSplitConfig(MatchModes.PVP_TEAM, 101, 2, 4, 10);
        cfg.validateAgainstCrossRegionWait(30);
        List<Segment> all = PoolSegmentPlanner.enumerateSegments(cfg, List.of(1, 2));
        assertThat(all).hasSize(10);
        assertThat(all.stream().filter(s -> s.role() == Role.LOCAL_PREFERRED)).hasSize(8);
        assertThat(all.getFirst().sqlSubpoolPredicate("player_id")).isEqualTo("MOD(player_id,4)=0");
        assertThat(PoolSegmentPlanner.subpoolOf(9L, 4)).isEqualTo(1);

        // 3. 三阶段选择（<10s 本区子池 -> 10..29s 本区合并 -> >=30s 跨机房）
        assertThat(PoolSegmentPlanner.selectPass(5_000L, cfg, 30)).isEqualTo(PassPhase.LOCAL_SUBPOOL);
        assertThat(PoolSegmentPlanner.selectPass(10_000L, cfg, 30)).isEqualTo(PassPhase.LOCAL_MERGED);
        assertThat(PoolSegmentPlanner.selectPass(30_000L, cfg, 30)).isEqualTo(PassPhase.CROSS_REGION);

        // 4. 两副本 HRW 归属无重叠且并集覆齐全部 10 段；canary 不在成员表返回 0 段
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        WorkShard.Membership a = new WorkShard.Membership(30_000L, now::get);
        a.setSelf("match-1");
        a.updateMembers(List.of("match-1", "match-2"));
        WorkShard.Membership b = new WorkShard.Membership(30_000L, now::get);
        b.setSelf("match-2");
        b.updateMembers(List.of("match-1", "match-2"));
        WorkShard.Membership canary = new WorkShard.Membership(30_000L, now::get);
        canary.setSelf("match-canary");
        canary.updateMembers(List.of("match-1", "match-2"));

        List<Segment> ownedA = PoolSegmentPlanner.ownedSegments(all, a);
        List<Segment> ownedB = PoolSegmentPlanner.ownedSegments(all, b);
        Set<String> keysA = new HashSet<>(ownedA.stream().map(Segment::ownershipKey).toList());
        Set<String> keysB = new HashSet<>(ownedB.stream().map(Segment::ownershipKey).toList());
        assertThat(keysA).doesNotContainAnyElementsOf(keysB);
        Set<String> union = new HashSet<>(keysA);
        union.addAll(keysB);
        assertThat(union).hasSize(10);
        assertThat(PoolSegmentPlanner.ownedSegments(all, canary)).isEmpty();

        // 5. 按角色记负责段数与提交结果（含 claim_stale 正常竞争）
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            PoolSegmentPlanner.Metrics segMetrics = new PoolSegmentPlanner.Metrics(meters);
            segMetrics.recordOwnedSegments(ownedA);
            segMetrics.recordCommit(Role.LOCAL_PREFERRED, "committed");
            segMetrics.recordCommit(Role.LOCAL_PREFERRED, "claim_stale");
            assertThat(meters.get("game.match.pool.segment.commits")
                    .tag("role", "local_preferred").tag("result", "claim_stale").counter().count()).isEqualTo(1.0);
        } finally {
            meters.close();
        }
    }
}
