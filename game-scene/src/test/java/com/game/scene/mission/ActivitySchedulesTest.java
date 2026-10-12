package com.game.scene.mission;

import static com.game.scene.mission.MissionFixtures.edit;
import static com.game.scene.mission.MissionFixtures.mission;
import static com.game.scene.mission.MissionFixtures.schedule;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.PlayerActivityStatus;
import com.game.scene.mission.ActivitySchedules.Info;
import com.game.scene.mission.MissionTables.MissionDef;
import com.game.table.ActivityScheduleTable;
import org.junit.jupiter.api.Test;

/** 活动排期（基线 player_activity_schedule_test）：未排期、窗口 [start, end) 按无符号、非法配置清零。 */
class ActivitySchedulesTest {

    private static final long START = 1_900_000_000_000L;
    private static final long END = 1_900_000_060_000L;
    private static final long U64_MAX = -1L;

    /** 合成的活动任务 15（类型 2、奖励 7）。 */
    private static MissionDef activity(ActivityScheduleTable schedule) {
        MissionFixtures.Editor editor = edit().mission(mission(15).setRewardId(7));
        if (schedule == null) {
            editor.removeSchedule(15);
        } else {
            editor.schedule(schedule);
        }
        return editor.build().mission(15);
    }

    @Test
    void 没有排期行_未排期_不带窗口_活动号与奖励照填() {
        Info info = ActivitySchedules.build(activity(null), START);
        assertThat(info).isEqualTo(new Info(0, 15, 15, 7, PlayerActivityStatus.PLAYER_ACTIVITY_UNSCHEDULED, 0, 0,
                "活动尚未排期，敬请期待"));
        assertThat(ActivitySchedules.checkOpen(activity(null), START)).isEqualTo(1006);
    }

    @Test
    void 停用的行_未排期_不露出旧窗口() {
        assertThat(ActivitySchedules.build(activity(schedule(15, false, START, END)), START + 1))
                .isEqualTo(new Info(0, 15, 15, 7, PlayerActivityStatus.PLAYER_ACTIVITY_UNSCHEDULED, 0, 0,
                        "活动尚未排期，敬请期待"));
        assertThat(ActivitySchedules.build(activity(schedule(15, false, 0, 0)), U64_MAX).status())
                .isEqualTo(PlayerActivityStatus.PLAYER_ACTIVITY_UNSCHEDULED);
    }

    @Test
    void 窗口左闭右开() {
        MissionDef def = activity(schedule(15, true, START, END));
        for (long now : new long[] {0, START - 1}) {
            assertThat(ActivitySchedules.build(def, now)).isEqualTo(new Info(0, 15, 15, 7,
                    PlayerActivityStatus.PLAYER_ACTIVITY_UPCOMING, START, END, "活动尚未开始"));
            assertThat(ActivitySchedules.checkOpen(def, now)).isEqualTo(1006);
        }
        for (long now : new long[] {START, END - 1}) {
            assertThat(ActivitySchedules.build(def, now)).isEqualTo(new Info(0, 15, 15, 7,
                    PlayerActivityStatus.PLAYER_ACTIVITY_OPEN, START, END, ""));
            assertThat(ActivitySchedules.checkOpen(def, now)).isZero();
        }
        for (long now : new long[] {END, U64_MAX}) {
            assertThat(ActivitySchedules.build(def, now)).isEqualTo(new Info(0, 15, 15, 7,
                    PlayerActivityStatus.PLAYER_ACTIVITY_ENDED, START, END, "活动已结束"));
        }
    }

    @Test
    void uint64边界按无符号比较() {
        MissionDef def = activity(schedule(15, true, U64_MAX - 10, U64_MAX));
        Info open = ActivitySchedules.build(def, U64_MAX - 1);
        assertThat(open.status()).isEqualTo(PlayerActivityStatus.PLAYER_ACTIVITY_OPEN);
        assertThat(open.startsAtMs()).isEqualTo(U64_MAX - 10);
        assertThat(open.endsAtMs()).isEqualTo(U64_MAX);
        assertThat(ActivitySchedules.build(def, U64_MAX).status()).isEqualTo(PlayerActivityStatus.PLAYER_ACTIVITY_ENDED);
        assertThat(ActivitySchedules.build(def, START).status()).isEqualTo(PlayerActivityStatus.PLAYER_ACTIVITY_UPCOMING);
    }

    @Test
    void 非法窗口_启用或停用都清零回1002() {
        Info rejected = new Info(1002, 0, 0, 0, PlayerActivityStatus.PLAYER_ACTIVITY_UNSCHEDULED, 0, 0,
                "活动配置异常，暂不可参与");
        assertThat(ActivitySchedules.build(activity(schedule(15, true, 0, 0)), START)).isEqualTo(rejected);
        for (boolean enabled : new boolean[] {true, false}) {
            for (long[] window : new long[][] {{0, 100}, {100, 0}, {100, 100}, {101, 100}}) {
                ActivityScheduleTable row = schedule(15, enabled, window[0], window[1]);
                assertThat(ActivitySchedules.windowInvalid(row)).isTrue();
                assertThat(ActivitySchedules.build(activity(row), START)).isEqualTo(rejected);
                assertThat(ActivitySchedules.checkOpen(activity(row), START)).isEqualTo(1002);
            }
        }
    }

    @Test
    void 不是活动类型或排期号不符_清零回1002() {
        Info rejected = new Info(1002, 0, 0, 0, PlayerActivityStatus.PLAYER_ACTIVITY_UNSCHEDULED, 0, 0,
                "活动配置异常，暂不可参与");
        assertThat(ActivitySchedules.build(MissionFixtures.shippedTables().mission(1), START)).isEqualTo(rejected);
        MissionDef def = activity(schedule(15, true, START, END));
        MissionDef mismatched = new MissionDef(def.id(), def.type(), def.subType(), def.conditionOrder(),
                def.autoReward(), def.rewardId(), def.slots(), def.nextMissionIds(), def.earlyTip(), def.lateTip(),
                schedule(16, true, START, END));
        assertThat(ActivitySchedules.build(mismatched, START)).isEqualTo(rejected);
    }

    @Test
    void 正式表_15到17都未排期_接取回1006() {
        MissionTables tables = MissionFixtures.shippedTables();
        for (int id : new int[] {15, 16, 17}) {
            Info info = ActivitySchedules.build(tables.mission(id), START);
            assertThat(info.status()).isEqualTo(PlayerActivityStatus.PLAYER_ACTIVITY_UNSCHEDULED);
            assertThat(info.rewardId()).isEqualTo(1);
            assertThat(ActivitySchedules.checkOpen(tables.mission(id), START)).isEqualTo(1006);
        }
    }
}
