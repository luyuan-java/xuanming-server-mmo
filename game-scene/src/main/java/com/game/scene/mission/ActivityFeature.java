package com.game.scene.mission;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.proto.ClaimActivityVitalityRewardRequest;
import com.game.proto.GetActivityListRequest;
import com.game.proto.GetActivityListResponse;
import com.game.proto.PlayerActivityInfo;
import com.game.scene.mission.MissionTables.MissionDef;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.PlayerCall;
import com.game.scene.world.SceneFeature;
import com.game.scene.world.ScenePlayer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 活动列表（{@code SceneActivityClientPlayer} 190 GetActivityList；基线 player_activity_handler + PlayerActivityScheduleSystem）。
 * 活动就是任务类型 2 的任务行：每行按排期算状态与窗口，开放时再跑一遍接取闸决定能否参与；按活动号升序。
 * 参与走 194 接取（同一个任务号）。名字、描述、图标恒空（表没有展示列）。
 */
public final class ActivityFeature implements SceneFeature {

    private static final String SERVICE = "SceneActivityClientPlayer";
    static final String REASON_SEE_MISSIONS = "请在任务页查看活动进度";
    static final String REASON_TYPE_OCCUPIED = "请先完成同类型活动任务";
    static final String REASON_NOT_PARTICIPATABLE = "活动所需玩法或当前状态暂不满足参与条件";

    private final MissionService missions;
    private com.game.scene.festivalbeast.FestivalBeastService festivalBeastService;

    public ActivityFeature(MissionService missions) {
        this.missions = missions;
    }

    public ActivityFeature withFestivalBeastService(com.game.scene.festivalbeast.FestivalBeastService festivalBeastService) {
        this.festivalBeastService = festivalBeastService;
        return this;
    }

    /** 冻结策略（scene-handoff-spec §5.9）：190 只读；348 领奖幂等重放优先，未重放的新领奖在业务层判冻结/战斗回 1006。 */
    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "GetActivityList", GetActivityListRequest.class, FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, request) -> call.reply(list(call.player(), missions.nowMillis())));
        r.on(SERVICE, "ClaimActivityVitalityReward", ClaimActivityVitalityRewardRequest.class,
                FreezePolicy.GATED, BattlePolicy.ALLOW,
                (call, request) -> call.reply(missions.vitality().claim(call.player(), request, missions.nowMillis())));
    }

    GetActivityListResponse list(ScenePlayer player, long nowMs) {
        List<PlayerActivityInfo> activities = new ArrayList<>();
        for (MissionDef def : missions.tables().missions()) {
            if (def.activity()) {
                activities.add(entry(player, def, nowMs));
            }
        }
        activities.sort(Comparator.comparing(PlayerActivityInfo::getActivityId, Integer::compareUnsigned));
        if (festivalBeastService != null) {
            PlayerActivityInfo festivalEntry = festivalBeastService.buildCatalogEntry(player, nowMs);
            if (festivalEntry != null) {
                activities.add(festivalEntry);
            }
        }
        return GetActivityListResponse.newBuilder()
                .setErrorMessage(tip(MissionService.OK))
                .addAllActivities(activities)
                .setServerTimeMs(nowMs)
                .setVitality(missions.vitality().buildSnapshot(player, nowMs))
                .build();
    }

    private PlayerActivityInfo entry(ScenePlayer player, MissionDef def, long nowMs) {
        ActivitySchedules.Info schedule = ActivitySchedules.build(def, nowMs);
        int accept = missions.checkAccept(player, 0, def.id(), nowMs);
        boolean open = schedule.open();
        boolean canParticipate = open && accept == MissionService.OK;
        String reason = schedule.reason();
        if (open && !canParticipate) {
            if (accept == MissionService.ALREADY_ACCEPTED || accept == MissionService.ALREADY_COMPLETED) {
                reason = REASON_SEE_MISSIONS;
            } else if (accept == MissionService.TYPE_ALREADY_EXISTS) {
                reason = REASON_TYPE_OCCUPIED;
            } else {
                reason = REASON_NOT_PARTICIPATABLE;
            }
        }
        return PlayerActivityInfo.newBuilder()
                .setActivityId(schedule.activityId())
                .setMissionId(schedule.missionId())
                .setRewardId(schedule.rewardId())
                .setStatus(schedule.status())
                .setStartsAtMs(schedule.startsAtMs())
                .setEndsAtMs(schedule.endsAtMs())
                .setCanParticipate(canParticipate)
                .setUnavailableReason(reason)
                .build();
    }
}
