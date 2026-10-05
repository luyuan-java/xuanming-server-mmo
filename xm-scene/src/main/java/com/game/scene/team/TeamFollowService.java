package com.game.scene.team;

import com.game.discovery.proto.TeamInfo;
import com.game.discovery.team.TeamMembership;
import com.game.discovery.team.TeamMembershipReader;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.metrics.SceneMetrics.TeamFollowResult;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.SwitchPhase;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 组队场景跟随（team-spec §6.10，D7 / D8 / D9；基线 C++ cpp/libs/services/scene/player/system/player_team.{h,cpp}）。
 * 只做同节点跟随：队员被拉到本节点上队长所在的<b>同一个场景实例</b>。
 *
 * <p>流程（全部在场景逻辑线程上，读 Redis 异步）：
 * <ol>
 *   <li>进场 / 换场景之后（{@link #onEnteredScene}）读这个玩家的成员关系（{@link TeamMembershipReader}，一次往返读出索引与投影）；
 *       结果投递回逻辑线程；</li>
 *   <li>回调先核对实例：{@code world.playerById(pid)} 已不是发起读时的那个实例（已离开、已重新进场）→ 丢弃
 *       （基线每一跳回调的 {@code IsSamePlayer}，player_team.cpp:55-59）；</li>
 *   <li>读失败或数据损坏、无队（tid = 0 或键缺失）、投影缺失或与索引对不上（team_id 不符、leader_id = 0）、自己不在 members 里
 *       → 不跟随（player_team.cpp:290-318）；</li>
 *   <li>队长不是自己 → {@link #followLeader}：队长不在本节点（跨节点、跨 zone、离线）不跟随；已同场景什么都不做；
 *       队长所在频道在排空中不跟随（批次 5.1，scene-channels-spec §4.13）；否则 {@code world.switchScene(self, leader.scene())}。用内存里的队长场景、不读 {@code xm:location}（同节点时内存就是权威，D9），
 *       不经 scene-manager（Java 同节点换场景是同步的，没有 60 s 去重）；自己有在途的跨节点换图（选目标中或冻结中，批次 5.2）不跟随；</li>
 *   <li>队长是自己、且这次是自己进场 → 对 members 里在本节点上的其他成员各自再读一次<b>自己的</b>成员关系，按「只跟随、不扇出」处理
 *       （player_team.cpp:327-347，防循环）。</li>
 * </ol>
 * 被跟随换场景（以及被扇出后的跟随）也会触发进场钩子：那次按「只跟随、不扇出」处理；它已和队长同场景，所以什么都不做，不会循环。
 *
 * <p>与基线的差异（team-spec D7–D10）：不收 {@code PlayerTeamRefreshEvent}、不缓存 TeamId（入队 / 转让 / 踢人本来就不拉人，
 * 基线的 RefreshOnly 模式只为刷新组件）；不做队友 AOI 优先级；不读 {@code battle:lock}（6.3 前没有战斗，「冻结解除后补一次」届时再加）；
 * 基线的「归属交接在途 / 换图在途」守卫对应 Java 的跨节点换图在途（批次 5.2，{@code switchPhase ≠ NONE} 不跟随）；「会话不活」守卫
 * 在 Java 不存在（断线即移出）。
 *
 * <p>指标：每次读回来恰好计一次 {@code xm.scene.team.follow{result}}（{@link TeamFollowResult}）。
 */
public final class TeamFollowService implements TeamFollow {

    private static final Logger log = LoggerFactory.getLogger(TeamFollowService.class);

    /** 读一个玩家的成员关系（生产 = {@link TeamMembershipReader#readAsync}）。不得阻塞；失败以异常完成。 */
    @FunctionalInterface
    public interface MembershipReads {
        CompletionStage<TeamMembership> readAsync(long playerId);
    }

    /** 一次读回来之后怎么处理（基线 FollowMode，player_team.h 的 kFollowLeader / kFollowLeaderAndFanout）。 */
    private enum Mode {
        /** 自己进场：非队长跟随队长，队长扇出给本节点成员。 */
        FOLLOW_AND_FANOUT,
        /** 被跟随 / 被扇出触发：只跟随，不扇出。 */
        FOLLOW_ONLY
    }

    private final MembershipReads reads;
    private final Executor logic;
    private final SceneMetrics metrics;
    /**
     * 正在执行一次跟随换场景（{@code world.switchScene} 是同步的，换完会同步回调 {@link #onEnteredScene}）：
     * 这期间触发的进场钩子按「只跟随、不扇出」处理。只在逻辑线程上读写。
     */
    private boolean following;

    /**
     * @param reads   成员关系的异步读
     * @param logic   投递回场景逻辑线程（停服后可能拒绝，拒绝时丢弃这次结果）
     * @param metrics scene 指标
     */
    public TeamFollowService(MembershipReads reads, Executor logic, SceneMetrics metrics) {
        this.reads = reads;
        this.logic = logic;
        this.metrics = metrics;
    }

    @Override
    public void onEnteredScene(SceneWorld world, ScenePlayer player) {
        check(world, player, following ? Mode.FOLLOW_ONLY : Mode.FOLLOW_AND_FANOUT);
    }

    /** 发起一次读（逻辑线程）；结果投递回逻辑线程再处理。 */
    private void check(SceneWorld world, ScenePlayer player, Mode mode) {
        long playerId = player.playerId();
        try {
            CompletionStage<TeamMembership> read = reads.readAsync(playerId);
            read.whenComplete((membership, failure) -> post(playerId,
                    () -> onRead(world, player, mode, membership, failure)));
        } catch (RuntimeException e) {
            // 读没发出去（客户端已关闭等）：不跟随，进场 / 换场景照常
            readFailed(playerId, e);
        }
    }

    private void post(long playerId, Runnable task) {
        try {
            logic.execute(task);
        } catch (RejectedExecutionException e) {
            log.debug("逻辑线程已停止，丢弃组队跟随检查 player={}", Long.toUnsignedString(playerId));
        }
    }

    /** 读回来了（逻辑线程）。 */
    private void onRead(SceneWorld world, ScenePlayer player, Mode mode, TeamMembership membership, Throwable failure) {
        try {
            decide(world, player, mode, membership, failure);
        } catch (RuntimeException e) {
            // 钩子不得让逻辑任务带着异常结束（换场景已开始的那部分由 SceneWorld 自己保证一致）
            metrics.teamFollow(TeamFollowResult.READ_ERROR);
            log.error("组队跟随检查出错 player={}", Long.toUnsignedString(player.playerId()), e);
        }
    }

    private void decide(SceneWorld world, ScenePlayer player, Mode mode, TeamMembership membership, Throwable failure) {
        long playerId = player.playerId();
        if (world.playerById(playerId) != player) {
            metrics.teamFollow(TeamFollowResult.STALE);
            log.debug("组队跟随检查读回来时玩家已离开或已重新进场，丢弃 player={}", Long.toUnsignedString(playerId));
            return;
        }
        if (failure != null) {
            readFailed(playerId, failure);
            return;
        }
        if (!membership.inTeam()) {
            metrics.teamFollow(TeamFollowResult.NOT_IN_TEAM);
            return;
        }
        TeamInfo info = membership.info();
        if (info == null || info.getTeamId() != membership.teamId() || info.getLeaderId() == 0) {
            // 投影缺失（xm-team 下一次提交 / 续期会重写）或与索引对不上：本次不跟随，等下一次进场
            metrics.teamFollow(TeamFollowResult.PROJECTION_MISSING);
            log.debug("组队投影缺失或与索引不符，不跟随 player={} team={}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(membership.teamId()));
            return;
        }
        if (!info.getMembersList().contains(playerId)) {
            // 投影里没有自己：索引可能刚过时，等下一次进场
            metrics.teamFollow(TeamFollowResult.NOT_MEMBER);
            return;
        }
        long leaderId = info.getLeaderId();
        if (leaderId != playerId) {
            followLeader(world, player, leaderId);
            return;
        }
        metrics.teamFollow(TeamFollowResult.IS_LEADER);
        if (mode != Mode.FOLLOW_AND_FANOUT) {
            return;
        }
        // 队长自己进场：本节点上的其他成员各自再读一次自己的成员关系，只跟随、不再扇出（防循环）；不在本节点的不跨节点拉人
        for (long memberId : info.getMembersList()) {
            if (memberId == playerId) {
                continue;
            }
            ScenePlayer member = world.playerById(memberId);
            if (member != null) {
                check(world, member, Mode.FOLLOW_ONLY);
            }
        }
    }

    /** 跟随队长到它所在的场景实例（逻辑线程）。 */
    private void followLeader(SceneWorld world, ScenePlayer player, long leaderId) {
        if (player.switchPhase() != SwitchPhase.NONE) {
            // 自己有在途的跨节点换图（选目标中或冻结中，scene-handoff-spec §5.5；基线 IsFollowBlocked 查冻结或交接意图，
            // player_team.cpp:65-75）：换图的结局优先，不跟随
            metrics.teamFollow(TeamFollowResult.SWITCHING);
            log.debug("有在途的跨节点换图，不跟随 player={} 阶段={}", Long.toUnsignedString(player.playerId()),
                    player.switchPhase());
            return;
        }
        ScenePlayer leader = world.playerById(leaderId);
        if (leader == null) {
            metrics.teamFollow(TeamFollowResult.LEADER_NOT_ON_NODE);
            log.debug("队长不在本节点，不跟随 player={} leader={}", Long.toUnsignedString(player.playerId()),
                    Long.toUnsignedString(leaderId));
            return;
        }
        if (leader.scene() == player.scene()) {
            metrics.teamFollow(TeamFollowResult.SAME_SCENE);
            return;
        }
        if (leader.scene().draining()) {
            // 批次 5.1 §4.13：不跟进排空中的频道（基线跟随可以进，B5；scene-channels-spec D11）。队长随后被改派时以「自己进场」
            // 触发扇出，把本节点队员拉到它的新频道，队伍照样收拢，不会被反复拉回排空频道再改派。
            metrics.teamFollow(TeamFollowResult.LEADER_SCENE_DRAINING);
            log.debug("队长所在频道在排空，不跟随 player={} leader={} scene_id={}", Long.toUnsignedString(player.playerId()),
                    Long.toUnsignedString(leaderId), Long.toUnsignedString(leader.scene().sceneId()));
            return;
        }
        long from = player.scene().sceneId();
        long to = leader.scene().sceneId();
        boolean outer = following;
        following = true;
        try {
            world.switchScene(player, leader.scene());
        } finally {
            following = outer;
        }
        metrics.teamFollow(TeamFollowResult.FOLLOWED);
        log.info("跟随队长换场景 player={} leader={} {} -> {}", Long.toUnsignedString(player.playerId()),
                Long.toUnsignedString(leaderId), from, to);
    }

    private void readFailed(long playerId, Throwable failure) {
        metrics.teamFollow(TeamFollowResult.READ_ERROR);
        log.warn("读组队成员关系失败，本次不跟随 player={}: {}", Long.toUnsignedString(playerId), failure.toString());
    }
}
