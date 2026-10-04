package com.game.scene.team;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.discovery.proto.TeamInfo;
import com.game.discovery.team.TeamMembership;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.metrics.SceneMetrics.TeamFollowResult;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.PlayerInitializer;
import com.game.scene.world.PlayerLocations;
import com.game.scene.world.PlayerSnapshots;
import com.game.scene.world.Scene;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 组队场景跟随（team-spec §10.4）：假的成员关系读（测试决定何时、以什么结果完成）+ 手动执行的「逻辑线程」队列——
 * 读回来的结果只投递进队列，由测试在本线程上依次执行，与生产中投递回场景逻辑线程同序。场景用真的 {@link SceneWorld}。
 */
class TeamFollowServiceTest {

    private static final long LINK = 3;
    private static final long TEAM = 0x8000_0000_0000_0007L;   // ≥ 2^63：id 一律按无符号处理
    private static final long LEADER = 0x8000_0000_0000_1001L;
    private static final long M1 = 0x8000_0000_0000_1002L;
    private static final long M2 = 0x8000_0000_0000_1003L;
    private static final long OTHER_NODE_MEMBER = 0x8000_0000_0000_1004L;

    /** 一次挂起的读。 */
    private record PendingRead(long playerId, CompletableFuture<TeamMembership> future) {
    }

    /** 「Redis 里」此刻的成员关系（没有 = 索引键缺失）。 */
    private final Map<Long, TeamMembership> memberships = new HashMap<>();
    private final Set<Long> failing = new HashSet<>();
    private final Deque<PendingRead> pendingReads = new ArrayDeque<>();
    /** 发起过读的玩家（按发起顺序）。 */
    private final List<Long> readLog = new ArrayList<>();
    private final Deque<Runnable> logicQueue = new ArrayDeque<>();
    private final Executor logic = logicQueue::add;

    private SimpleMeterRegistry meters;
    private FakePlayerRepository repo;
    private SceneWorld world;
    private Scene map1;
    /** 与 map1 同配置的另一条频道（另一个场景实例）：跟随必须精确到实例，不是只到配置。 */
    private Scene map1b;
    private Scene map2;
    private int nextSession = 10;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        repo = new FakePlayerRepository();
        SceneMetrics metrics = new SceneMetrics(meters);
        TeamFollowService follow = new TeamFollowService(this::read, logic, metrics);
        world = newWorld(follow, metrics);
        map1 = world.createScene(1);
        map1b = world.createScene(1);
        map2 = world.createScene(2);
    }

    private SceneWorld newWorld(TeamFollow follow, SceneMetrics metrics) {
        return new SceneWorld(new FakeSceneTables(), Contracts.IDS, new RecordingSink(), repo,
                new AtomicLong(1000)::incrementAndGet, new ManualClock(), metrics, PlayerInitializer.NONE,
                PlayerSnapshots.NONE, PlayerLocations.NONE, follow);
    }

    private CompletableFuture<TeamMembership> read(long playerId) {
        CompletableFuture<TeamMembership> future = new CompletableFuture<>();
        pendingReads.add(new PendingRead(playerId, future));
        readLog.add(playerId);
        return future;
    }

    // ------------------------------------------------------------------ 驱动

    /** 按「Redis」此刻的内容完成全部挂起的读（结果只进逻辑队列）。 */
    private void completeReads() {
        while (!pendingReads.isEmpty()) {
            PendingRead r = pendingReads.poll();
            if (failing.contains(r.playerId())) {
                r.future().completeExceptionally(new IllegalStateException("模拟 Redis 故障"));
            } else {
                r.future().complete(memberships.getOrDefault(r.playerId(), TeamMembership.KEY_MISSING));
            }
        }
    }

    private void runLogic() {
        while (!logicQueue.isEmpty()) {
            logicQueue.poll().run();
        }
    }

    /** 读与逻辑任务交替跑到稳定；超过上限说明在循环。 */
    private void settle() {
        for (int round = 0; round < 50; round++) {
            if (pendingReads.isEmpty() && logicQueue.isEmpty()) {
                return;
            }
            completeReads();
            runLogic();
        }
        throw new AssertionError("跟随检查没有收敛（循环了）");
    }

    private ScenePlayer enter(long playerId, Scene scene) {
        int session = nextSession++;
        repo.putNewPlayer(playerId, 1);
        world.onPlayerEnter(LINK, PlayerEnter.newBuilder().setSessionId(session).setPlayerId(playerId)
                .setSceneId(scene.sceneId()).setOwnerEpoch(1).build());
        repo.completeAll();
        ScenePlayer player = world.playerById(playerId);
        assertThat(player).isNotNull();
        return player;
    }

    /** 组一支队：每个成员的索引都指向它，投影里是这份名单。 */
    private void team(long leader, long... members) {
        TeamInfo.Builder info = TeamInfo.newBuilder().setTeamId(TEAM).setLeaderId(leader);
        for (long m : members) {
            info.addMembers(m);
        }
        for (long m : members) {
            memberships.put(m, new TeamMembership(TEAM, 5, false, info.build()));
        }
    }

    private Scene sceneOf(long playerId) {
        return world.playerById(playerId).scene();
    }

    private double count(TeamFollowResult result) {
        return meters.get("xm.scene.team.follow").tag("result", result.name().toLowerCase(java.util.Locale.ROOT))
                .counter().count();
    }

    private Map<TeamFollowResult, Integer> counts() {
        Map<TeamFollowResult, Integer> out = new EnumMap<>(TeamFollowResult.class);
        for (TeamFollowResult r : TeamFollowResult.values()) {
            int c = (int) count(r);
            if (c > 0) {
                out.put(r, c);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 用例

    @Test
    void 指标启动即注册_全部结局初值为0() {
        for (TeamFollowResult r : TeamFollowResult.values()) {
            assertThat(count(r)).as(r.name()).isZero();
        }
    }

    @Test
    void 非队长进场_跟随到队长所在的场景实例() {
        team(LEADER, LEADER, M1);
        enter(LEADER, map1b);
        settle();
        readLog.clear();

        enter(M1, map1);
        settle();

        assertThat(sceneOf(M1)).as("精确到实例：同配置的另一条频道").isSameAs(map1b);
        assertThat(readLog).as("自己进场读一次，被跟随换场景后再读一次").containsExactly(M1, M1);
        assertThat(counts()).isEqualTo(Map.of(
                TeamFollowResult.IS_LEADER, 1,        // 队长进场时 M1 还不在本节点，扇出没人
                TeamFollowResult.FOLLOWED, 1,
                TeamFollowResult.SAME_SCENE, 1));     // 被跟随后的那次检查：已同场景，不再动
    }

    @Test
    void 队长进场_扇出给本节点成员_各读自己的_不循环() {
        enter(M1, map1);
        enter(M2, map1b);
        settle();
        team(LEADER, LEADER, M1, M2, OTHER_NODE_MEMBER);
        readLog.clear();

        enter(LEADER, map2);
        settle();

        assertThat(sceneOf(M1)).isSameAs(map2);
        assertThat(sceneOf(M2)).isSameAs(map2);
        assertThat(sceneOf(LEADER)).isSameAs(map2);
        assertThat(readLog).as("队长一次；成员被扇出一次、被跟随换场景后一次；不在本节点的成员不读")
                .containsExactlyInAnyOrder(LEADER, M1, M2, M1, M2);
        assertThat(count(TeamFollowResult.IS_LEADER)).isEqualTo(1);
        assertThat(count(TeamFollowResult.FOLLOWED)).isEqualTo(2);
        assertThat(count(TeamFollowResult.SAME_SCENE)).isEqualTo(2);
    }

    @Test
    void 队长不在本节点_不跟随() {
        team(LEADER, LEADER, M1);

        enter(M1, map1);
        settle();

        assertThat(sceneOf(M1)).isSameAs(map1);
        assertThat(counts()).isEqualTo(Map.of(TeamFollowResult.LEADER_NOT_ON_NODE, 1));
    }

    @Test
    void 已与队长同场景_什么都不做() {
        team(LEADER, LEADER, M1);
        enter(LEADER, map1);
        settle();

        enter(M1, map1);
        settle();

        assertThat(sceneOf(M1)).isSameAs(map1);
        assertThat(count(TeamFollowResult.SAME_SCENE)).isEqualTo(1);
        assertThat(count(TeamFollowResult.FOLLOWED)).isZero();
    }

    @Test
    void 读回来前已离开_丢弃() {
        team(LEADER, LEADER, M1);
        enter(LEADER, map2);
        settle();
        enter(M1, map1);
        ScenePlayer m1 = world.playerById(M1);

        world.onPlayerLeave(LINK, PlayerLeave.newBuilder().setSessionId(nextSession - 1).setPlayerId(M1)
                .setVoluntary(true).build());
        settle();

        assertThat(world.playerById(M1)).isNull();
        assertThat(m1.scene()).as("离开时所在的场景，没被挪动").isSameAs(map1);
        assertThat(count(TeamFollowResult.STALE)).isEqualTo(1);
        assertThat(count(TeamFollowResult.FOLLOWED)).isZero();
    }

    @Test
    void 读回来前已重新进场_旧实例的结果丢弃_新实例照常跟随() {
        team(LEADER, LEADER, M1);
        enter(LEADER, map2);
        settle();
        int session = nextSession;
        enter(M1, map1);
        ScenePlayer first = world.playerById(M1);
        // 同一会话、同一 epoch 的重复进场：换成新实例（SceneWorld 的接管旧实例路径）
        world.onPlayerEnter(LINK, PlayerEnter.newBuilder().setSessionId(session).setPlayerId(M1)
                .setSceneId(map1.sceneId()).setOwnerEpoch(1).build());
        repo.completeAll();
        ScenePlayer second = world.playerById(M1);
        assertThat(second).isNotSameAs(first);

        settle();

        assertThat(count(TeamFollowResult.STALE)).as("旧实例那次").isEqualTo(1);
        assertThat(count(TeamFollowResult.FOLLOWED)).as("新实例那次").isEqualTo(1);
        assertThat(sceneOf(M1)).isSameAs(map2);
    }

    @Test
    void 无队_键缺失_投影缺失_投影对不上_不在名单_队长为0_读失败_都不跟随() {
        long tidZero = 0x8000_0000_0000_2001L;
        long keyMissing = 0x8000_0000_0000_2002L;
        long noProjection = 0x8000_0000_0000_2003L;
        long wrongTeam = 0x8000_0000_0000_2004L;
        long notMember = 0x8000_0000_0000_2005L;
        long noLeader = 0x8000_0000_0000_2006L;
        long broken = 0x8000_0000_0000_2007L;
        TeamInfo info = TeamInfo.newBuilder().setTeamId(TEAM).setLeaderId(LEADER).addMembers(LEADER).build();
        memberships.put(tidZero, new TeamMembership(0, 9, false, null));
        memberships.put(noProjection, new TeamMembership(TEAM, 9, false, null));
        memberships.put(wrongTeam, new TeamMembership(TEAM + 1, 9, false,
                info.toBuilder().setTeamId(TEAM + 1 + 1).addMembers(wrongTeam).build()));
        memberships.put(notMember, new TeamMembership(TEAM, 9, false, info));
        memberships.put(noLeader, new TeamMembership(TEAM, 9, false,
                info.toBuilder().setLeaderId(0).addMembers(noLeader).build()));
        failing.add(broken);

        for (long p : List.of(tidZero, keyMissing, noProjection, wrongTeam, notMember, noLeader, broken)) {
            enter(p, map1);
        }
        settle();

        for (long p : List.of(tidZero, keyMissing, noProjection, wrongTeam, notMember, noLeader, broken)) {
            assertThat(sceneOf(p)).isSameAs(map1);
        }
        assertThat(counts()).isEqualTo(Map.of(
                TeamFollowResult.NOT_IN_TEAM, 2,          // tid=0 + 键缺失
                TeamFollowResult.PROJECTION_MISSING, 3,   // 投影缺失 + team_id 对不上 + leader_id=0
                TeamFollowResult.NOT_MEMBER, 1,
                TeamFollowResult.READ_ERROR, 1));
    }

    @Test
    void 队员自己换图后被拉回队长身边() {
        team(LEADER, LEADER, M1);
        enter(LEADER, map1);
        enter(M1, map1);
        settle();
        readLog.clear();

        world.switchScene(world.playerById(M1), map2);
        assertThat(sceneOf(M1)).as("换图是同步的，拉回要等读回来").isSameAs(map2);
        settle();

        assertThat(sceneOf(M1)).isSameAs(map1);
        assertThat(readLog).as("自己换图后一次，被拉回后一次").containsExactly(M1, M1);
        assertThat(count(TeamFollowResult.FOLLOWED)).isEqualTo(1);
    }

    @Test
    void 入队没有进场事件_不拉人() {
        enter(LEADER, map1);
        enter(M1, map2);
        settle();
        readLog.clear();

        team(LEADER, LEADER, M1);   // 组队服务写了 Redis，但 scene 不收刷新信号（D7）
        settle();

        assertThat(readLog).isEmpty();
        assertThat(sceneOf(M1)).isSameAs(map2);
    }

    @Test
    void 被跟随触发的检查_即使此刻自己成了队长也不扇出() {
        team(LEADER, LEADER, M1, M2);
        enter(LEADER, map1);
        enter(M2, map1);
        settle();
        enter(M1, map2);
        completeReads();
        runLogic();                         // M1 读回来：跟随到 map1，换完立即发起 M1 的「被跟随」检查
        assertThat(sceneOf(M1)).isSameAs(map1);
        assertThat(pendingReads).extracting(PendingRead::playerId).containsExactly(M1);
        team(M1, LEADER, M1, M2);           // 这之间队长转让给了 M1
        readLog.clear();

        settle();

        assertThat(readLog).as("被跟随触发的检查只跟随、不扇出").isEmpty();
        assertThat(sceneOf(M2)).isSameAs(map1);
        assertThat(count(TeamFollowResult.IS_LEADER)).isEqualTo(2);   // 队长 LEADER 进场一次 + 这次
    }

    @Test
    void 被扇出的成员读到自己是队长_也不再扇出() {
        enter(M1, map1);
        enter(M2, map1);
        settle();
        team(LEADER, LEADER, M1, M2);
        enter(LEADER, map2);
        completeReads();
        runLogic();                         // 队长读回来：扇出给 M1、M2
        assertThat(pendingReads).extracting(PendingRead::playerId).containsExactlyInAnyOrder(M1, M2);
        team(M1, LEADER, M1, M2);           // 读回来之前队长转让给了 M1
        readLog.clear();

        settle();

        assertThat(readLog).as("M1 的被扇出检查不再扇出（否则会读 LEADER 与 M2）；M2 已与新队长 M1 同场景").isEmpty();
        assertThat(sceneOf(M1)).isSameAs(map1);
        assertThat(sceneOf(M2)).isSameAs(map1);
        assertThat(sceneOf(LEADER)).isSameAs(map2);
    }

    @Test
    void 读同步抛出_记读失败_不影响进场() {
        SceneMetrics metrics = new SceneMetrics(meters);
        world = newWorld(new TeamFollowService(id -> {
            throw new IllegalStateException("Redisson 已关闭");
        }, logic, metrics), metrics);
        map1 = world.createScene(1);

        enter(M1, map1);

        assertThat(world.playerById(M1).scene()).isSameAs(map1);
        assertThat(count(TeamFollowResult.READ_ERROR)).isEqualTo(1);
    }

    @Test
    void 逻辑线程已停_读回来的结果丢弃_不抛() {
        SceneMetrics metrics = new SceneMetrics(meters);
        Executor stopped = task -> {
            throw new RejectedExecutionException("逻辑线程已停止");
        };
        world = newWorld(new TeamFollowService(this::read, stopped, metrics), metrics);
        map1 = world.createScene(1);
        team(LEADER, LEADER, M1);

        enter(M1, map1);
        completeReads();

        assertThat(logicQueue).isEmpty();
        assertThat(counts()).isEmpty();
    }
}
