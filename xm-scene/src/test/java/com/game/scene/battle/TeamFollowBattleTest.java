package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SettlementDisposition;
import com.game.discovery.battle.BattleRedis;
import com.game.discovery.proto.TeamInfo;
import com.game.discovery.team.TeamMembership;
import com.game.player.store.state.PlayerState;
import com.game.proto.BattleSettlementData;
import com.game.scene.battle.BattleFreeze.Phase;
import com.game.scene.team.TeamFollowService;
import com.game.scene.testing.FakeBattleLocks.Op;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.Scene;
import com.game.scene.world.ScenePlayer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 组队跟随与回合制战斗（scene-battle-spec §7.13 世界内部第 3 条、§7.4 的解冻；§13.2 TeamFollowBattleTest 一行逐条）：
 * 真的 {@link TeamFollowService} 接在真的 {@link PlayerBattleService} 上（{@link BattleFixture} 的带工厂构造）。
 * <ul>
 *   <li>战斗在途（内存冻结）的队员不被拉走，计 {@code in_battle}；没有冻结但战斗锁还在（或读锁失败）同样不跟，计 {@code battle_lock}；</li>
 *   <li>解冻后的补跟随：要删锁的解冻（取消备战、reaper 判废）等<b>删锁完成</b>才发起检查；保留锁的解冻（备战到期、结算应用后）当场发起、
 *       读到锁而放弃；销账脚本真的放掉了锁（位 2）才补上，没放掉不补（返回 0，或只删到记录的返回 1）；</li>
 *   <li>补跟随只跟随、不扇出；读回来时实例已换按过期丢弃。</li>
 * </ul>
 * 成员关系的读是假的（测试里的一张表，立即完成）；战斗锁的读（生产 = {@code BattleLockReader.exists}）直接看假 Redis <b>发起那一刻</b>有没有锁——
 * 两个读的结果只投递进手动逻辑队列，{@code drain()} 才处理。
 */
class TeamFollowBattleTest {

    private static final long TEAM = 0x8000_0000_0000_0007L;
    private static final int LEADER_SESSION = 11;
    private static final long LEADER = 1001;
    private static final int MEMBER_SESSION = 12;
    private static final long MEMBER = 1002;
    private static final long X = 7;

    /** 「Redis 里」此刻的成员关系（没有 = 索引键缺失）。 */
    private final Map<Long, TeamMembership> memberships = new HashMap<>();
    /** 发起过成员关系读的玩家（= 发起过跟随检查），按发起顺序。 */
    private final List<Long> checks = new ArrayList<>();
    /** 发起过战斗锁读的玩家，按发起顺序。 */
    private final List<Long> lockReads = new ArrayList<>();
    /** 某个玩家的战斗锁读换成别的行为（失败、同步抛出）；没有的照常看假 Redis。 */
    private final Map<Long, LongFunction<CompletionStage<Boolean>>> lockReadOverrides = new HashMap<>();

    private final BattleFixture f = new BattleFixture(
            wiring -> new TeamFollowService(this::readMembership, this::readLock, wiring.logic(), wiring.metrics()));

    private CompletionStage<TeamMembership> readMembership(long playerId) {
        checks.add(playerId);
        return CompletableFuture.completedFuture(memberships.getOrDefault(playerId, TeamMembership.KEY_MISSING));
    }

    private CompletionStage<Boolean> readLock(long playerId) {
        lockReads.add(playerId);
        LongFunction<CompletionStage<Boolean>> override = lockReadOverrides.get(playerId);
        if (override != null) {
            return override.apply(playerId);
        }
        return CompletableFuture.completedFuture(f.locks.lock(playerId) != null);
    }

    static Stream<Phase> phases() {
        return Stream.of(Phase.PREPARING, Phase.FIGHTING);
    }

    // ================================================================== 在途 / 锁存在 / 读锁失败 → 跳过

    /** 队长进场（扇出给本节点队员）时，战斗在途的队员不被拉走，计 {@code in_battle}；同队不在战斗的队员照常跟过去。 */
    @ParameterizedTest
    @MethodSource("phases")
    void 战斗在途的队员不被拉走_计in_battle_不在战斗的队友照常跟随(Phase phase) {
        ScenePlayer inBattle = f.enter(MEMBER_SESSION, MEMBER);
        ScenePlayer idle = f.enter(MEMBER_SESSION + 1, 1003);
        freeze(MEMBER, phase);
        team(LEADER, LEADER, MEMBER, 1003);

        enterLeader(f.scene2);

        assertThat(inBattle.scene()).as("在途的不跟").isSameAs(f.scene1);
        assertThat(idle.scene()).as("对照：不在战斗的被拉到队长的场景").isSameAs(f.scene2);
        assertThat(follow("in_battle")).isEqualTo(1);
        assertThat(follow("battle_lock")).as("内存冻结先判，不落到锁那一支").isZero();
        assertThat(follow("followed")).isEqualTo(1);
        assertThat(lockReads).as("锁读与成员关系读并行发，在途的也发（不做读前预判）").contains(MEMBER);
        assertThat(inBattle.battle().freeze().phase()).isEqualTo(phase);
    }

    /** 战斗中断线重连（同 epoch 沿用冻结）：自己进场触发的那次检查同样被内存冻结挡住。 */
    @Test
    void 战斗中重连进场_沿用的冻结照样挡住跟随() {
        f.enter(MEMBER_SESSION, MEMBER);
        f.fighting(MEMBER, X);
        team(LEADER, LEADER, MEMBER);
        enterLeader(f.scene2);
        assertThat(follow("in_battle")).isEqualTo(1);
        checks.clear();

        ScenePlayer reconnected = f.reenter(MEMBER_SESSION + 100, MEMBER, 1, f.scene1);
        f.drain();

        assertThat(reconnected.battle().freeze()).as("沿用了旧实例的冻结").isNotNull();
        assertThat(checks).as("自己进场发起了一次检查").contains(MEMBER);
        assertThat(reconnected.scene()).isSameAs(f.scene1);
        assertThat(follow("in_battle")).isEqualTo(2);
        assertThat(follow("followed")).isZero();
    }

    /**
     * 没有内存冻结、但战斗锁还在（这一局还没完：备战到期只摘了冻结、或已结算待落盘）→ 不跟，计 {@code battle_lock}。
     * 同队没有锁的队友照常跟。
     */
    @Test
    void 没有内存冻结但战斗锁还在_不跟_计battle_lock() {
        ScenePlayer locked = f.enter(MEMBER_SESSION, MEMBER);
        ScenePlayer free = f.enter(MEMBER_SESSION + 1, 1003);
        f.locks.putLock(MEMBER, X, BattleFixture.BATTLE_NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        assertThat(locked.inBattle()).isFalse();
        team(LEADER, LEADER, MEMBER, 1003);

        enterLeader(f.scene2);

        assertThat(locked.scene()).as("锁在就不跟").isSameAs(f.scene1);
        assertThat(free.scene()).isSameAs(f.scene2);
        assertThat(follow("battle_lock")).isEqualTo(1);
        assertThat(follow("in_battle")).isZero();
        assertThat(follow("followed")).isEqualTo(1);
    }

    /**
     * 读锁失败按在途（fail-closed，同基线 {@code team.cpp:378-402}）：读的 future 异常完成、发读时同步抛出、读回来没有结论（null）、
     * 连 future 都没给，都不跟，计 {@code battle_lock}——只有明确读到「没有锁」才跟。
     */
    @Test
    void 读锁失败按在途_异常完成与同步抛出与没有结论都不跟() {
        ScenePlayer failed = f.enter(MEMBER_SESSION, MEMBER);
        ScenePlayer thrown = f.enter(MEMBER_SESSION + 1, 1003);
        ScenePlayer healthy = f.enter(MEMBER_SESSION + 2, 1004);
        ScenePlayer noAnswer = f.enter(MEMBER_SESSION + 3, 1005);
        ScenePlayer noFuture = f.enter(MEMBER_SESSION + 4, 1006);
        lockReadOverrides.put(MEMBER, id -> CompletableFuture.failedFuture(new IllegalStateException("模拟 Redis 超时")));
        lockReadOverrides.put(1003L, id -> {
            throw new IllegalStateException("Redisson 已关闭");
        });
        lockReadOverrides.put(1005L, id -> CompletableFuture.completedFuture(null));
        lockReadOverrides.put(1006L, id -> null);
        team(LEADER, LEADER, MEMBER, 1003, 1004, 1005, 1006);
        lockReads.clear();

        enterLeader(f.scene2);

        assertThat(failed.scene()).as("读锁异常完成：按在途").isSameAs(f.scene1);
        assertThat(thrown.scene()).as("读锁同步抛出：按在途").isSameAs(f.scene1);
        assertThat(noAnswer.scene()).as("读回来是 null（没问到结论）：按在途").isSameAs(f.scene1);
        assertThat(noFuture.scene()).as("读锁端口连 future 都没给：按在途").isSameAs(f.scene1);
        assertThat(healthy.scene()).as("对照：读到「没有锁」的照常跟").isSameAs(f.scene2);
        assertThat(follow("battle_lock")).isEqualTo(4);
        assertThat(follow("read_error")).as("锁读失败不算成员关系读失败").isZero();
        assertThat(follow("followed")).isEqualTo(1);
        assertThat(lockReads).as("队长进场扇出：每名队员都读了自己的锁").contains(MEMBER, 1003L, 1004L, 1005L, 1006L);
    }

    // ================================================================== 解冻且删锁完成后补跟随

    /**
     * 取消备战：冻结当场摘掉，但<b>删锁完成之前不发起跟随检查</b>（那时去读还能读到锁、会放弃，而之后再没有别的触发点）；
     * 删锁完成后才检查，读到没有锁 → 跟到队长所在的场景。
     */
    @Test
    void 取消备战_删锁完成之前不发起跟随_完成后跟到队长的场景() {
        ScenePlayer member = memberInBattleWithLeaderElsewhere(Phase.PREPARING);
        f.locks.hold(Op.DELETE_PREPARING);

        CompletableFuture<Void> cancelled = f.cancel(MEMBER, X);
        f.drain();

        assertThat(member.inBattle()).as("冻结已摘").isFalse();
        assertThat(cancelled).isNotDone();
        assertThat(checks).as("删锁还没完成：不发起检查").isEmpty();
        assertThat(member.scene()).isSameAs(f.scene1);

        f.locks.take(Op.DELETE_PREPARING).complete();
        f.drain();

        assertThat(cancelled).isCompleted();
        assertThat(f.locks.lock(MEMBER)).isNull();
        assertThat(checks).as("删完才检查").startsWith(MEMBER);
        assertThat(member.scene()).as("补上了跟随").isSameAs(f.scene2);
        assertThat(follow("followed")).isEqualTo(1);
        assertThat(follow("battle_lock")).isZero();
    }

    /** reaper 判废（FIGHTING 过了期限 + 宽限、没有本局记录）走条件删锁：同样等删锁完成才补跟随。 */
    @Test
    void reaper判废_条件删锁完成后才补跟随() {
        ScenePlayer member = memberInBattleWithLeaderElsewhere(Phase.FIGHTING);
        f.advance(BattleFixture.BATTLE_MILLIS + BattleRedis.FIGHTING_EXPIRY_GRACE.toMillis() + 1);
        f.locks.hold(Op.DELETE_IF_MATCH);

        f.reap();

        assertThat(member.inBattle()).as("没有本局记录：判废、冻结已摘").isFalse();
        assertThat(f.rescues("miss")).isEqualTo(1);
        assertThat(checks).as("删锁还没完成：不发起检查").isEmpty();
        assertThat(member.scene()).isSameAs(f.scene1);

        f.locks.take(Op.DELETE_IF_MATCH).complete();
        f.drain();

        assertThat(checks).startsWith(MEMBER);
        assertThat(member.scene()).isSameAs(f.scene2);
        assertThat(follow("followed")).isEqualTo(1);
    }

    // ================================================================== 保留锁的解冻不补；销账放锁后才补

    /** 备战到期（reaper 只摘冻结、锁留到 TTL）：当场发起一次检查，读到锁而放弃——不换场景。 */
    @Test
    void 备战到期保留锁的解冻_发起一次检查但读到锁而放弃() {
        ScenePlayer member = memberInBattleWithLeaderElsewhere(Phase.PREPARING);
        f.advance(BattleFixture.PREPARE_MILLIS + 1);

        f.reap();

        assertThat(member.inBattle()).isFalse();
        assertThat(f.locks.lockState(MEMBER)).as("锁保留").isEqualTo(BattleRedis.STATE_PREPARING);
        assertThat(checks).as("发起了一次检查").containsExactly(MEMBER);
        assertThat(lockReads).containsExactly(MEMBER);
        assertThat(member.scene()).as("读到锁：不补").isSameAs(f.scene1);
        assertThat(follow("battle_lock")).isEqualTo(1);
        assertThat(follow("followed")).isZero();
    }

    /**
     * 结算应用后解冻（锁续到落盘）：那次检查读到锁而放弃；等存盘落库、销账脚本<b>真的放掉了锁</b>（位 2）才补上跟随。
     */
    @Test
    void 结算应用后保留锁的解冻不补_存盘落库销账放锁后才补上跟随() {
        ScenePlayer member = memberInBattleWithLeaderElsewhere(Phase.FIGHTING);

        CompletableFuture<SceneBattleReply> reply = f.deliver(BattleFixture.settlement(MEMBER, X, 100).build());
        f.drain();

        assertThat(BattleFixture.done(reply).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(member.inBattle()).isFalse();
        assertThat(f.locks.lockBattleId(MEMBER)).as("锁留到落盘").isEqualTo(X);
        assertThat(checks).as("解冻当场检查了一次").containsExactly(MEMBER);
        assertThat(member.scene()).as("读到锁：不补").isSameAs(f.scene1);
        assertThat(follow("battle_lock")).isEqualTo(1);
        assertThat(f.locks.count(Op.ACK)).as("还没落盘，没销账").isZero();
        checks.clear();

        f.completeSaves(ProgressResult.SAVED);

        assertThat(f.locks.count(Op.ACK)).isEqualTo(1);
        assertThat(f.locks.lock(MEMBER)).as("销账放掉了锁").isNull();
        assertThat(f.acks("persisted", "released")).isEqualTo(1);
        assertThat(checks).as("放锁后补一次（被拉过去后进场钩子再查一次，已同场景）").containsExactly(MEMBER, MEMBER);
        assertThat(member.scene()).as("补上了跟随").isSameAs(f.scene2);
        assertThat(follow("followed")).isEqualTo(1);
        assertThat(follow("same_scene")).isEqualTo(1);
        assertThat(follow("battle_lock")).isEqualTo(1);
    }

    /**
     * 销账脚本没有放掉锁（位 2 为 0：这一局的锁本来就不在）→ 不补跟随。对照：这名队员没有锁也没有冻结，真发起检查的话会被拉到队长那里。
     */
    @Test
    void 销账没有放掉锁_不补跟随() {
        ScenePlayer member = f.enter(MEMBER_SESSION, MEMBER);
        f.enter(LEADER_SESSION, LEADER, 1, f.scene2, PlayerState.getDefaultInstance());
        team(LEADER, LEADER, MEMBER);
        checks.clear();

        // 没有冻结、锁不在：这一局已作废，丢弃并销账（脚本什么都没删到，返回 0）
        CompletableFuture<SceneBattleReply> reply = f.deliver(BattleFixture.settlement(MEMBER, X, 100).build());
        f.drain();

        assertThat(BattleFixture.done(reply).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_DISCARDED);
        assertThat(f.locks.count(Op.ACK)).isEqualTo(1);
        assertThat(f.locks.last(Op.ACK).lastReply()).isEqualTo(0L);
        assertThat(f.acks("discard", "not_ours")).isEqualTo(1);
        assertThat(checks).as("没放锁就不发起检查").isEmpty();
        assertThat(member.scene()).isSameAs(f.scene1);
        assertThat(follow("followed")).isZero();
    }

    /**
     * 判的是<b>位 2</b>（放了锁），不是「返回值非 0」：销账只删到了记录（返回 1）时同样不补跟随。丢弃一份过期结算（记录还在、锁早已不在）就是这种情形。
     * 对照同上：这名队员没有锁也没有冻结，真发起检查的话会被拉到队长那里。
     */
    @Test
    void 销账只删到记录没有锁可放_返回1_不补跟随() {
        ScenePlayer member = f.enter(MEMBER_SESSION, MEMBER);
        f.enter(LEADER_SESSION, LEADER, 1, f.scene2, PlayerState.getDefaultInstance());
        team(LEADER, LEADER, MEMBER);
        BattleSettlementData settlement = BattleFixture.settlement(MEMBER, X, 100).build();
        f.store(settlement);
        checks.clear();
        lockReads.clear();
        f.locks.clearCalls();

        // 没有冻结、锁不在、记录在：丢弃并销账，脚本只删到记录
        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement);
        f.drain();

        assertThat(BattleFixture.done(reply).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_DISCARDED);
        assertThat(f.locks.ops()).containsExactly(Op.READ_LOCK, Op.ACK);
        assertThat(f.locks.last(Op.ACK).lastReply()).as("只有位 1：删了记录，没有锁可放").isEqualTo(1L);
        assertThat(f.locks.hasSettlement(MEMBER, X)).isFalse();
        assertThat(f.acks("discard", "released")).isEqualTo(1);
        assertThat(member.inBattle()).isFalse();
        assertThat(checks).as("没放锁就不发起检查").isEmpty();
        assertThat(lockReads).isEmpty();
        assertThat(member.scene()).isSameAs(f.scene1);
        assertThat(follow("followed")).isZero();
    }

    /**
     * 同一条判据的另一种来路：锁在，但已是下一局 Y 的。销账只删 X 的记录（返回 1），Y 的锁不动——没有锁被放掉，不发起检查
     * （真发起的话会多读一次成员关系与战斗锁，再因为 Y 的锁而放弃）。
     */
    @Test
    void 销账只删到记录_锁是别的局的没被放掉_返回1_不发起跟随检查() {
        long next = 8;
        ScenePlayer member = f.enter(MEMBER_SESSION, MEMBER);
        f.enter(LEADER_SESSION, LEADER, 1, f.scene2, PlayerState.getDefaultInstance());
        team(LEADER, LEADER, MEMBER);
        f.locks.putLock(MEMBER, next, BattleFixture.BATTLE_NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        BattleSettlementData settlement = BattleFixture.settlement(MEMBER, X, 100).build();
        f.store(settlement);
        checks.clear();
        lockReads.clear();
        f.locks.clearCalls();

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement);
        f.drain();

        assertThat(BattleFixture.done(reply).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_DISCARDED);
        assertThat(f.locks.last(Op.ACK).battleId()).isEqualTo(X);
        assertThat(f.locks.last(Op.ACK).lastReply()).as("只有位 1：删了 X 的记录，Y 的锁不是它的").isEqualTo(1L);
        assertThat(f.locks.lockBattleId(MEMBER)).as("下一局的锁不动").isEqualTo(next);
        assertThat(f.locks.lockTtlSec(MEMBER)).isEqualTo(120);
        assertThat(f.acks("discard", "released")).isEqualTo(1);
        assertThat(checks).as("没放锁就不发起检查").isEmpty();
        assertThat(lockReads).isEmpty();
        assertThat(follow("battle_lock")).isZero();
        assertThat(member.scene()).isSameAs(f.scene1);
    }

    // ================================================================== 只跟随不扇出；实例已换

    /** 补跟随按「只跟随、不扇出」：解冻的是队长时，只读他自己的成员关系，不去拉队员（否则队员会被读到并拉过来）。 */
    @Test
    void 解冻后的补跟随只跟随不扇出_队长解冻不去拉队员() {
        ScenePlayer member = f.enter(MEMBER_SESSION, MEMBER);
        ScenePlayer leader = f.enter(LEADER_SESSION, LEADER, 1, f.scene2, PlayerState.getDefaultInstance());
        f.prepared(LEADER, X);
        team(LEADER, LEADER, MEMBER);
        checks.clear();

        f.cancel(LEADER, X);
        f.drain();

        assertThat(leader.inBattle()).isFalse();
        assertThat(checks).as("只查了队长自己，没有扇出给队员").containsExactly(LEADER);
        assertThat(follow("is_leader")).isEqualTo(1);
        assertThat(member.scene()).as("队员没有被拉走").isSameAs(f.scene1);
        assertThat(follow("followed")).isZero();
    }

    /** 补跟随的读回来时这名玩家已经重新进场（不是发起读时的那个实例）→ 丢弃，计 {@code stale}，不动新实例。 */
    @Test
    void 补跟随的读回来时实例已换_按过期丢弃() {
        memberInBattleWithLeaderElsewhere(Phase.PREPARING);
        f.advance(BattleFixture.PREPARE_MILLIS + 1);
        f.battle.reap();
        assertThat(checks).as("备战到期的解冻发起了检查，结果还在逻辑队列里").containsExactly(MEMBER);

        ScenePlayer fresh = f.reenter(MEMBER_SESSION + 100, MEMBER, 1, f.scene1);
        f.drain();

        assertThat(follow("stale")).isEqualTo(1);
        assertThat(fresh.scene()).isSameAs(f.scene1);
        assertThat(follow("followed")).isZero();
    }

    // ================================================================== 工具

    /**
     * 队员在 {@code scene1} 进入战斗（给定阶段），之后才组队、队长在 {@code scene2}（进场时队还没组，所以没有扇出）。
     * 返回队员；检查记录已清——此后任何一次检查只要读到「没有锁、没有冻结」就会把队员拉到 {@code scene2}。
     */
    private ScenePlayer memberInBattleWithLeaderElsewhere(Phase phase) {
        ScenePlayer member = f.enter(MEMBER_SESSION, MEMBER);
        f.enter(LEADER_SESSION, LEADER, 1, f.scene2, PlayerState.getDefaultInstance());
        freeze(MEMBER, phase);
        team(LEADER, LEADER, MEMBER);
        checks.clear();
        lockReads.clear();
        f.locks.clearCalls();
        return member;
    }

    private void freeze(long playerId, Phase phase) {
        if (phase == Phase.PREPARING) {
            f.prepared(playerId, X);
        } else {
            f.fighting(playerId, X);
        }
    }

    /** 队长在组好队之后进场：他的进场扇出给本节点上的队员（各自「只跟随」）。 */
    private ScenePlayer enterLeader(Scene scene) {
        return f.enter(LEADER_SESSION, LEADER, 1, scene, PlayerState.getDefaultInstance());
    }

    /** 组一支队：每个成员的索引都指向它，投影里是这份名单。 */
    private void team(long leader, long... members) {
        TeamInfo.Builder info = TeamInfo.newBuilder().setTeamId(TEAM).setLeaderId(leader);
        for (long member : members) {
            info.addMembers(member);
        }
        for (long member : members) {
            memberships.put(member, new TeamMembership(TEAM, 5, false, info.build()));
        }
    }

    private double follow(String result) {
        return f.count("xm.scene.team.follow", "result", result);
    }
}
