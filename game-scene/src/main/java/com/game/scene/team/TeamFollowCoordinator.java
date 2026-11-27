package com.game.scene.team;

import com.game.proto.Rotation;
import com.game.proto.TeamFollowStateS2C;
import com.game.proto.TeamFollowStopReason;
import com.game.scene.metrics.SceneMetrics.TeleportReason;
import com.game.scene.skill.SkillService;
import com.game.scene.world.Scene;
import com.game.scene.world.SceneNavigation;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务器驱动组队跟随的运行时协调器（对齐 C++ {@code TeamFollowSystem}，单逻辑线程独占）。
 *
 * <p>遵循 2026-11-22 收口约束：
 * <ul>
 *   <li>最后一名随从离开/暂离/被踢后立即回收空队形，不留 0 随从队长持续跑 20Hz 轨迹更新；</li>
 *   <li>离线托管实体（已摘除活会话）照常在队形内推进走位，发给本人的 {@code TeamFollowStateS2C} / {@code ActorMoveS2C} 静默跳过；</li>
 *   <li>与 {@link SkillService} 状态互斥表联动：入队跟随时置 {@code STATE_TEAM_FOLLOW}，被施法/进入战斗打断时经退出钩子退跟。</li>
 * </ul>
 */
public final class TeamFollowCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TeamFollowCoordinator.class);

    public static final double SNAP_TO_TRAIL_RADIUS = 0.75;
    public static final double MAX_APPROACH_PATH = 48.0;
    public static final double MAX_LEADER_JUMP = 4.0;

    private static final class FormationEntry {
        TeamFollowFormation formation;
        final long teamId;
        final long sceneId;

        FormationEntry(TeamFollowFormation formation, long teamId, long sceneId) {
            this.formation = formation;
            this.teamId = teamId;
            this.sceneId = sceneId;
        }
    }

    private static final class FollowerEntry {
        final long leaderPlayerId;
        boolean broadcastMoving;
        double broadcastVx;
        double broadcastVy;
        long broadcastFrame;

        FollowerEntry(long leaderPlayerId, long frame) {
            this.leaderPlayerId = leaderPlayerId;
            this.broadcastFrame = frame;
        }
    }

    /** 队长离开本节点导致队形解散后，原随从最多等待队长去留结论的时长：90 秒（对齐主仓）。 */
    public static final long LEADER_OUTCOME_WAIT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(90L);

    private static final class PendingLeaderOutcome {
        final Set<Long> followerIds = new HashSet<>();
        long deadlineNanos;

        PendingLeaderOutcome(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }
    }

    private final Map<Long, FormationEntry> formations = new HashMap<>();
    private final Map<Long, FollowerEntry> followers = new HashMap<>();
    private final Set<Long> followIntents = new HashSet<>();
    private final Map<Long, Integer> joinSeqs = new HashMap<>();
    private final Map<Long, TeamFollowFormation.Style> formationStyles = new HashMap<>();
    private final Map<Long, PendingLeaderOutcome> waitingForLeaderOutcome = new HashMap<>();
    private SkillService skills;

    public void attachSkills(SkillService skills) {
        this.skills = skills;
    }

    public boolean isDriven(long playerId) {
        return followers.containsKey(playerId);
    }

    public boolean isDriven(ScenePlayer player) {
        return player != null && followers.containsKey(player.playerId());
    }

    public boolean wantsFollow(long playerId) {
        return followIntents.contains(playerId);
    }

    public int activeFormationCount() {
        return formations.size();
    }

    public void rememberJoinSeq(long playerId, int joinSeq) {
        joinSeqs.put(playerId, joinSeq);
    }

    /** 记录队长/队伍的跑图跟随阵型样式；若该玩家当前正领队则实时切换队形样式。 */
    public void rememberFormationStyle(long playerId, TeamFollowFormation.Style style) {
        TeamFollowFormation.Style effective = style == null ? TeamFollowFormation.Style.COLUMN : style;
        formationStyles.put(playerId, effective);
        FormationEntry active = formations.get(playerId);
        if (active != null) {
            active.formation.setStyle(effective);
        }
    }

    public TeamFollowFormation.Style formationStyleOf(long leaderId) {
        FormationEntry active = formations.get(leaderId);
        if (active != null) {
            return active.formation.style();
        }
        return formationStyles.getOrDefault(leaderId, TeamFollowFormation.Style.COLUMN);
    }

    /** 声明跟随（follow=true）或暂离（follow=false，立即退跟并通知客户端）。 */
    public void setFollowIntent(SceneWorld world, ScenePlayer player, boolean follow) {
        if (player == null) {
            return;
        }
        if (follow) {
            followIntents.add(player.playerId());
            return;
        }
        followIntents.remove(player.playerId());
        detach(world, player, TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEFT_FOLLOW);
    }

    /** 尝试将 {@code follower} 接到 {@code leader} 身后进入服务器驱动；成功返回 true。 */
    public boolean tryAttach(SceneWorld world, ScenePlayer follower, ScenePlayer leader, boolean walkAllowed) {
        if (follower == null || leader == null || follower == leader || !wantsFollow(follower.playerId())) {
            return false;
        }
        if (follower.scene() == null || leader.scene() == null || follower.scene() != leader.scene()) {
            return false;
        }
        long teamId = leader.teamId();
        if (teamId == 0 || follower.teamId() != teamId) {
            return false;
        }
        if (isLeavingNode(world, follower) || isLeavingNode(world, leader)) {
            return false;
        }
        long followerId = follower.playerId();
        long leaderId = leader.playerId();
        // 已接在同一队长身后：幂等补推状态通知
        FollowerEntry existing = followers.get(followerId);
        if (existing != null && existing.leaderPlayerId == leaderId) {
            FormationEntry entry = formations.get(leaderId);
            if (entry != null) {
                OptionalInt rank = entry.formation.rankOf(followerId);
                if (rank.isPresent()) {
                    sendFollowState(world, follower, true, leader.entity(), rank.getAsInt() + 1,
                            TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_UNSPECIFIED);
                    return true;
                }
            }
        }

        detach(world, follower, TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEADER_CHANGED);
        dissolveFormation(world, followerId, TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEADER_CHANGED);
        detach(world, leader, TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_BECAME_LEADER);

        FormationEntry comp = formations.get(leaderId);
        if (comp != null && (comp.teamId != teamId || comp.sceneId != leader.scene().sceneId())) {
            dissolveFormation(world, leaderId, TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEADER_CHANGED);
            comp = null;
        }
        if (comp == null) {
            TeamFollowFormation.Style style = formationStyles.getOrDefault(leaderId, TeamFollowFormation.Style.COLUMN);
            comp = new FormationEntry(new TeamFollowFormation(leader.position(), TeamFollowFormation.DEFAULT_SPACING, style),
                    teamId, leader.scene().sceneId());
            formations.put(leaderId, comp);
        }
        if (comp.formation.members().size() >= TeamFollowFormation.MAX_MEMBERS) {
            log.warn("[TeamFollow] metric=team_follow_skipped reason=formation_full player_id={} leader_id={}",
                    Long.toUnsignedString(followerId), Long.toUnsignedString(leaderId));
            return false;
        }

        if (!placeMember(world, leader, comp, follower, walkAllowed)) {
            if (comp.formation.isEmpty()) {
                formations.remove(leaderId);
            }
            return false;
        }
        if (skills != null) {
            skills.addActionState(world, follower, SkillService.STATE_TEAM_FOLLOW);
        }
        return true;
    }

    /** 停止驱动指定队员；若其所属队形变空则立即回收队形。 */
    public void detach(SceneWorld world, ScenePlayer follower, TeamFollowStopReason reason) {
        if (follower == null) {
            return;
        }
        long followerId = follower.playerId();
        FollowerEntry driven = followers.get(followerId);
        if (driven == null) {
            return;
        }
        FormationEntry comp = formations.get(driven.leaderPlayerId);
        if (comp != null) {
            comp.formation.remove(followerId);
            if (comp.formation.isEmpty()) {
                formations.remove(driven.leaderPlayerId);
            }
        }
        releaseFollower(world, follower, reason);
    }

    /** 客户端重连 / 接回断线保留后补推驱动态（若仍在队形且同场景）。 */
    public void resendStateAfterEntry(SceneWorld world, ScenePlayer player) {
        if (player == null || player.scene() == null) {
            return;
        }
        FollowerEntry driven = followers.get(player.playerId());
        if (driven == null) {
            return;
        }
        FormationEntry comp = formations.get(driven.leaderPlayerId);
        ScenePlayer leader = world.playerById(driven.leaderPlayerId);
        if (comp == null || leader == null || comp.sceneId != player.scene().sceneId()) {
            return;
        }
        OptionalInt rank = comp.formation.rankOf(player.playerId());
        if (rank.isPresent()) {
            sendFollowState(world, player, true, leader.entity(), rank.getAsInt() + 1,
                    TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_UNSPECIFIED);
        }
    }

    public void onTeamChanged(SceneWorld world, ScenePlayer player) {
        if (player == null) {
            return;
        }
        detach(world, player, TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_TEAM_CHANGED);
        dissolveFormation(world, player.playerId(), TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEADER_CHANGED);
    }

    public void onBecameLeader(SceneWorld world, ScenePlayer player) {
        detach(world, player, TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_BECAME_LEADER);
    }

    public void onNotLeader(SceneWorld world, ScenePlayer player) {
        if (player != null) {
            dissolveFormation(world, player.playerId(), TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEADER_CHANGED);
        }
    }

    /** 玩家彻底离开节点时清理其意图、随从状态与所领导的队形。 */
    public void onPlayerRemoved(SceneWorld world, ScenePlayer player) {
        if (player == null) {
            return;
        }
        long id = player.playerId();
        followIntents.remove(id);
        joinSeqs.remove(id);
        formationStyles.remove(id);
        detach(world, player, TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEAVING);
        dissolveFormation(world, id, TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEADER_GONE);
        // 队长离开本节点：唤醒在等队长去留的原随从重跑跟随检查（跨节点跟过去）
        notifyLeaderOutcome(world, id);
    }

    /** 队长去留有结论（离开本节点或跨节点交接作废/原地解冻）时，立即唤醒等待中的原随从重跑跟随检查。 */
    public void notifyLeaderOutcome(SceneWorld world, long leaderId) {
        PendingLeaderOutcome pending = waitingForLeaderOutcome.remove(leaderId);
        if (pending == null || pending.followerIds.isEmpty()) {
            return;
        }
        for (long followerId : pending.followerIds) {
            ScenePlayer follower = world.playerById(followerId);
            if (follower != null && followIntents.contains(followerId)) {
                world.recheckTeamFollow(follower);
            }
        }
    }

    /** 检查等待队长去留是否到达 90 秒上限，到期唤醒原随从复查。 */
    public void sweepLeaderOutcomeWaits(SceneWorld world, long nowNanos) {
        if (waitingForLeaderOutcome.isEmpty()) {
            return;
        }
        List<Long> expiredLeaders = null;
        for (Map.Entry<Long, PendingLeaderOutcome> entry : waitingForLeaderOutcome.entrySet()) {
            if (nowNanos - entry.getValue().deadlineNanos >= 0L) {
                if (expiredLeaders == null) {
                    expiredLeaders = new ArrayList<>();
                }
                expiredLeaders.add(entry.getKey());
            }
        }
        if (expiredLeaders != null) {
            for (long leaderId : expiredLeaders) {
                notifyLeaderOutcome(world, leaderId);
            }
        }
    }

    public int waitingLeaderOutcomeCount() {
        return waitingForLeaderOutcome.size();
    }

    /** 每逻辑帧调用（紧随 {@code integrate} 之后），推进所有活跃队形并回收空/失效队形。 */
    public void tick(SceneWorld world, double deltaSeconds, long currentFrame) {
        if (formations.isEmpty() && followers.isEmpty()) {
            return;
        }
        List<Long> leaderIds = new ArrayList<>(formations.keySet());
        for (long leaderId : leaderIds) {
            FormationEntry comp = formations.get(leaderId);
            if (comp == null) {
                continue;
            }
            ScenePlayer leader = world.playerById(leaderId);
            TeamFollowStopReason dissolveReason = formationStopReason(world, leader, comp);
            if (dissolveReason != null) {
                dissolveFormation(world, leaderId, dissolveReason);
                continue;
            }
            if (leader.inBattle()) {
                for (TeamFollowFormation.Member m : comp.formation.members()) {
                    ScenePlayer f = world.playerById(m.playerId);
                    FollowerEntry d = followers.get(m.playerId);
                    if (f != null && d != null) {
                        stopMotion(world, f, d, currentFrame);
                    }
                }
                continue;
            }
            List<TeamFollowFormation.Member> snapshot = new ArrayList<>(comp.formation.members());
            for (TeamFollowFormation.Member m : snapshot) {
                ScenePlayer f = world.playerById(m.playerId);
                TeamFollowStopReason memberReason = memberStopReason(world, f, leaderId, comp);
                if (memberReason != null) {
                    comp.formation.remove(m.playerId);
                    if (f != null) {
                        releaseFollower(world, f, memberReason);
                    } else {
                        followers.remove(m.playerId);
                    }
                }
            }
            if (comp.formation.isEmpty()) {
                formations.remove(leaderId);
                continue;
            }
            Vec3 leaderPos = leader.position();
            if (TeamFollowTrail.horizontalDistance(comp.formation.trail().head(), leaderPos) > MAX_LEADER_JUMP) {
                restartFormation(world, leader, comp, leaderPos, currentFrame);
                if (comp.formation.isEmpty()) {
                    formations.remove(leaderId);
                }
                continue;
            }
            for (TeamFollowFormation.Member m : comp.formation.members()) {
                ScenePlayer f = world.playerById(m.playerId);
                comp.formation.setSuspended(m.playerId, f == null || f.inBattle());
            }
            SceneNavigation nav = world.navigationFor(leader.scene());
            TeamFollowTrail.WalkableCheck walkable = (from, to) -> !nav.trace(from, to).blocked();
            List<TeamFollowFormation.Step> steps = comp.formation.tick(leaderPos, deltaSeconds, walkable);
            List<ScenePlayer> moveBatch = null;
            for (TeamFollowFormation.Step step : steps) {
                ScenePlayer f = world.playerById(step.playerId());
                FollowerEntry d = followers.get(step.playerId());
                if (f == null || d == null) {
                    continue;
                }
                if (step.suspended()) {
                    if (stopMotion(f, d, currentFrame)) {
                        if (moveBatch == null) {
                            moveBatch = new ArrayList<>(steps.size());
                        }
                        moveBatch.add(f);
                    }
                    continue;
                }
                if (step.placed()) {
                    if (!teleportIntoSlot(world, f, d, leader, step.position(), currentFrame)) {
                        detach(world, f, TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEFT_FOLLOW);
                    }
                    continue;
                }
                if (applyWalkStep(world, f, d, step, deltaSeconds, currentFrame)) {
                    if (moveBatch == null) {
                        moveBatch = new ArrayList<>(steps.size());
                    }
                    moveBatch.add(f);
                }
            }
            if (moveBatch != null && !moveBatch.isEmpty()) {
                world.broadcastTeamFollowMoves(moveBatch);
            }
            if (comp.formation.isEmpty()) {
                formations.remove(leaderId);
            }
        }
        // 清理孤儿随从
        if (!followers.isEmpty()) {
            List<Long> orphans = new ArrayList<>();
            for (var e : followers.entrySet()) {
                FormationEntry comp = formations.get(e.getValue().leaderPlayerId);
                if (comp == null || comp.formation.find(e.getKey()) == null) {
                    orphans.add(e.getKey());
                }
            }
            for (long orphanId : orphans) {
                ScenePlayer p = world.playerById(orphanId);
                if (p != null) {
                    releaseFollower(world, p, TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEADER_GONE);
                } else {
                    followers.remove(orphanId);
                }
            }
        }
    }

    private boolean placeMember(SceneWorld world, ScenePlayer leader, FormationEntry comp, ScenePlayer follower,
                                boolean walkAllowed) {
        long followerId = follower.playerId();
        int joinSeq = joinSeqs.getOrDefault(followerId, 0);
        int rank = comp.formation.rankFor(joinSeq, followerId);
        Vec3 pos = follower.position();
        TeamFollowFormation.Member member;
        boolean teleport = false;
        OptionalDouble nearest = comp.formation.trail().nearestOdo(pos, SNAP_TO_TRAIL_RADIUS);
        if (nearest.isPresent()) {
            member = new TeamFollowFormation.Member(followerId, joinSeq, nearest.getAsDouble());
        } else if (walkAllowed && tryPrependStraightApproach(world, follower, comp.formation, rank, pos)) {
            member = new TeamFollowFormation.Member(followerId, joinSeq, comp.formation.trail().tailOdo());
        } else {
            double slot = comp.formation.slotOdo(rank);
            member = new TeamFollowFormation.Member(followerId, joinSeq, slot);
            teleport = true;
        }
        if (!comp.formation.add(member)) {
            return false;
        }
        FollowerEntry entry = new FollowerEntry(leader.playerId(), world.currentFrame());
        followers.put(followerId, entry);
        if (teleport) {
            if (!teleportIntoSlot(world, follower, entry, leader, comp.formation.trail().pointAt(member.cursorOdo),
                    world.currentFrame())) {
                comp.formation.remove(followerId);
                releaseFollower(world, follower, TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEFT_FOLLOW);
                return false;
            }
        }
        int finalSlot = comp.formation.rankOf(followerId).orElse(rank) + 1;
        sendFollowState(world, follower, true, leader.entity(), finalSlot,
                TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_UNSPECIFIED);
        return true;
    }

    private boolean tryPrependStraightApproach(SceneWorld world, ScenePlayer follower, TeamFollowFormation formation,
                                               int rank, Vec3 pos) {
        formation.trimTailForNewcomer(rank);
        Vec3 tail = formation.trail().tail();
        double dist = TeamFollowTrail.horizontalDistance(pos, tail);
        if (dist > MAX_APPROACH_PATH) {
            return false;
        }
        SceneNavigation nav = world.navigationFor(follower.scene());
        if (nav.trace(pos, tail).blocked()) {
            return false;
        }
        formation.trail().prependPath(List.of(pos, tail));
        return true;
    }

    private void restartFormation(SceneWorld world, ScenePlayer leader, FormationEntry comp, Vec3 leaderPos,
                                  long currentFrame) {
        List<TeamFollowFormation.Member> old = new ArrayList<>(comp.formation.members());
        comp.formation = new TeamFollowFormation(leaderPos, comp.formation.spacing(), comp.formation.style());
        for (TeamFollowFormation.Member m : old) {
            ScenePlayer f = world.playerById(m.playerId);
            FollowerEntry d = followers.get(m.playerId);
            if (f == null || d == null) {
                continue;
            }
            if (f.inBattle()) {
                stopMotion(world, f, d, currentFrame);
                TeamFollowFormation.Member held = new TeamFollowFormation.Member(m.playerId, m.joinSeq, 0.0);
                held.suspended = true;
                held.needsPlacement = true;
                comp.formation.add(held);
                continue;
            }
            boolean withinWalk = TeamFollowTrail.horizontalDistance(f.position(), leaderPos) <= MAX_APPROACH_PATH * 0.5;
            placeMember(world, leader, comp, f, withinWalk);
        }
    }

    private boolean applyWalkStep(SceneWorld world, ScenePlayer follower, FollowerEntry driven,
                                  TeamFollowFormation.Step step, double deltaSeconds, long currentFrame) {
        if (step.moved() <= 0.0 || deltaSeconds <= 0.0) {
            return stopMotion(follower, driven, currentFrame);
        }
        Vec3 from = follower.position();
        SceneNavigation nav = world.navigationFor(follower.scene());
        SceneNavigation.Move traced = nav.trace(from, step.position());
        if (traced.blocked() && step.trailPosition() != null && !step.trailPosition().equals(step.position())) {
            traced = nav.trace(from, step.trailPosition());
        }
        Vec3 target = traced.position();
        double dx = target.x() - from.x();
        double dy = target.y() - from.y();
        double dz = target.z() - from.z();
        boolean stoppedElsewhere = driven.broadcastMoving && !follower.isMoving();

        world.relocateTeamFollower(follower, target);
        if (Math.hypot(dx, dy) > 1e-6) {
            float yawDeg = (float) Math.toDegrees(Math.atan2(dy, dx));
            follower.setRotation(Rotation.newBuilder().setZ(yawDeg).build());
        }
        Vec3 vel = new Vec3(dx / deltaSeconds, dy / deltaSeconds, dz / deltaSeconds);
        follower.setVelocity(vel);
        follower.markTransformAndVelocityDirty();

        TeamFollowFormation.BroadcastState last = new TeamFollowFormation.BroadcastState(
                driven.broadcastMoving && !stoppedElsewhere, driven.broadcastVx, driven.broadcastVy);
        TeamFollowFormation.BroadcastState now = new TeamFollowFormation.BroadcastState(true, vel.x(), vel.y());
        if (TeamFollowFormation.shouldBroadcast(last, now, currentFrame - driven.broadcastFrame)) {
            driven.broadcastMoving = true;
            driven.broadcastVx = vel.x();
            driven.broadcastVy = vel.y();
            driven.broadcastFrame = currentFrame;
            return true;
        }
        return false;
    }

    private void stopMotion(SceneWorld world, ScenePlayer follower, FollowerEntry driven, long currentFrame) {
        if (stopMotion(follower, driven, currentFrame)) {
            world.broadcastTeamFollowMove(follower);
        }
    }

    private boolean stopMotion(ScenePlayer follower, FollowerEntry driven, long currentFrame) {
        if (follower.isMoving()) {
            follower.stopMotion();
            follower.markVelocityDirty();
        }
        if (driven.broadcastMoving) {
            driven.broadcastMoving = false;
            driven.broadcastVx = 0.0;
            driven.broadcastVy = 0.0;
            driven.broadcastFrame = currentFrame;
            return true;
        }
        return false;
    }

    private boolean teleportIntoSlot(SceneWorld world, ScenePlayer follower, FollowerEntry driven, ScenePlayer leader,
                                     Vec3 slot, long currentFrame) {
        Rotation facing = leader.rotation() == null ? Rotation.getDefaultInstance() : leader.rotation();
        var result = world.teleportWithinScene(follower, slot, facing, TeleportReason.TEAM_REGROUP);
        if (result != com.game.scene.metrics.SceneMetrics.TeleportResult.TELEPORTED) {
            return false;
        }
        driven.broadcastMoving = false;
        driven.broadcastVx = 0.0;
        driven.broadcastVy = 0.0;
        driven.broadcastFrame = currentFrame;
        return true;
    }

    private void releaseFollower(SceneWorld world, ScenePlayer follower, TeamFollowStopReason reason) {
        FollowerEntry driven = followers.remove(follower.playerId());
        if (driven == null) {
            return;
        }
        stopMotion(world, follower, driven, world.currentFrame());
        if (skills != null) {
            skills.removeActionState(world, follower, SkillService.STATE_TEAM_FOLLOW);
        }
        sendFollowState(world, follower, false, 0L, 0, reason);
    }

    private void dissolveFormation(SceneWorld world, long leaderId, TeamFollowStopReason reason) {
        FormationEntry comp = formations.remove(leaderId);
        if (comp == null) {
            return;
        }
        boolean waitLeaderOutcome = reason == TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEADER_GONE;
        PendingLeaderOutcome waiter = null;
        if (waitLeaderOutcome) {
            waiter = waitingForLeaderOutcome.computeIfAbsent(leaderId,
                    ignored -> new PendingLeaderOutcome(world.nanoTime() + LEADER_OUTCOME_WAIT_NANOS));
        }
        for (TeamFollowFormation.Member m : new ArrayList<>(comp.formation.members())) {
            ScenePlayer f = world.playerById(m.playerId);
            FollowerEntry d = followers.get(m.playerId);
            if (d != null && d.leaderPlayerId == leaderId) {
                if (waiter != null && f != null && followIntents.contains(m.playerId)) {
                    waiter.followerIds.add(m.playerId);
                }
                if (f != null) {
                    releaseFollower(world, f, reason);
                } else {
                    followers.remove(m.playerId);
                }
            }
        }
        if (waiter != null && waiter.followerIds.isEmpty()) {
            waitingForLeaderOutcome.remove(leaderId, waiter);
        }
    }

    private TeamFollowStopReason formationStopReason(SceneWorld world, ScenePlayer leader, FormationEntry comp) {
        if (leader == null || isLeavingNode(world, leader)) {
            return TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEADER_GONE;
        }
        Scene scene = leader.scene();
        if (scene == null || scene.sceneId() != comp.sceneId) {
            return TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_SCENE_CHANGED;
        }
        if (leader.teamId() != comp.teamId || followers.containsKey(leader.playerId())) {
            return TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEADER_CHANGED;
        }
        return null;
    }

    private TeamFollowStopReason memberStopReason(SceneWorld world, ScenePlayer follower, long leaderId,
                                                  FormationEntry comp) {
        if (follower == null) {
            return TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEAVING;
        }
        FollowerEntry driven = followers.get(follower.playerId());
        if (driven == null || driven.leaderPlayerId != leaderId) {
            return TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEADER_CHANGED;
        }
        if (!followIntents.contains(follower.playerId())) {
            return TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEFT_FOLLOW;
        }
        if (isLeavingNode(world, follower)) {
            return TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEAVING;
        }
        if (follower.teamId() != comp.teamId) {
            return TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_TEAM_CHANGED;
        }
        if (follower.scene() == null || follower.scene().sceneId() != comp.sceneId) {
            return TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_SCENE_CHANGED;
        }
        return null;
    }

    private static boolean isLeavingNode(SceneWorld world, ScenePlayer player) {
        return player.frozen() || world.switchInFlight(player);
    }

    private static void sendFollowState(SceneWorld world, ScenePlayer follower, boolean driven, long leaderEntity,
                                        int slot, TeamFollowStopReason reason) {
        if (follower.session() == null) {
            return; // 断线保留 / 离线托管中已摘除活会话，静默跳过
        }
        TeamFollowStateS2C.Builder msg = TeamFollowStateS2C.newBuilder().setDriven(driven);
        if (driven) {
            msg.setLeaderEntity(leaderEntity).setSlot(slot);
        } else {
            msg.setReason(reason);
        }
        world.sendTeamFollowState(follower, msg.build());
    }
}
