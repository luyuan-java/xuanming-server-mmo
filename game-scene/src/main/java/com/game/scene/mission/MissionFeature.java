package com.game.scene.mission;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.proto.GetMissionListRequest;
import com.game.proto.GetMissionListResponse;
import com.game.proto.MissionActionRequest;
import com.game.proto.MissionObjectiveInfo;
import com.game.proto.PlayerMissionInfo;
import com.game.proto.PlayerMissionStatus;
import com.game.scene.mission.MissionTables.MissionDef;
import com.game.scene.mission.MissionTables.Slot;
import com.game.scene.player.PlayerMissions;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.PlayerCall;
import com.game.scene.world.SceneFeature;
import com.game.scene.world.ScenePlayer;
import java.util.TreeSet;

/**
 * 任务的客户端方法（{@code SceneMissionClientPlayer}）：193 列表、194 接取、195 领奖（基线 player_mission_handler +
 * player_feature_snapshot FillMission）。三个方法都回 {@code GetMissionListResponse}：成功带 {@code error_message{id:0}}、
 * 完整列表与 {@code state_persistent=true}（任务状态随玩家快照落库）；失败只回 tip。
 *
 * <p>列表是配表全部任务 ∪ 玩家存档里出现过的任务（表里删掉的照样列出，{@code configured=false}），按任务号无符号升序；
 * 每行都跑一遍只读的接取闸与领奖闸算 {@code can_accept / can_claim} 与不可用原因。名字与描述恒空（表没有文案列）。
 * 基线还有 1009（实体无效）、1003（任务组件缺失）——Java 里玩家从会话解析、任务状态总在，到不了。
 */
public final class MissionFeature implements SceneFeature {

    private static final String SERVICE = "SceneMissionClientPlayer";
    static final String REASON_UNCONFIGURED = "任务配置暂不可用";
    static final String REASON_ACTIVE = "完成任务目标后领取奖励";
    static final String REASON_COMPLETED = "任务已完成";
    static final String REASON_CLAIM_RETRY = "当前状态暂不可领奖，请稍后重试";
    static final String REASON_TYPE_OCCUPIED = "请先完成同类型任务";
    static final String REASON_NOT_OPEN = "任务所需玩法暂未开放";
    static final String REASON_NOT_ACCEPTABLE = "当前条件下暂不可接取";

    private final MissionService missions;

    public MissionFeature(MissionService missions) {
        this.missions = missions;
    }

    /** 冻结策略（scene-handoff-spec §5.9）：193 只读；194 / 195 GATED，由 {@link MissionService} 的冻结闸回 1005。 */
    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "GetMissionList", GetMissionListRequest.class, FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, request) -> reply(call, MissionService.OK, missions.nowMillis()));
        r.on(SERVICE, "AcceptMission", MissionActionRequest.class, FreezePolicy.GATED, BattlePolicy.ALLOW, (call, request) -> {
            long now = missions.nowMillis();
            reply(call, missions.accept(call.player(), request.getScope(), request.getMissionId(), now), now);
        });
        r.on(SERVICE, "ClaimMissionReward", MissionActionRequest.class, FreezePolicy.GATED, BattlePolicy.ALLOW, (call, request) -> {
            long now = missions.nowMillis();
            reply(call, missions.claim(call.player(), request.getScope(), request.getMissionId()), now);
        });
    }

    /** 一次请求只读一次时钟：接取闸与应答列表用同一个时刻。 */
    private void reply(PlayerCall call, int result, long nowMs) {
        if (result != MissionService.OK) {
            call.reply(GetMissionListResponse.newBuilder().setErrorMessage(tip(result)).build());
            return;
        }
        call.reply(list(call.player(), nowMs));
    }

    GetMissionListResponse list(ScenePlayer player, long nowMs) {
        PlayerMissions state = player.missions();
        TreeSet<Integer> ids = state.knownIds();
        for (MissionDef def : missions.tables().missions()) {
            ids.add(def.id());
        }
        GetMissionListResponse.Builder response = GetMissionListResponse.newBuilder()
                .setErrorMessage(tip(MissionService.OK))
                .setStatePersistent(true);
        for (int id : ids) {
            response.addMissions(entry(player, id, nowMs));
        }
        return response.build();
    }

    private PlayerMissionInfo entry(ScenePlayer player, int id, long nowMs) {
        PlayerMissions state = player.missions();
        PlayerMissionStatus status = MissionService.status(state, id);
        PlayerMissionInfo.Builder info = PlayerMissionInfo.newBuilder().setMissionId(id).setScope(0).setStatus(status);
        MissionDef def = missions.tables().mission(id);
        if (def == null) {
            return info.setConfigured(false).setUnavailableReason(REASON_UNCONFIGURED).build();
        }
        int accept = missions.checkAccept(player, 0, id, nowMs);
        int claim = missions.checkClaim(player, 0, id);
        boolean canAccept = status == PlayerMissionStatus.PLAYER_MISSION_NOT_ACCEPTED && accept == MissionService.OK;
        boolean canClaim = status == PlayerMissionStatus.PLAYER_MISSION_CLAIMABLE && claim == MissionService.OK;
        info.setConfigured(true).setCanAccept(canAccept).setCanClaim(canClaim)
                .setMissionType(def.type())
                .setMissionSubType(def.subType())
                .setRewardId(def.rewardId())
                .setAutoReward(def.autoReward());
        if (!canAccept && !canClaim) {
            info.setUnavailableReason(reason(def, status, accept, nowMs));
        }
        PlayerMissions.Active active = state.active(id);
        boolean finished = status == PlayerMissionStatus.PLAYER_MISSION_COMPLETED
                || status == PlayerMissionStatus.PLAYER_MISSION_CLAIMABLE;
        for (Slot slot : def.slots()) {
            MissionObjectiveInfo.Builder objective = MissionObjectiveInfo.newBuilder()
                    .setObjectiveIndex(slot.index())
                    .setConditionId(slot.conditionId());
            if (slot.condition() != null) {
                long progress = active != null && slot.index() < active.slots() ? active.progress(slot.index()) : 0;
                objective.setCategory(slot.condition().category())
                        .setTarget((int) ConditionRules.effectiveTarget(slot.condition(), slot.targetOverride()))
                        .setProgress((int) progress)
                        .setCompleted(finished || (active != null
                                && ConditionRules.isFulfilled(slot.condition(), progress, slot.targetOverride())));
            }
            info.addObjectives(objective);
        }
        return info.build();
    }

    private static String reason(MissionDef def, PlayerMissionStatus status, int accept, long nowMs) {
        return switch (status) {
            case PLAYER_MISSION_ACTIVE -> REASON_ACTIVE;
            case PLAYER_MISSION_COMPLETED -> REASON_COMPLETED;
            case PLAYER_MISSION_CLAIMABLE -> REASON_CLAIM_RETRY;
            default -> {
                if (accept == MissionService.TYPE_ALREADY_EXISTS) {
                    yield REASON_TYPE_OCCUPIED;
                }
                if (def.activity()) {
                    yield ActivitySchedules.build(def, nowMs).reason();
                }
                yield accept == MissionService.SERVICE_UNAVAILABLE ? REASON_NOT_OPEN : REASON_NOT_ACCEPTABLE;
            }
        };
    }
}
