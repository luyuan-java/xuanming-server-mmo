package com.game.data.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.data.ops.OpsException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 回档请求的校验与规范化（§4.3、§4.5；T-R2 的入参部分）。 */
class RollbackRequestTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final Duration AGE = Duration.ofMinutes(5);

    private static RollbackRequest.Body body(String scope, List<String> players, List<Long> zones, Boolean all,
                                             String snapshot, Long target, List<String> sections, String ifOnline) {
        return new RollbackRequest.Body(scope, players, zones, all, snapshot, target, sections, ifOnline, null, null,
                "客诉", null);
    }

    private static void bad(RollbackRequest.Body body, String fragment) {
        assertThatThrownBy(() -> RollbackRequest.parse(body, NOW, AGE)).isInstanceOfSatisfying(OpsException.class, e -> {
            assertThat(e.code()).isEqualTo("invalid_request");
            assertThat(e.getMessage()).contains(fragment);
        });
    }

    @Test
    void 单人按号_玩家去重升序_缺省reject_FULL() {
        RollbackRequest r = RollbackRequest.parse(body("players", List.of("7"), null, null, "99", null, null, null), NOW, AGE);
        assertThat(r.scope()).isEqualTo(RollbackRequest.Scope.PLAYERS);
        assertThat(r.players()).containsExactly(7L);
        assertThat(r.snapshotId()).isEqualTo(99L);
        assertThat(r.ifOnline()).isEqualTo(RollbackRequest.IfOnline.REJECT);
        assertThat(r.full()).isTrue();
        assertThat(r.dryRun()).isFalse();

        RollbackRequest many = RollbackRequest.parse(body("players", List.of("3", "18446744073709551615", "3", "2"), null,
                null, null, NOW - AGE.toMillis(), null, "kick"), NOW, AGE);
        assertThat(many.players()).containsExactly(2L, 3L, -1L);
        assertThat(many.ifOnline()).isEqualTo(RollbackRequest.IfOnline.KICK);
    }

    @Test
    void 入参非法一律400() {
        bad(body("all", List.of("1"), null, null, "9", null, null, null), "scope");
        bad(body("players", List.of("1"), null, null, "9", NOW - 600_000L, null, null), "二选一");
        bad(body("players", List.of("1"), null, null, null, null, null, null), "二选一");
        bad(body("players", List.of("1", "2"), null, null, "9", null, null, null), "单个玩家");
        bad(body("players", List.of(), null, null, null, NOW - 600_000L, null, null), "1–100");
        bad(body("players", List.of("0"), null, null, null, NOW - 600_000L, null, null), "不得含 0");
        bad(body("players", List.of("1"), null, null, null, NOW - 1000L, null, null), "至少要早于现在");
        bad(body("players", List.of("1"), null, null, "9", null, null, "maybe"), "ifOnline");
        bad(body("players", java.util.stream.LongStream.rangeClosed(1, 101).mapToObj(Long::toString).toList(), null, null,
                null, NOW - 600_000L, null, null), "1–100");
        bad(new RollbackRequest.Body("players", List.of("1"), null, null, "9", null, null, null, null, null, " ", null),
                "reason");
    }

    @Test
    void 段名规则_空列表400_拆开资产组400_mission要带assets() {
        bad(body("players", List.of("1"), null, null, "9", null, List.of(), null), "不能为空");
        bad(body("players", List.of("1"), null, null, "9", null, List.of("currency"), null), "assets");
        bad(body("players", List.of("1"), null, null, "9", null, List.of("bag", "level"), null), "assets");
        bad(body("players", List.of("1"), null, null, "9", null, List.of("mission"), null), "mission 必须与 assets");
        bad(body("players", List.of("1"), null, null, "9", null, List.of("skills"), null), "未知的段名");
        RollbackRequest r = RollbackRequest.parse(body("players", List.of("1"), null, null, "9", null,
                List.of("Mission", "assets", "level"), null), NOW, AGE);
        assertThat(r.sections()).containsExactlyInAnyOrder(RollbackSection.MISSION, RollbackSection.ASSETS,
                RollbackSection.LEVEL);
        assertThat(RollbackSection.restoresAssets(r.sections())).isTrue();
        assertThat(RollbackSection.restoresAssets(RollbackSection.parse(List.of("position")))).isFalse();
    }

    @Test
    void 整区_只能按时刻_强制kick_zones与allZones二选一() {
        RollbackRequest r = RollbackRequest.parse(body("zones", null, List.of(3L, 1L, 3L), null, null, NOW - 600_000L, null,
                null), NOW, AGE);
        assertThat(r.zones()).containsExactly(1, 3);
        assertThat(r.ifOnline()).isEqualTo(RollbackRequest.IfOnline.KICK);
        RollbackRequest all = RollbackRequest.parse(body("zones", null, null, true, null, NOW - 600_000L, null, "kick"), NOW,
                AGE);
        assertThat(all.allZones()).isTrue();
        assertThat(all.canonical()).contains("zones=all");

        bad(body("zones", null, List.of(1L), true, null, NOW - 600_000L, null, null), "二选一");
        bad(body("zones", null, null, null, null, NOW - 600_000L, null, null), "二选一");
        bad(body("zones", null, List.of(1L), null, "9", null, null, null), "按时刻");
        bad(body("zones", null, List.of(1L), null, null, NOW - 600_000L, null, "reject"), "一律 kick");
        bad(body("zones", List.of("1"), List.of(1L), null, null, NOW - 600_000L, null, null), "不带 players");
        bad(body("zones", null, List.of(0L), null, null, NOW - 600_000L, null, null), "区号");
    }

    @Test
    void 规范化串_同一语义同一指纹_改任何参数都不同() {
        RollbackRequest a = RollbackRequest.parse(body("players", List.of("2", "1"), null, null, null, NOW - 600_000L,
                List.of("assets", "level"), null), NOW, AGE);
        RollbackRequest b = RollbackRequest.parse(body("players", List.of("1", "2", "2"), null, null, null, NOW - 600_000L,
                List.of("level", "assets"), "reject"), NOW, AGE);
        assertThat(a.canonical()).isEqualTo(b.canonical());
        RollbackRequest c = RollbackRequest.parse(body("players", List.of("1", "2"), null, null, null, NOW - 600_000L,
                List.of("level", "assets"), "kick"), NOW, AGE);
        assertThat(c.canonical()).isNotEqualTo(a.canonical());
    }
}
