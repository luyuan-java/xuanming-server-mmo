package com.game.scene.mission;

import com.game.proto.PlayerActivityStatus;
import com.game.scene.mission.MissionTables.MissionDef;
import com.game.table.ActivityScheduleTable;

/**
 * 活动排期（基线 PlayerActivityScheduleSystem）：活动 = 任务类型 2 的任务行，排期表按任务号给一个绝对的单窗口
 * {@code [start, end)}（UTC 毫秒，uint64 按无符号比较），没有日 / 周循环与重置。
 */
final class ActivitySchedules {

    static final String REASON_INVALID = "活动配置异常，暂不可参与";
    static final String REASON_UNSCHEDULED = "活动尚未排期，敬请期待";
    static final String REASON_UPCOMING = "活动尚未开始";
    static final String REASON_ENDED = "活动已结束";

    /**
     * 一个活动的排期结果（基线 BuildInfo）。配置非法时全部清零（活动号 0、状态未排期），{@code tip} 为 1002。
     * 未排期（没有排期行或未启用）时不带窗口，不把停用行的旧窗口露给客户端。
     */
    record Info(int tip, int activityId, int missionId, int rewardId, PlayerActivityStatus status, long startsAtMs,
                long endsAtMs, String reason) {

        boolean open() {
            return tip == MissionTables.OK && status == PlayerActivityStatus.PLAYER_ACTIVITY_OPEN;
        }
    }

    private ActivitySchedules() {
    }

    /** 窗口非法：有窗口但起点为 0 或终点不晚于起点，或启用了却没有窗口（停用的行也校验，同基线）。 */
    static boolean windowInvalid(ActivityScheduleTable schedule) {
        long start = schedule.getBaselineStartAtMs();
        long end = schedule.getBaselineEndAtMs();
        boolean empty = start == 0 && end == 0;
        return (!empty && (start == 0 || Long.compareUnsigned(end, start) <= 0)) || (schedule.getEnabled() && empty);
    }

    static Info build(MissionDef def, long nowMs) {
        ActivityScheduleTable schedule = def.schedule();
        if (def.id() == 0 || !def.activity() || (schedule != null && schedule.getId() != def.id())
                || (schedule != null && windowInvalid(schedule))) {
            return new Info(MissionTables.INVALID_TABLE_DATA, 0, 0, 0, PlayerActivityStatus.PLAYER_ACTIVITY_UNSCHEDULED,
                    0, 0, REASON_INVALID);
        }
        if (schedule == null || !schedule.getEnabled()) {
            return new Info(MissionTables.OK, def.id(), def.id(), def.rewardId(),
                    PlayerActivityStatus.PLAYER_ACTIVITY_UNSCHEDULED, 0, 0, REASON_UNSCHEDULED);
        }
        long start = schedule.getBaselineStartAtMs();
        long end = schedule.getBaselineEndAtMs();
        PlayerActivityStatus status;
        String reason;
        if (Long.compareUnsigned(nowMs, start) < 0) {
            status = PlayerActivityStatus.PLAYER_ACTIVITY_UPCOMING;
            reason = REASON_UPCOMING;
        } else if (Long.compareUnsigned(nowMs, end) >= 0) {
            status = PlayerActivityStatus.PLAYER_ACTIVITY_ENDED;
            reason = REASON_ENDED;
        } else {
            status = PlayerActivityStatus.PLAYER_ACTIVITY_OPEN;
            reason = "";
        }
        return new Info(MissionTables.OK, def.id(), def.id(), def.rewardId(), status, start, end, reason);
    }

    /** 接取闸里的活动窗口（基线 CheckOpen）：配置非法 1002，不在开放窗口内 1006。 */
    static int checkOpen(MissionDef def, long nowMs) {
        Info info = build(def, nowMs);
        if (info.tip() != MissionTables.OK) {
            return info.tip();
        }
        return info.open() ? MissionTables.OK : MissionService.FEATURE_UNAVAILABLE;
    }
}
