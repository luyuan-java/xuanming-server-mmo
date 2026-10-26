package com.game.scene.team;

import com.game.discovery.proto.TeamInfo;
import com.game.discovery.team.TeamMembership;
import com.game.proto.Rotation;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.metrics.SceneMetrics.TeamFollowResult;
import com.game.scene.metrics.SceneMetrics.TeleportReason;
import com.game.scene.metrics.SceneMetrics.TeleportResult;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 组队跟随与成员关系提示（4.3b）：SQL 是权威，提示只触发强读；所有判定与 AOI 变化在场景逻辑线程。
 * 每个进场实例同时只有一次读取。读取期间又收到提示时标脏，丢弃旧结果再读，避免同 epoch 的队长转让旧结果倒灌。
 * 入队 / 显式归队只跟随，保留 / 离队仅刷新；进场的队长才扇出，战斗解冻后的检查不扇出。
 * 同节点换场景沿用原通道；同场景水平距离超过 8 m 或双向不可见时，用 2.10 原语瞬移并保留落位等待。
 */
public final class TeamFollowService implements TeamFollow {

    private static final Logger log = LoggerFactory.getLogger(TeamFollowService.class);
    static final double REGROUP_DISTANCE = 8.0;

    /** 生产读取 SQL 权威成员索引与队伍投影；不得阻塞逻辑线程。 */
    @FunctionalInterface
    public interface MembershipReads {
        CompletionStage<TeamMembership> readAsync(long playerId);
    }

    @FunctionalInterface
    public interface BattleLockProbe {
        CompletionStage<Boolean> lockedAsync(long playerId);
        BattleLockProbe NONE = playerId -> CompletableFuture.completedFuture(false);
    }

    private enum Mode { REFRESH_ONLY, FOLLOW_ONLY, FOLLOW_AND_FANOUT }

    /** 只由逻辑线程读写；等待队长补读时也保留令牌，新提示会使旧归队决定失效。 */
    private static final class ReadState {
        final long ownerEpoch;
        final List<Consumer<Boolean>> completions = new ArrayList<>();
        Mode mode;
        boolean reading = true;
        boolean dirty;

        ReadState(ScenePlayer player, Mode mode) {
            this.ownerEpoch = player.ownerEpoch();
            this.mode = mode;
        }
    }

    private final MembershipReads reads;
    private final BattleLockProbe battleLocks;
    private final Executor logic;
    private final SceneMetrics metrics;
    private final Map<ScenePlayer, ReadState> pending = new IdentityHashMap<>();
    /** switchScene 同步触发的进场只能跟随，不能重新扇出。 */
    private boolean following;

    public TeamFollowService(MembershipReads reads, Executor logic, SceneMetrics metrics) {
        this(reads, BattleLockProbe.NONE, logic, metrics);
    }

    public TeamFollowService(MembershipReads reads, BattleLockProbe battleLocks, Executor logic, SceneMetrics metrics) {
        this.reads = reads;
        this.battleLocks = battleLocks;
        this.logic = logic;
        this.metrics = metrics;
    }

    @Override
    public void onEnteredScene(SceneWorld world, ScenePlayer player) {
        check(world, player, following ? Mode.FOLLOW_ONLY : Mode.FOLLOW_AND_FANOUT, null);
    }

    @Override
    public void onBattleFreezeCleared(SceneWorld world, ScenePlayer player) {
        check(world, player, Mode.FOLLOW_ONLY, null);
    }

    @Override
    public void onRefresh(SceneWorld world, ScenePlayer player, boolean follow) {
        check(world, player, follow ? Mode.FOLLOW_ONLY : Mode.REFRESH_ONLY, null);
    }

    private void check(SceneWorld world, ScenePlayer player, Mode mode, Consumer<Boolean> completion) {
        ReadState active = pending.get(player);
        if (active != null && active.reading) {
            active.dirty = true;
            if (mode.ordinal() > active.mode.ordinal()) active.mode = mode;
            if (completion != null) active.completions.add(completion);
            return;
        }
        // 已读完但在等队长提示：新提示优先，旧的跟随决定不再允许执行。
        if (active != null) finish(player, active, false);
        ReadState state = new ReadState(player, mode);
        if (completion != null) state.completions.add(completion);
        pending.put(player, state);
        read(world, player, state);
    }

    private void read(SceneWorld world, ScenePlayer player, ReadState state) {
        state.reading = true;
        state.dirty = false;
        try {
            // 两项并行，超时保证断线实例不会因为一个悬挂 future 永久留在 pending 中。
            CompletableFuture<TeamMembership> membership = reads.readAsync(player.playerId()).toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS);
            CompletableFuture<Boolean> locked = state.mode == Mode.REFRESH_ONLY
                    ? CompletableFuture.completedFuture(false) : lockRead(player.playerId());
            membership.whenComplete((value, failure) -> locked.whenComplete((isLocked, ignored) -> post(player.playerId(),
                    () -> onRead(world, player, state, value, failure, isLocked == null || isLocked))));
        } catch (RuntimeException error) {
            readFailed(player.playerId(), error);
            finish(player, state, false);
        }
    }

    private CompletableFuture<Boolean> lockRead(long playerId) {
        try {
            return battleLocks.lockedAsync(playerId).toCompletableFuture().orTimeout(5, TimeUnit.SECONDS)
                    .handle((locked, error) -> error != null || locked == null || locked);
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(true);
        }
    }

    private void post(long playerId, Runnable task) {
        try {
            logic.execute(task);
        } catch (RejectedExecutionException error) {
            // 逻辑执行器关闭后不从 I/O 线程触碰它所有的 map / 玩家 / 指标。
            log.debug("逻辑线程已停止，丢弃组队检查 player={}", Long.toUnsignedString(playerId));
        }
    }

    private void onRead(SceneWorld world, ScenePlayer player, ReadState state, TeamMembership membership,
                        Throwable failure, boolean locked) {
        if (pending.get(player) != state) return;
        if (!current(world, player, state)) {
            metrics.teamFollow(TeamFollowResult.STALE);
            finish(player, state, false);
            return;
        }
        if (state.dirty) {
            // 旧结果连 AOI 提示也不应用；新读取在旧读取完成后才发起，同 epoch 变更有确定先后。
            read(world, player, state);
            return;
        }
        state.reading = false;
        try {
            if (failure != null || membership == null) {
                readFailed(player.playerId(), failure == null ? new IllegalStateException("空成员关系") : failure);
                finish(player, state, false);
                return;
            }
            TeamInfo info = membership.info();
            if (membership.inTeam()) {
                if (membership.epoch() == 0 || info == null || info.getTeamId() != membership.teamId() || info.getLeaderId() == 0
                        || !info.getMembersList().contains(info.getLeaderId()) || info.getMembersCount() > 5
                        || new HashSet<>(info.getMembersList()).size() != info.getMembersCount()) {
                    metrics.teamFollow(TeamFollowResult.PROJECTION_MISSING);
                    finish(player, state, false);
                    return;
                }
                if (!info.getMembersList().contains(player.playerId())) {
                    metrics.teamFollow(TeamFollowResult.NOT_MEMBER);
                    finish(player, state, false);
                    return;
                }
            }
            if (!world.applyTeamMembership(player, membership.teamId(), membership.epoch())) {
                metrics.teamFollow(TeamFollowResult.STALE);
                finish(player, state, false);
                return;
            }
            if (!membership.inTeam()) {
                metrics.teamFollow(TeamFollowResult.NOT_IN_TEAM);
                finish(player, state, true);
            } else if (state.mode == Mode.REFRESH_ONLY) {
                metrics.teamFollow(TeamFollowResult.REFRESHED);
                finish(player, state, true);
            } else if (info.getLeaderId() == player.playerId()) {
                metrics.teamFollow(TeamFollowResult.IS_LEADER);
                finish(player, state, true);
                if (state.mode == Mode.FOLLOW_AND_FANOUT) {
                    for (long memberId : info.getMembersList()) {
                        ScenePlayer member = world.playerById(memberId);
                        if (memberId != player.playerId() && member != null) check(world, member, Mode.FOLLOW_ONLY, null);
                    }
                }
            } else if (!followLeader(world, player, state, info.getLeaderId(), locked)) {
                finish(player, state, true);
            }
        } catch (RuntimeException error) {
            readFailed(player.playerId(), error);
            finish(player, state, false);
        }
    }

    private boolean current(SceneWorld world, ScenePlayer player, ReadState state) {
        return world.playerById(player.playerId()) == player && player.ownerEpoch() == state.ownerEpoch;
    }

    /** 返回 true 表示在等队长补读；调用方保留令牌直到回调或新提示使它失效。 */
    private boolean followLeader(SceneWorld world, ScenePlayer player, ReadState state, long leaderId,
                                 boolean battleLocked) {
        if (world.switchInFlight(player)) {
            metrics.teamFollow(TeamFollowResult.SWITCHING);
            return false;
        }
        if (player.inBattle()) {
            metrics.teamFollow(TeamFollowResult.IN_BATTLE);
            return false;
        }
        if (battleLocked) {
            metrics.teamFollow(TeamFollowResult.BATTLE_LOCK);
            return false;
        }
        ScenePlayer leader = world.playerById(leaderId);
        if (leader == null) {
            metrics.teamFollow(TeamFollowResult.LEADER_NOT_ON_NODE);
            return false;
        }
        // 队长 PRECHECK / RESOLVING 尚未失去可跟随性；只检查已进入冻结的归属交接。
        if (leader.frozen()) {
            metrics.teamFollow(TeamFollowResult.LEADER_FROZEN);
            return false;
        }
        if (leader.teamId() != player.teamId()) {
            check(world, leader, Mode.REFRESH_ONLY, success -> {
                if (pending.get(player) != state) return;
                if (!success || !current(world, player, state)) {
                    metrics.teamFollow(TeamFollowResult.STALE);
                    finish(player, state, false);
                    return;
                }
                try {
                    if (world.playerById(leaderId) != leader || leader.teamId() != player.teamId()) {
                        metrics.teamFollow(TeamFollowResult.PROJECTION_MISSING);
                        finish(player, state, false);
                        return;
                    }
                    // 补读队长可能跨过离队 / 转让 / 战斗锁变化。重新读自己，不能继续使用等待前的决定。
                    finish(player, state, true);
                    check(world, player, state.mode, null);
                } catch (RuntimeException error) {
                    readFailed(player.playerId(), error);
                    finish(player, state, false);
                }
            });
            return true;
        }
        if (leader.scene() == player.scene()) {
            if (shouldRegroup(player.position().horizontalDistance(leader.position()), world.mutuallyVisible(player, leader))) {
                Rotation facing = leader.rotation() == null ? Rotation.getDefaultInstance() : leader.rotation();
                TeleportResult result = world.teleportWithinScene(player, leader.position(), facing, TeleportReason.TEAM_REGROUP);
                metrics.teamFollow(result == TeleportResult.TELEPORTED ? TeamFollowResult.REGROUPED : TeamFollowResult.REGROUP_REJECTED);
            } else {
                metrics.teamFollow(TeamFollowResult.SAME_SCENE);
            }
            return false;
        }
        if (leader.scene().draining()) {
            metrics.teamFollow(TeamFollowResult.LEADER_SCENE_DRAINING);
            return false;
        }
        // 换场景会同步进入下一次检查；旧状态必须先退休，不能把新场景检查合进已完成的读取。
        finish(player, state, true);
        boolean outer = following;
        following = true;
        try {
            world.switchScene(player, leader.scene());
        } finally {
            following = outer;
        }
        metrics.teamFollow(TeamFollowResult.FOLLOWED);
        return false;
    }

    static boolean shouldRegroup(double horizontalDistance, boolean mutuallyVisible) {
        return horizontalDistance > REGROUP_DISTANCE || !mutuallyVisible;
    }

    private void finish(ScenePlayer player, ReadState state, boolean success) {
        if (!pending.remove(player, state)) return;
        for (Consumer<Boolean> completion : state.completions) completion.accept(success);
    }

    private void readFailed(long playerId, Throwable failure) {
        metrics.teamFollow(TeamFollowResult.READ_ERROR);
        log.warn("读组队成员关系失败，本次不跟随 player={}: {}", Long.toUnsignedString(playerId), failure.toString());
    }
}
