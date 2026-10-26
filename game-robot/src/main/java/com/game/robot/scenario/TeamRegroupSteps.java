package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.ActorCreateS2C;
import com.game.proto.EnterSceneS2C;
import com.game.proto.TeleportS2C;
import com.game.proto.team.*;
import com.game.robot.client.MessageIds;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import com.game.robot.client.SceneAdminClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** 4.3b 可选真链路：用 dev 瞬移安排位置，归队仍由真实组队 RPC 和 scene 强读触发。 */
final class TeamRegroupSteps {
    private static final String REF = "team-spec 4.3b；movement-broadcast-spec §5.7";
    private final MessageIds ids;
    private final MessageIdRegistry registry;
    private final SceneAdminClient admin;
    private final Duration timeout;
    private final CheckReport report;

    TeamRegroupSteps(MessageIdRegistry registry, SceneAdminClient admin, Duration timeout, CheckReport report) {
        this.registry = registry;
        this.ids = MessageIds.resolve(registry);
        this.admin = admin;
        this.timeout = timeout;
        this.report = report;
    }

    void run(MatchSupport.Caller leader, MatchSupport.Caller member, MatchSupport.Caller outsider, long teamId, long sceneId)
            throws RobotException {
        ActorCreateS2C lead = currentSelf(leader, sceneId);
        ActorCreateS2C self = currentSelf(member, sceneId);
        Vec3 at = Vec3.of(lead.getTransform().getLocation());
        Vec3 facing = Vec3.of(lead.getTransform().getRotation());
        int get = id("GetMyTeam");

        // 离队→远处重入：检验的触发源是 MEMBER_JOINED，不能用显式 regroup 掩盖漏发入队信号。
        accepted(member.call(id("LeaveTeam"), LeaveTeamRequest.newBuilder().setRequestId(UUID.randomUUID().toString()).setExpectedTeamId(teamId).build(), TeamResponse.parser()));
        place(member, at.plus(new Vec3(50, 0, 0)), self.getEntity());
        accepted(member.call(id("ApplyJoinTeam"), ApplyJoinTeamRequest.newBuilder().setRequestId(UUID.randomUUID().toString()).setTargetPlayerId(leader.id()).build(), TeamResponse.parser()));
        int mark = member.mark();
        accepted(leader.call(id("HandleApplication"), HandleApplicationRequest.newBuilder().setRequestId(UUID.randomUUID().toString()).setApplicantId(member.id())
                .setApprove(true).setExpectedTeamId(teamId).build(), TeamResponse.parser()));
        assertRegroup(member, mark, self.getEntity(), at, facing, "入队远离队长时收到 reason=5 的130");

        for (Vec3 delta : List.of(new Vec3(50, 0, 0), new Vec3(0, 0, 30))) {
            place(member, at.plus(delta), self.getEntity());
            mark = member.mark();
            accepted(member.call(get, GetMyTeamRequest.newBuilder().setRegroup(true).build(), TeamResponse.parser()));
            assertRegroup(member, mark, self.getEntity(), at, facing, "显式归队，偏移=" + delta);
        }

        // 近距且互见应保持原位；同时验证队长与无队者不能因 regroup 被移动。
        place(member, at.plus(new Vec3(4, 0, 0)), self.getEntity());
        int nearMark = member.mark(), leaderMark = leader.mark(), outsiderMark = outsider.mark();
        var regroup = GetMyTeamRequest.newBuilder().setRegroup(true).build();
        accepted(member.call(get, regroup, TeamResponse.parser()));
        accepted(leader.call(get, regroup, TeamResponse.parser()));
        accepted(outsider.call(get, regroup, TeamResponse.parser()));
        noTeleport(member, nearMark, "近距互见不瞬移");
        noTeleport(leader, leaderMark, "队长归队请求不瞬移");
        noTeleport(outsider, outsiderMark, "无队玩家归队请求不瞬移");
    }

    private int id(String method) { return registry.requireId("ClientPlayerTeam", method); }

    private void place(MatchSupport.Caller member, Vec3 at, long entity) throws RobotException {
        int mark = member.mark();
        int tip = admin.teleport(member.id(), at.x(), at.y(), at.z());
        if (tip != 0) throw new RobotException("归队探针布置位置失败，dev tip=" + tip + "；管理口须指向当前单scene节点");
        Received received = member.connection().await(mark, r -> r.messageId() == ids.notifyTeleport() && r.requestId() == 0,
                timeout).orElseThrow(() -> new RobotException("dev瞬移后没有130"));
        TeleportS2C teleport = received.parse(TeleportS2C.parser());
        if (teleport.getEntity() != entity || teleport.getReason() != 3
                || !MoveAssertions.sameBits(Vec3.of(teleport.getTransform().getLocation()), at)) {
            throw new RobotException("dev瞬移未到指定位置：" + teleport);
        }
    }

    private void assertRegroup(MatchSupport.Caller member, int mark, long entity, Vec3 at, Vec3 facing, String step)
            throws RobotException {
        var frame = member.connection().await(mark, r -> r.messageId() == ids.notifyTeleport(), timeout);
        List<String> problems = frame.isEmpty() ? List.of("没有收到130")
                : MoveAssertions.teleportProblems(frame.get(), entity, at, facing, 5);
        report.check(problems.isEmpty(), "4.3b " + step, String.join("；", problems), REF);
        if (!problems.isEmpty()) throw new RobotException("归队前提失败：" + problems);
    }

    private void noTeleport(MatchSupport.Caller bot, int mark, String step) throws RobotException {
        boolean moved = bot.connection().await(mark, r -> r.messageId() == ids.notifyTeleport(), Duration.ofSeconds(2)).isPresent();
        report.check(!moved, "4.3b " + step, moved ? "收到了不应有的130" : "2秒内没有130", REF);
    }

    private ActorCreateS2C currentSelf(MatchSupport.Caller bot, long sceneId) throws RobotException {
        int start = -1;
        for (Received r : bot.connection().inbox().snapshot(0)) {
            if (r.messageId() != ids.notifyEnterScene() || r.requestId() != 0) continue;
            var enter = r.parseOrNull(EnterSceneS2C.parser());
            if (enter != null && enter.getSceneInfo().getSceneId() == sceneId) start = r.index();
        }
        if (start < 0) throw new RobotException("归队探针缺少当前场景79");
        return bot.connection().await(start, r -> {
            if (r.messageId() != ids.notifyActorCreate() || r.requestId() != 0) return false;
            var actor = r.parseOrNull(ActorCreateS2C.parser());
            return actor != null && actor.getGuid() == bot.id();
        }, timeout).orElseThrow(() -> new RobotException("归队探针缺少当前实体21")).parse(ActorCreateS2C.parser());
    }

    private static void accepted(TeamResponse response) throws RobotException {
        if (response.hasErrorMessage() && response.getErrorMessage().getId() != 0)
            throw new RobotException("归队探针组队请求失败：" + response.getErrorMessage());
    }
}
