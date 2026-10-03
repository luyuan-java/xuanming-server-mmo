package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.GetActivityListRequest;
import com.game.proto.GetActivityListResponse;
import com.game.proto.GetBagRequest;
import com.game.proto.GetBagResponse;
import com.game.proto.GetMissionListRequest;
import com.game.proto.GetMissionListResponse;
import com.game.proto.MissionActionRequest;
import com.game.proto.MissionObjectiveInfo;
import com.game.proto.PlayerActivityInfo;
import com.game.proto.PlayerActivityStatus;
import com.game.proto.PlayerMissionInfo;
import com.game.proto.PlayerMissionStatus;
import com.game.robot.client.GameConnection;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 玩家功能面板端到端（对应 mmorpg robot features-smoke 里不依赖战斗的部分）：191 背包、193 任务列表、190 活动列表、
 * 194 接取、195 领奖、重登后任务状态原样。全新账号、正式配表下的期望：
 * <ul>
 *   <li>193 是配表全部 17 个任务，都未接；可接 4 / 7 / 8 / 9 / 12 / 13 / 14；1 / 2 / 6 / 10 / 11 玩法未开放（击杀的怪打不到或条件类别没有来源）；
 *       3 / 5 无条件；15–17 是未排期的活动；</li>
 *   <li>190 是三个未排期活动（15–17），不带窗口、不可参与；</li>
 *   <li>194 的拒绝码：scope 1 → 1005、未知任务 1001、无条件 1002、玩法未开放 1003、未排期活动 1006；失败只回 tip；</li>
 *   <li>接 12 → 进行中、目标 {序号 0、条件 1、击杀、进度 0、目标 1}；再接 12 → 5004；同类型的 4 → 5000；领 12 → 5002；</li>
 *   <li>重登后列表逐字段相同、state_persistent 仍为 true。</li>
 * </ul>
 * 击杀要等回合制战斗（路线图 6.x），所以完成 / 领奖成功 / 自动领奖在这里到不了，由 scene 单测覆盖。
 */
public final class FeaturesScenario {

    private static final String MISSIONS = "SceneMissionClientPlayer";
    private static final String REF = "PARITY「任务」「活动列表」行";
    private static final Duration CALL_SPACING = Duration.ofMillis(400);
    private static final Set<Integer> ACCEPTABLE = Set.of(4, 7, 8, 9, 12, 13, 14);
    private static final Map<Integer, String> FRESH_REASONS = freshReasons();

    private final PlayerFlow flow;
    private final String account;
    private final Duration requestTimeout;
    private final int getBag;
    private final int getMissionList;
    private final int acceptMission;
    private final int claimMissionReward;
    private final int getActivityList;

    public FeaturesScenario(PlayerFlow flow, MessageIdRegistry registry, String accountPrefix, String runTag,
                            Duration requestTimeout) {
        this.flow = flow;
        this.account = accountName(accountPrefix, runTag);
        this.requestTimeout = requestTimeout;
        this.getBag = registry.requireId("SceneBagClientPlayer", "GetBag");
        this.getMissionList = registry.requireId(MISSIONS, "GetMissionList");
        this.acceptMission = registry.requireId(MISSIONS, "AcceptMission");
        this.claimMissionReward = registry.requireId(MISSIONS, "ClaimMissionReward");
        this.getActivityList = registry.requireId("SceneActivityClientPlayer", "GetActivityList");
    }

    public static String accountName(String prefix, String runTag) {
        return prefix + "feat" + runTag;
    }

    public String account() {
        return account;
    }

    private static Map<Integer, String> freshReasons() {
        Map<Integer, String> reasons = new TreeMap<>();
        for (int id : ACCEPTABLE) {
            reasons.put(id, "");
        }
        for (int id : new int[] {1, 2, 6, 10, 11}) {
            reasons.put(id, "任务所需玩法暂未开放");
        }
        reasons.put(3, "当前条件下暂不可接取");
        reasons.put(5, "当前条件下暂不可接取");
        for (int id : new int[] {15, 16, 17}) {
            reasons.put(id, "活动尚未排期，敬请期待");
        }
        return reasons;
    }

    public CheckReport run() {
        CheckReport report = new CheckReport();
        EnteredPlayer player = null;
        try {
            player = flow.enter(account, new Timings());
            GameConnection c = player.connection();

            pause();
            GetBagResponse bag = c.call(getBag, GetBagRequest.newBuilder().setBagType(0).build(), GetBagResponse.parser(),
                    requestTimeout);
            report.check(bag.getErrorMessage().getId() == 0 && bag.getBag().getItemsCount() == 0,
                    "191 人物背包是空的", "tip=" + bag.getErrorMessage().getId() + " items=" + bag.getBag().getItemsCount(),
                    REF);

            GetMissionListResponse fresh = list(c);
            checkFreshMissions(report, fresh);
            checkFreshActivities(report, activities(c));

            expectRejected(report, accept(c, 1, 13), 1005, "194 scope 1");
            expectRejected(report, accept(c, 0, 999), 1001, "194 未知任务 999");
            expectRejected(report, accept(c, 0, 3), 1002, "194 无条件的任务 3");
            expectRejected(report, accept(c, 0, 2), 1003, "194 玩法未开放的任务 2");
            expectRejected(report, accept(c, 0, 10), 1003, "194 怪打不到的任务 10");
            expectRejected(report, accept(c, 0, 15), 1006, "194 未排期的活动 15");

            GetMissionListResponse accepted = accept(c, 0, 12);
            PlayerMissionInfo twelve = find(accepted, 12);
            MissionObjectiveInfo objective = MissionObjectiveInfo.newBuilder().setObjectiveIndex(0).setConditionId(1)
                    .setCategory(1).setTarget(1).build();
            report.check(accepted.getErrorMessage().getId() == 0 && accepted.getStatePersistent() && twelve != null
                            && twelve.getStatus() == PlayerMissionStatus.PLAYER_MISSION_ACTIVE && !twelve.getCanAccept()
                            && !twelve.getCanClaim() && twelve.getUnavailableReason().equals("完成任务目标后领取奖励")
                            && twelve.getObjectivesList().equals(List.of(objective)),
                    "194 接 12：进行中、目标 {0, 条件 1, 击杀, 0/1}", "tip=" + accepted.getErrorMessage().getId()
                            + " row=" + (twelve == null ? "null" : twelve.toString().replace('\n', ' ')), REF);
            PlayerMissionInfo four = find(accepted, 4);
            report.check(four != null && !four.getCanAccept() && four.getUnavailableReason().equals("请先完成同类型任务"),
                    "接 12 后同类型的 4 显示「请先完成同类型任务」",
                    "row=" + (four == null ? "null" : four.getUnavailableReason()), REF);
            expectRejected(report, accept(c, 0, 12), 5004, "194 重复接 12");
            expectRejected(report, accept(c, 0, 4), 5000, "194 同类型的 4");
            expectRejected(report, claim(c, 0, 12), 5002, "195 未完成的 12");
            expectRejected(report, claim(c, 1, 12), 1005, "195 scope 1");

            GetMissionListResponse last = accept(c, 0, 13);
            PlayerMissionInfo thirteen = find(last, 13);
            report.check(last.getErrorMessage().getId() == 0 && thirteen != null
                            && thirteen.getStatus() == PlayerMissionStatus.PLAYER_MISSION_ACTIVE,
                    "194 接 13（类型 9）", "tip=" + last.getErrorMessage().getId(), REF);

            c.close();
            player = flow.enter(account, new Timings());
            GetMissionListResponse again = list(player.connection());
            report.check(again.equals(last), "重登后任务列表逐字段相同（state_persistent=true）",
                    "persistent=" + again.getStatePersistent() + " rows=" + again.getMissionsCount(), REF);
        } catch (RobotException e) {
            report.fail("流程", e.getMessage(), REF);
        } finally {
            if (player != null) {
                player.connection().close();
            }
        }
        return report;
    }

    private static void checkFreshMissions(CheckReport report, GetMissionListResponse fresh) {
        List<Integer> ids = fresh.getMissionsList().stream().map(PlayerMissionInfo::getMissionId).toList();
        report.check(fresh.getErrorMessage().getId() == 0 && fresh.getStatePersistent()
                        && ids.equals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17)),
                "193 新号：配表全部 17 个任务、按任务号升序、state_persistent=true",
                "tip=" + fresh.getErrorMessage().getId() + " ids=" + ids, REF);
        boolean allFresh = fresh.getMissionsList().stream().allMatch(info -> info.getConfigured()
                && info.getStatus() == PlayerMissionStatus.PLAYER_MISSION_NOT_ACCEPTED && !info.getCanClaim()
                && info.getCanAccept() == ACCEPTABLE.contains(info.getMissionId()));
        Set<Integer> acceptable = fresh.getMissionsList().stream().filter(PlayerMissionInfo::getCanAccept)
                .map(PlayerMissionInfo::getMissionId).collect(Collectors.toCollection(java.util.TreeSet::new));
        report.check(allFresh, "193 新号：都未接，可接的是 4 / 7 / 8 / 9 / 12 / 13 / 14", "can_accept=" + acceptable, REF);
        Map<Integer, String> reasons = fresh.getMissionsList().stream()
                .collect(Collectors.toMap(PlayerMissionInfo::getMissionId, PlayerMissionInfo::getUnavailableReason,
                        (a, b) -> a, TreeMap::new));
        report.check(reasons.equals(FRESH_REASONS), "193 新号：不可接原因同基线（玩法未开放 / 无条件 / 未排期）",
                "reasons=" + reasons, REF);
        PlayerMissionInfo seven = find(fresh, 7);
        report.check(seven != null && seven.getObjectivesCount() == 1 && seven.getObjectives(0).getTarget() == 8
                        && seven.getAutoReward() && seven.getRewardId() == 1,
                "193 任务 7：目标数取任务行覆盖值 8、自动领奖、奖励 1",
                "row=" + (seven == null ? "null" : seven.toString().replace('\n', ' ')), REF);
    }

    private static void checkFreshActivities(CheckReport report, GetActivityListResponse activities) {
        List<Integer> ids = activities.getActivitiesList().stream().map(PlayerActivityInfo::getActivityId).toList();
        boolean unscheduled = activities.getActivitiesList().stream().allMatch(info ->
                info.getStatus() == PlayerActivityStatus.PLAYER_ACTIVITY_UNSCHEDULED && info.getStartsAtMs() == 0
                        && info.getEndsAtMs() == 0 && !info.getCanParticipate() && info.getRewardId() == 1
                        && info.getMissionId() == info.getActivityId()
                        && info.getUnavailableReason().equals("活动尚未排期，敬请期待"));
        report.check(activities.getErrorMessage().getId() == 0 && ids.equals(List.of(15, 16, 17)) && unscheduled
                        && activities.getServerTimeMs() > 0,
                "190 新号：15 / 16 / 17 都未排期、不带窗口、不可参与、带服务器时间",
                "tip=" + activities.getErrorMessage().getId() + " ids=" + ids + " server_time="
                        + activities.getServerTimeMs(), REF);
    }

    private static void expectRejected(CheckReport report, GetMissionListResponse response, int tip, String name) {
        report.check(response.getErrorMessage().getId() == tip && response.getMissionsCount() == 0
                        && !response.getStatePersistent(),
                name + " 回 " + tip + "，只带 tip", "tip=" + response.getErrorMessage().getId() + " rows="
                        + response.getMissionsCount(), REF);
    }

    private static PlayerMissionInfo find(GetMissionListResponse response, int missionId) {
        return response.getMissionsList().stream().filter(info -> info.getMissionId() == missionId).findFirst()
                .orElse(null);
    }

    private GetMissionListResponse list(GameConnection c) throws RobotException {
        pause();
        return c.call(getMissionList, GetMissionListRequest.getDefaultInstance(), GetMissionListResponse.parser(),
                requestTimeout);
    }

    private GetActivityListResponse activities(GameConnection c) throws RobotException {
        pause();
        return c.call(getActivityList, GetActivityListRequest.getDefaultInstance(), GetActivityListResponse.parser(),
                requestTimeout);
    }

    private GetMissionListResponse accept(GameConnection c, int scope, int missionId) throws RobotException {
        pause();
        return c.call(acceptMission, MissionActionRequest.newBuilder().setScope(scope).setMissionId(missionId).build(),
                GetMissionListResponse.parser(), requestTimeout);
    }

    private GetMissionListResponse claim(GameConnection c, int scope, int missionId) throws RobotException {
        pause();
        return c.call(claimMissionReward, MissionActionRequest.newBuilder().setScope(scope).setMissionId(missionId)
                .build(), GetMissionListResponse.parser(), requestTimeout);
    }

    /** 每次调用前歇一下：gate 按消息号限频（这几个消息号不在表里，取缺省每秒 3 条，同基线），连发会被回 1008。 */
    private static void pause() throws RobotException {
        try {
            Thread.sleep(CALL_SPACING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待被中断", e);
        }
    }
}
