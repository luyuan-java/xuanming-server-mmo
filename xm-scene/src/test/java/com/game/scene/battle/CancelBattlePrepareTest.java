package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.PlayerLeave;
import com.game.discovery.battle.BattleRedis;
import com.game.proto.PrepareBattleResponse;
import com.game.scene.battle.BattleFreeze.Phase;
import com.game.scene.testing.FakeBattleLocks.Call;
import com.game.scene.testing.FakeBattleLocks.Op;
import com.game.scene.world.ScenePlayer;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * 取消备战 {@code cancelBattlePrepare}（scene-battle-spec §7.6 的表、§13.2；基线 {@code CancelBattlePrepare}，{@code pb.cpp:1233-1310}）：
 * <ul>
 *   <li>玩家不在本节点：一段 {@code CANCEL_OFFLINE}，1 / 2 / 0 与出错各计各的，应答等脚本结局；</li>
 *   <li>在线、没有冻结：忽略、<b>不碰锁</b>（B2）；battle_id 不符：忽略；FIGHTING：拒绝（B1）；</li>
 *   <li>PREPARING：立刻解冻，发「只删备战锁」的条件删除，应答与组队跟随的补查都等删锁结局；</li>
 *   <li>PREPARING 且写锁在途：只记下，写锁完成后才删（Redisson 可能把删锁排到写锁之前，§1.8）；</li>
 *   <li>第二轮修正 R2-a：删锁在途期间现任实例（同 epoch 重进的新实例，或重跑进场恢复的原实例）按这把还没删掉的锁重建出来的备战冻结，
 *       在删锁有了结局时一并摘掉（返回 2 = 锁已确认开战时除外；FIGHTING 的、别的局的不动）；</li>
 *   <li>评审 R2-REV-1：删锁（或离线取消的脚本）有了结局时发起它的实例已换 / 本来就没有实例，而现任实例上没有冻结可摘——锁确实是这一次删掉的
 *       （返回 1）就给现任实例补一次组队跟随；返回 0 / 2 / 出错、现任实例战斗在途、玩家已离场都不补。</li>
 * </ul>
 * 取消与紧随其后的确认乱序（审计 FRZ-7）在 {@link BattleLockDeletionRegressionTest}；回调出错 / 逻辑线程已停时应答仍完成在
 * {@link BattleCallbackFailureTest}。
 */
class CancelBattlePrepareTest {

    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    /** 不在本节点的玩家。 */
    private static final long ABSENT = 2002;
    private static final long X = 7;
    private static final long Y = 8;
    private static final int NODE = BattleFixture.BATTLE_NODE;
    private static final int FEATURE_UNAVAILABLE = 1006;
    private static final int SERVICE_UNAVAILABLE = 1003;
    private static final int ATTRIBUTE_IN_BATTLE = 25011;
    private static final List<String> RESULTS = List.of("cleared", "idempotent", "mismatch", "rejected_fighting", "deferred",
            "offline_deleted", "offline_rejected_fighting", "offline_absent", "offline_error");

    private final BattleFixture f = new BattleFixture();

    /** 全部取消计数里只有这一个取值是 1，其余是 0（一次取消恰好计一次）。 */
    private void assertCountedOnce(String result) {
        Map<String, Double> expected = new TreeMap<>();
        Map<String, Double> actual = new TreeMap<>();
        for (String each : RESULTS) {
            expected.put(each, each.equals(result) ? 1.0 : 0.0);
            actual.put(each, f.cancels(each));
        }
        assertThat(actual).isEqualTo(expected);
    }

    // ------------------------------------------------------------------ 玩家不在本节点：一段 CANCEL_OFFLINE

    @Test
    void 离线_备战锁是这一局_删掉_计offline_deleted_应答等脚本结局() {
        f.locks.putLock(ABSENT, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        f.locks.hold(Op.CANCEL_OFFLINE);

        CompletableFuture<Void> done = f.cancel(ABSENT, X);

        assertThat(done).as("应答等脚本结局").isNotDone();
        Call script = f.locks.take(Op.CANCEL_OFFLINE);
        assertThat(script.playerId()).isEqualTo(ABSENT);
        assertThat(script.battleId()).isEqualTo(X);
        script.complete();
        assertThat(done).as("结局还没回到逻辑线程").isNotDone();
        f.drain();

        assertThat(done).isCompleted();
        assertThat(script.lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_DONE);
        assertThat(f.locks.lock(ABSENT)).isNull();
        assertThat(f.locks.ops()).as("一次往返").containsExactly(Op.CANCEL_OFFLINE);
        assertCountedOnce("offline_deleted");
    }

    /** B1 的离线形态：锁上已是 F = 确认已到、房间建成过，这时的取消是建房超时后的过期回滚，不能把在打的局的锁删掉。 */
    @Test
    void 离线_锁已确认开战_拒绝_锁的字段与TTL都不动_计offline_rejected_fighting() {
        long deadline = f.deadline();
        f.locks.putLock(ABSENT, X, NODE, BattleRedis.STATE_FIGHTING, deadline, f.prepareDeadline(), 360);

        CompletableFuture<Void> done = f.cancel(ABSENT, X);
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.last(Op.CANCEL_OFFLINE).lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_FIGHTING);
        assertThat(f.locks.lock(ABSENT)).containsEntry("b", "7").containsEntry("s", "F").containsEntry("d", String.valueOf(deadline));
        assertThat(f.locks.lockTtlSec(ABSENT)).isEqualTo(360);
        assertCountedOnce("offline_rejected_fighting");
    }

    @Test
    void 离线_没有锁_幂等忽略_计offline_absent() {
        CompletableFuture<Void> done = f.cancel(ABSENT, X);
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.last(Op.CANCEL_OFFLINE).lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_MISS);
        assertThat(f.locks.ops()).containsExactly(Op.CANCEL_OFFLINE);
        assertCountedOnce("offline_absent");
    }

    @Test
    void 离线_锁已是别的局_不动别人的锁_计offline_absent() {
        f.locks.putLock(ABSENT, Y, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);

        CompletableFuture<Void> done = f.cancel(ABSENT, X);
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.lockBattleId(ABSENT)).isEqualTo(Y);
        assertThat(f.locks.lockState(ABSENT)).isEqualTo("P");
        assertThat(f.locks.lockTtlSec(ABSENT)).isEqualTo(120);
        assertCountedOnce("offline_absent");
    }

    @Test
    void 离线_脚本出错_计offline_error_应答照常完成_锁留给TTL() {
        f.locks.putLock(ABSENT, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        f.locks.failNext(Op.CANCEL_OFFLINE, new RuntimeException("Redis 超时"));

        CompletableFuture<Void> done = f.cancel(ABSENT, X);
        assertThat(done).isNotDone();
        f.drain();

        assertThat(done).as("取消只管尽力，出错也给调用方一个结局").isCompleted();
        assertThat(f.locks.lockBattleId(ABSENT)).isEqualTo(X);
        assertThat(f.locks.ops()).as("不重试").containsExactly(Op.CANCEL_OFFLINE);
        assertCountedOnce("offline_error");
    }

    /** §7.2 重放语义：首发已把锁删掉、回复超时、Redisson 原样重发 → 第二遍回 0，计 offline_absent，无害。 */
    @Test
    void 离线_脚本被重放_第二遍回0计offline_absent_锁已删() {
        f.locks.putLock(ABSENT, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        f.locks.replayNext(Op.CANCEL_OFFLINE);

        CompletableFuture<Void> done = f.cancel(ABSENT, X);
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.last(Op.CANCEL_OFFLINE).executions()).isEqualTo(2);
        assertThat(f.locks.lock(ABSENT)).isNull();
        assertCountedOnce("offline_absent");
    }

    /**
     * 第二轮修正 R2-a 用在离线取消上：取消到达时玩家不在本节点（CANCEL_OFFLINE 在途），脚本执行之前玩家进场——恢复读看到了那把还没删掉的备战锁，
     * 按它重建出 PREPARING 冻结（复核命中）；随后脚本把锁删了。这一局已被取消，刚进场的实例上按锁重建的冻结一并摘掉、当场补一次组队跟随，
     * 不留一把没有锁的冻结到备战期限。
     */
    @Test
    void 离线取消在途时玩家进场按锁重建了备战冻结_脚本删锁后一并摘掉() {
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        f.locks.hold(Op.CANCEL_OFFLINE);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        assertThat(f.locks.pending(Op.CANCEL_OFFLINE)).as("发出时玩家不在本节点：走离线取消").hasSize(1);
        ScenePlayer player = f.enter(SESSION, PLAYER);
        BattleFreeze rebuilt = player.battle().freeze();
        assertThat(rebuilt).as("进场恢复看到了还没删掉的备战锁").isNotNull();
        assertThat(rebuilt.phase()).isEqualTo(Phase.PREPARING);
        assertThat(rebuilt.preparedHere()).isFalse();
        assertThat(f.rebuilds("login", "rebuilt")).isEqualTo(1);
        assertThat(done).isNotDone();

        Call script = f.locks.take(Op.CANCEL_OFFLINE).complete();
        f.drain();

        assertThat(script.lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_DONE);
        assertThat(done).isCompleted();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(player.inBattle()).as("锁删掉的同时，按它重建的备战冻结一并摘掉").isFalse();
        assertThat(f.rebuilds("login", "reverted")).isEqualTo(1);
        assertThat(f.follows.freezeClearedPlayers).containsExactly(player);
        assertCountedOnce("offline_deleted");
        assertThat(BattleFixture.tipOf(f.prepared(PLAYER, Y))).as("立刻可以备战下一局").isZero();
    }

    /** 上一条的守卫：离线取消被拒（返回 2，锁已确认开战）时，进场实例按锁重建的冻结不动——这一局在打。 */
    @Test
    void 离线取消在途时玩家进场按已确认的锁重建了冻结_脚本被拒_冻结保留() {
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), f.prepareDeadline(), 360);
        f.locks.hold(Op.CANCEL_OFFLINE);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        ScenePlayer player = f.enter(SESSION, PLAYER);
        BattleFreeze rebuilt = player.battle().freeze();
        assertThat(rebuilt.phase()).isEqualTo(Phase.FIGHTING);

        Call script = f.locks.take(Op.CANCEL_OFFLINE).complete();
        f.drain();

        assertThat(script.lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_FIGHTING);
        assertThat(done).isCompleted();
        assertThat(player.battle().freeze()).isSameAs(rebuilt);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
        assertThat(f.rebuilds("login", "reverted")).isZero();
        assertThat(f.follows.freezeCleared).isEmpty();
        assertCountedOnce("offline_rejected_fighting");
    }

    /**
     * 评审 R2-REV-1 的同一条规则用在离线取消上：CANCEL_OFFLINE 在途时玩家进场，他的恢复读排在删除<b>之后</b>才执行——读不到锁、不重建，
     * 没有冻结可摘（上上条的 R2-a 不适用）。但他进场那次组队跟随检查先于恢复读发出，读到的还是这把备战锁、已经放弃；
     * 脚本确实把锁删了（返回 1）→ 给现任实例补一次，否则再没有触发点。
     */
    @Test
    void 离线取消在途时玩家进场_恢复读排在删除之后没有重建_脚本删到了锁_给现任实例补一次跟随() {
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        f.locks.hold(Op.CANCEL_OFFLINE, Op.ENTER_READ);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        ScenePlayer player = f.enter(SESSION, PLAYER);
        assertThat(player.battle().freeze()).as("恢复读还没执行：没有重建").isNull();

        Call script = f.locks.take(Op.CANCEL_OFFLINE).complete();
        f.drain();

        assertThat(script.lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_DONE);
        assertThat(done).isCompleted();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeClearedPlayers).as("锁是这次删掉的：给现任实例补一次").containsExactly(player);
        assertThat(f.rebuilds("login", "reverted")).isZero();
        assertCountedOnce("offline_deleted");

        f.locks.take(Op.ENTER_READ).complete();
        f.drain();
        assertThat(player.inBattle()).as("恢复读看到的已是「没有锁」").isFalse();
        assertThat(player.battle().recovery()).isEqualTo(PlayerBattle.Recovery.READY);
        assertThat(f.follows.freezeClearedPlayers).as("不再补第二次").containsExactly(player);
    }

    /** 上一条的守卫：离线取消没有删到锁（锁不在，返回 0；或脚本出错）时，不给期间进场的实例补跟随——没有锁是被这一次删掉的。 */
    @Test
    void 离线取消在途时玩家进场_脚本没有删到锁_返回0或出错_不补跟随() {
        for (boolean failed : List.of(false, true)) {
            BattleFixture g = new BattleFixture();
            if (failed) {
                g.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, g.deadline(), g.prepareDeadline(), 120);
            }
            g.locks.hold(Op.CANCEL_OFFLINE, Op.ENTER_READ);
            CompletableFuture<Void> done = g.cancel(PLAYER, X);
            ScenePlayer player = g.enter(SESSION, PLAYER);

            Call script = g.locks.take(Op.CANCEL_OFFLINE);
            if (failed) {
                script.fail(new RuntimeException("Redis 超时"));
            } else {
                script.complete();
                assertThat(script.lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_MISS);
            }
            g.drain();

            assertThat(done).as("出错=%s", failed).isCompleted();
            assertThat(player.inBattle()).isFalse();
            assertThat(g.follows.freezeCleared).as("出错=%s：这一次没有删掉锁，不补", failed).isEmpty();
            assertThat(g.cancels(failed ? "offline_error" : "offline_absent")).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ 忽略与拒绝（都不碰锁）

    @Test
    void player_id或battle_id为0_直接忽略_零Redis调用() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        BattleFreeze freeze = player.battle().freeze();
        f.locks.clearCalls();

        CompletableFuture<Void> noPlayer = f.cancel(0, X);
        CompletableFuture<Void> noBattle = f.cancel(PLAYER, 0);

        assertThat(noPlayer).isCompleted();
        assertThat(noBattle).isCompleted();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(player.battle().freeze()).isSameAs(freeze);
        assertThat(f.cancels("mismatch")).isEqualTo(2);
        assertThat(f.cancels("cleared") + f.cancels("offline_absent") + f.cancels("offline_deleted")).isZero();
    }

    /**
     * B2：玩家在线、没有冻结（备战到期被 reaper 摘了冻结、锁留到 TTL 给迟到的确认）。取消是幂等忽略，<b>不碰锁</b>——
     * 删了的话随后到达的确认就没法据锁重建。
     */
    @Test
    void 在线没有冻结_忽略_不碰还留着的锁_计idempotent() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);

        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.calls()).as("在线分支不发 CANCEL_OFFLINE，也不发删除").isEmpty();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(120);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.follows.freezeCleared).isEmpty();
        assertCountedOnce("idempotent");
    }

    /** 迟到的取消（上一局的）不得解冻下一局。 */
    @Test
    void battle_id不符_忽略_冻结与锁都不动_计mismatch() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        BattleFreeze freeze = player.battle().freeze();
        f.locks.clearCalls();

        CompletableFuture<Void> done = f.cancel(PLAYER, Y);
        f.drain();

        assertThat(done).isCompleted();
        assertThat(player.battle().freeze()).isSameAs(freeze);
        assertThat(freeze.phase()).isEqualTo(Phase.PREPARING);
        assertThat(freeze.cancelRequested()).isFalse();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
        assertThat(f.follows.freezeCleared).isEmpty();
        assertCountedOnce("mismatch");
    }

    /** B1：确认已到 = 房间建成过，这时的取消是 CreateBattle 超时后的过期回滚，拒绝。 */
    @Test
    void 已确认开战_拒绝取消_冻结与锁都不动_计rejected_fighting() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        BattleFreeze freeze = player.battle().freeze();
        long ttl = f.locks.lockTtlSec(PLAYER);
        f.locks.clearCalls();

        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        f.drain();

        assertThat(done).isCompleted();
        assertThat(player.battle().freeze()).isSameAs(freeze);
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(ttl);
        assertThat(f.follows.freezeCleared).isEmpty();
        assertCountedOnce("rejected_fighting");
    }

    // ------------------------------------------------------------------ PREPARING：解冻并条件删锁

    /**
     * 在线 PREPARING 的取消：当场解冻（在途闸立刻放开），发一条「只删备战锁」（b == X 且 s ≠ F 才删，审计 FRZ-7；不用不看 s 的
     * DELETE_IF_MATCH，也不用 CANCEL_OFFLINE）。应答与组队跟随的补查都等删锁结局：跟随链会读锁，删锁完成之前读到的还是「在途」。
     */
    @Test
    void 备战中_立刻解冻并条件删锁_删锁完成后才完成应答并触发跟随复查() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.clearCalls();
        f.locks.hold(Op.DELETE_PREPARING);

        CompletableFuture<Void> done = f.cancel(PLAYER, X);

        assertThat(player.inBattle()).as("取消当场解冻").isFalse();
        assertThat(f.attributes.createScheme(player, "解冻了").tipId()).as("在途闸立刻放开").isNotEqualTo(ATTRIBUTE_IN_BATTLE);
        assertThat(f.locks.ops()).containsExactly(Op.DELETE_PREPARING);
        assertThat(f.locks.take(Op.DELETE_PREPARING).playerId()).isEqualTo(PLAYER);
        assertThat(f.locks.take(Op.DELETE_PREPARING).battleId()).isEqualTo(X);
        assertThat(done).as("应答等删锁结局").isNotDone();
        assertThat(f.follows.freezeCleared).as("锁还没删，不补跟随").isEmpty();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);

        Call delete = f.locks.take(Op.DELETE_PREPARING).complete();
        assertThat(done).as("结局还没回到逻辑线程").isNotDone();
        assertThat(f.follows.freezeCleared).isEmpty();
        f.drain();

        assertThat(delete.lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_DONE);
        assertThat(done).isCompleted();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeCleared).as("删锁完成后补一次跟随").containsExactly(PLAYER);
        assertThat(f.locks.ops()).as("只有这一条脚本").containsExactly(Op.DELETE_PREPARING);
        assertThat(f.reconnectHints(player)).isEmpty();
        assertCountedOnce("cleared");
    }

    @Test
    void 备战中_取消后同一局可以重新备战() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.cancel(PLAYER, X);
        f.drain();
        assertThat(f.locks.lock(PLAYER)).isNull();

        PrepareBattleResponse again = f.prepared(PLAYER, X);

        assertThat(BattleFixture.tipOf(again)).isZero();
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.PREPARING);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
    }

    /** 重复取消幂等：第二次到达时已经没有冻结 → 按「在线没有冻结」忽略，不再发第二条删除。 */
    @Test
    void 备战中_重复取消_第二次忽略_只删一次锁() {
        f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.clearCalls();
        f.locks.hold(Op.DELETE_PREPARING);

        CompletableFuture<Void> first = f.cancel(PLAYER, X);
        CompletableFuture<Void> second = f.cancel(PLAYER, X);

        assertThat(second).as("第二次当场完成").isCompleted();
        assertThat(first).isNotDone();
        f.locks.completeAll();
        f.drain();

        assertThat(first).isCompleted();
        assertThat(f.locks.ops()).containsExactly(Op.DELETE_PREPARING);
        assertThat(f.follows.freezeCleared).containsExactly(PLAYER);
        assertThat(f.cancels("cleared")).isEqualTo(1);
        assertThat(f.cancels("idempotent")).isEqualTo(1);
    }

    /** 删锁脚本出错：解冻已经生效，应答照常完成、跟随照常补（读到残留的锁会自己放弃）；锁留给 TTL，不重试。 */
    @Test
    void 备战中_删锁出错_解冻已生效_应答与跟随照常_锁留给TTL() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.clearCalls();
        f.locks.failNext(Op.DELETE_PREPARING, new RuntimeException("Redis 超时"));

        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        assertThat(done).isNotDone();
        f.drain();

        assertThat(done).isCompleted();
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.lockBattleId(PLAYER)).as("没删掉，等 TTL").isEqualTo(X);
        assertThat(f.locks.ops()).as("不重试").containsExactly(Op.DELETE_PREPARING);
        assertThat(f.follows.freezeCleared).containsExactly(PLAYER);
        assertCountedOnce("cleared");
    }

    /** 删除被重放（首发已删、回复丢了、重发回 0）：与删到了同样收尾。 */
    @Test
    void 备战中_删锁被重放_第二遍回0_照常收尾() {
        f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.clearCalls();
        f.locks.replayNext(Op.DELETE_PREPARING);

        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.last(Op.DELETE_PREPARING).lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_MISS);
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeCleared).containsExactly(PLAYER);
    }

    /** 删锁结局回来时实例已换（玩家离场了）：不对已经不在的实例补跟随；应答照常完成。 */
    @Test
    void 备战中_删锁完成时玩家已离场_不触发跟随_应答照常完成() {
        f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.DELETE_PREPARING);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        f.world.onPlayerLeave(BattleFixture.LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER)
                .setVoluntary(true).build());
        assertThat(f.world.playerById(PLAYER)).isNull();

        f.locks.take(Op.DELETE_PREPARING).complete();
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeCleared).isEmpty();
    }

    /**
     * 删锁结局回来时同一名玩家已重进（同 epoch，新实例），新实例的恢复读排在删锁之后才执行——读不到锁、不重建，新实例上没有可摘的冻结
     * （排在删锁之前的那种交错见后面几条）。但新实例进场那次组队跟随检查是先于恢复读发出的，读到的还是这把没删掉的锁、已经放弃：
     * 锁确实是这一次删掉的（返回 1），「删完补一次」不能跟着旧实例一起丢，要落到<b>现任实例</b>上（评审 R2-REV-1；与销账回调的 R2-f 同一种丢失）——
     * 否则删完之后再没有触发点，队员要等下一次跟随事件才归队。
     */
    @Test
    void 备战中_删锁完成时实例已换成新实例_锁是这次删掉的_给新实例补一次跟随() {
        ScenePlayer old = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.DELETE_PREPARING, Op.ENTER_READ);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        assertThat(fresh).isNotSameAs(old);
        assertThat(fresh.battle().freeze()).as("已取消的冻结不会被沿用").isNull();
        assertThat(f.follows.entered).as("新实例进场那次跟随检查已经发出（此刻锁还在）").containsExactly(PLAYER);
        f.follows.clear();

        Call delete = f.locks.take(Op.DELETE_PREPARING).complete();
        assertThat(f.follows.freezeCleared).as("结局还没回到逻辑线程").isEmpty();
        f.drain();

        assertThat(delete.lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_DONE);
        assertThat(done).isCompleted();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeClearedPlayers).as("补给现任实例，恰好一次；不拿已被移除的旧实例去补").containsExactly(fresh);
        assertThat(f.rebuilds("login", "reverted")).as("新实例上没有冻结可摘：不是 R2-a 那条路径补的").isZero();

        f.locks.take(Op.ENTER_READ).complete();
        f.drain();
        assertThat(fresh.inBattle()).isFalse();
        assertThat(fresh.battle().recovery()).isEqualTo(PlayerBattle.Recovery.READY);
        assertThat(f.follows.freezeClearedPlayers).as("恢复读回来没有再补第二次").containsExactly(fresh);
    }

    /**
     * R2-REV-1 判的是<b>返回 1</b>（锁确实是这一次删掉的）。实例已换而这次删除没有删到锁——锁早已不在（返回 0）、已被确认标成 F 拒删（返回 2）、
     * 出错 / 超时——都不给新实例补：它进场那次检查不是被「这一次删掉的锁」挡下的，或者锁还留着、补了也是空跑。
     * （解冻的那个实例还在时不看返回值照补，见前面「删锁出错」「删锁被重放」两条；这里只收紧「实例已换」这一支。）
     */
    @Test
    void 备战中_删锁完成时实例已换成新实例_这次没有删到锁_返回0或2或出错_不给新实例补跟随() {
        for (String outcome : List.of("返回0", "返回2", "出错")) {
            BattleFixture g = new BattleFixture();
            ScenePlayer old = g.enter(SESSION, PLAYER);
            g.prepared(PLAYER, X);
            g.locks.hold(Op.DELETE_PREPARING, Op.ENTER_READ);
            CompletableFuture<Void> done = g.cancel(PLAYER, X);
            ScenePlayer fresh = g.reenter(SESSION + 1, PLAYER, 1, g.scene1);
            assertThat(fresh).isNotSameAs(old);
            g.follows.clear();

            Call delete = g.locks.take(Op.DELETE_PREPARING);
            switch (outcome) {
                case "返回0" -> {
                    // 锁在删除执行之前就没了（过期 / 被别处删掉）
                    g.locks.removeLock(PLAYER);
                    delete.complete();
                    assertThat(delete.lastReply()).as(outcome).isEqualTo(BattleRedis.PREPARING_DELETE_MISS);
                }
                case "返回2" -> {
                    // 删除被排到了确认之后：锁已标 F
                    g.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, g.deadline(), g.prepareDeadline(), 360);
                    delete.complete();
                    assertThat(delete.lastReply()).as(outcome).isEqualTo(BattleRedis.PREPARING_DELETE_FIGHTING);
                }
                default -> delete.fail(new RuntimeException("Redis 超时"));
            }
            g.drain();

            assertThat(done).as(outcome).isCompleted();
            assertThat(fresh.inBattle()).as(outcome).isFalse();
            assertThat(g.follows.freezeCleared).as("%s：这一次没有删掉锁，不补", outcome).isEmpty();
        }
    }

    /**
     * R2-REV-1 的守卫：现任实例<b>有战斗冻结</b>时不补（解冻时自会再查）。旧实例的取消删锁在途时同 epoch 重进，新实例随即进了下一局；
     * 旧实例那次删除回来、删掉了上一局的锁——新实例在途，不发起跟随，它的冻结原样。
     */
    @Test
    void 备战中_删锁完成时实例已换成新实例_新实例已在下一局里_删到了也不补跟随() {
        f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.DELETE_PREPARING, Op.ENTER_READ);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        // 新实例挂上下一局的冻结（X 的锁还在，直接摆冻结；锁的事与这条守卫无关）
        BattleFreeze next = new BattleFreeze(Y, NODE, Phase.FIGHTING, f.deadline(), f.prepareDeadline(), true);
        BattleFixture.setFreeze(fresh, next);
        f.follows.clear();

        Call delete = f.locks.take(Op.DELETE_PREPARING).complete();
        f.drain();

        assertThat(delete.lastReply()).as("X 的备战锁删掉了").isEqualTo(BattleRedis.PREPARING_DELETE_DONE);
        assertThat(done).isCompleted();
        assertThat(fresh.battle().freeze()).isSameAs(next);
        assertThat(f.rebuilds("login", "reverted")).isZero();
        assertThat(f.follows.freezeCleared).as("现任实例战斗在途：不补，等它解冻时再查").isEmpty();
    }

    /**
     * 上一条的坏交错（第二轮修正 R2-a）：删锁还没在 Redis 上执行时同 epoch 重进，新实例的恢复读与复核都排在删锁之前——看到的还是那把备战锁，
     * 按锁重建出 PREPARING 冻结、复核命中；随后那条删除才执行。这一局已被 match 取消，不会有确认来，也不会再有第二次取消：
     * 删锁有了结局的那一刻，新实例上按这把锁重建的冻结<b>一并摘掉</b>（{@code rebuilds{login, reverted}}、当场给新实例补一次组队跟随），
     * 不留一把没有锁的冻结到备战期限。
     */
    @Test
    void 备战中_删锁排在新实例的恢复读与复核之后才执行_删锁完成时新实例按锁重建的冻结一并摘掉() {
        f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.DELETE_PREPARING);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.drain();
        BattleFreeze rebuilt = fresh.battle().freeze();
        assertThat(rebuilt).as("恢复读看到了还没删掉的备战锁").isNotNull();
        assertThat(rebuilt.phase()).isEqualTo(Phase.PREPARING);
        assertThat(rebuilt.preparedHere()).isFalse();
        assertThat(f.rebuilds("login", "rebuilt")).as("复核命中：此刻锁还在").isEqualTo(1);
        assertThat(done).as("取消的应答还在等删锁结局").isNotDone();
        f.follows.clear();
        f.locks.clearCalls();

        Call delete = f.locks.take(Op.DELETE_PREPARING).complete();
        assertThat(fresh.battle().freeze()).as("结局还没回到逻辑线程").isSameAs(rebuilt);
        f.drain();

        assertThat(delete.lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_DONE);
        assertThat(done).isCompleted();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(fresh.inBattle()).as("锁删掉的同时，新实例上按它重建的备战冻结一并摘掉").isFalse();
        assertThat(f.attributes.createScheme(fresh, "解冻了").tipId()).as("在途闸当场放开").isNotEqualTo(ATTRIBUTE_IN_BATTLE);
        assertThat(f.rebuilds("login", "reverted")).isEqualTo(1);
        assertThat(f.follows.freezeClearedPlayers).as("给现任实例补一次组队跟随，恰好一次").containsExactly(fresh);
        assertThat(f.locks.ops()).as("摘冻结不再发第二条删除").containsExactly(Op.DELETE_PREPARING);
        assertThat(f.reconnectHints(fresh)).isEmpty();
        assertThat(f.count("xm.scene.battle.freeze.expired", "phase", "preparing")).as("不是等 reaper 按期限摘的").isZero();
        assertCountedOnce("cleared");

        assertThat(BattleFixture.tipOf(f.prepared(PLAYER, Y))).as("不必等备战期限，立刻可以备战下一局").isZero();
    }

    /**
     * 删锁排在新实例的恢复读之后、复核之前，且复核的结局先回到逻辑线程：复核（TOUCH）落空，重建当场撤销；随后回来的删锁结局已经没有冻结可摘——
     * 撤销只计一次。跟随会补两次：撤销那一下补一次；删锁结局回来时（返回 1、实例已换、新实例没有冻结）按 R2-REV-1 再补一次——
     * 回调分不清撤销时那次检查是不是发在删除执行之后，多查一次无害（已同场景就什么都不做）。
     */
    @Test
    void 备战中_删锁排在新实例的恢复读之后复核之前_复核落空当场撤销_随后的删锁结局不重复撤销_跟随再补一次() {
        f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.DELETE_PREPARING, Op.TOUCH);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.drain();
        assertThat(fresh.battle().freeze()).as("按快照里的锁重建，等复核").isNotNull();
        f.follows.clear();

        Call delete = f.locks.take(Op.DELETE_PREPARING).execute();
        Call touch = f.locks.take(Op.TOUCH).complete();
        f.drain();

        assertThat(touch.lastReply()).isEqualTo(BattleRedis.TOUCH_MISS);
        assertThat(fresh.inBattle()).as("复核落空：当场撤销").isFalse();
        assertThat(f.rebuilds("login", "reverted")).isEqualTo(1);
        assertThat(f.follows.freezeClearedPlayers).containsExactly(fresh);
        assertThat(done).as("删锁的回复还没交回").isNotDone();

        delete.reply();
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(delete.lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_DONE);
        assertThat(f.rebuilds("login", "reverted")).as("没有冻结可摘：不再计一次").isEqualTo(1);
        assertThat(f.follows.freezeClearedPlayers).as("锁是这次删掉的、实例已换：再给现任实例补一次（R2-REV-1）").containsExactly(fresh, fresh);
    }

    /**
     * 同一种交错、回调次序反过来：删锁的结局先回到逻辑线程（复核还在途）→ 由删锁回调把新实例上的重建冻结摘掉；随后回来的复核落空结局
     * 发现冻结已不是它重建的那个，直接丢弃——同样撤销只计一次、跟随只补一次。
     */
    @Test
    void 备战中_删锁的结局先于复核回到逻辑线程_由删锁回调摘掉重建冻结_随后的复核落空被丢弃() {
        f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.DELETE_PREPARING, Op.TOUCH);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.drain();
        assertThat(fresh.battle().freeze()).isNotNull();
        f.follows.clear();

        f.locks.take(Op.DELETE_PREPARING).complete();
        f.drain();

        assertThat(done).isCompleted();
        assertThat(fresh.inBattle()).as("复核还没回来，删锁回调已经把它摘了").isFalse();
        assertThat(f.rebuilds("login", "reverted")).isEqualTo(1);
        assertThat(f.follows.freezeClearedPlayers).containsExactly(fresh);

        Call touch = f.locks.take(Op.TOUCH).complete();
        f.drain();

        assertThat(touch.lastReply()).isEqualTo(BattleRedis.TOUCH_MISS);
        assertThat(fresh.inBattle()).isFalse();
        assertThat(f.rebuilds("login", "reverted")).as("同一个冻结只撤销一次").isEqualTo(1);
        assertThat(f.rebuilds("login", "rebuilt")).isZero();
        assertThat(f.follows.freezeClearedPlayers).containsExactly(fresh);
    }

    /**
     * R2-a 的「结局不明」一支：删锁以出错 / 超时完成（可能删了、可能没删）。取消的语义是「这一局的备战作废」，新实例上按锁重建的冻结照样摘掉；
     * 锁若还在，留给备战 TTL。
     */
    @Test
    void 备战中_删锁结局不明_新实例按锁重建的冻结照样摘掉_锁留给TTL() {
        f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.DELETE_PREPARING);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.drain();
        assertThat(fresh.battle().freeze()).isNotNull();
        f.follows.clear();

        f.locks.take(Op.DELETE_PREPARING).fail(new RuntimeException("Redis 超时"));
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.lockBattleId(PLAYER)).as("没删掉，等 TTL").isEqualTo(X);
        assertThat(fresh.inBattle()).isFalse();
        assertThat(f.rebuilds("login", "reverted")).isEqualTo(1);
        assertThat(f.follows.freezeClearedPlayers).containsExactly(fresh);
        assertThat(f.locks.count(Op.DELETE_PREPARING)).as("不重试").isEqualTo(1);
    }

    /**
     * 删除被 Redisson 重放（首发已删、回复丢了、重发回 0）：调用方看到的是「没有可删的」，但锁确实是这次删掉的——
     * 新实例上按它重建的冻结同样摘掉（返回 0 时这把按锁重建的冻结无论如何都已经没有对应的备战锁）。
     */
    @Test
    void 备战中_删锁被重放回0_新实例按锁重建的冻结同样摘掉() {
        f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.DELETE_PREPARING);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.drain();
        assertThat(fresh.battle().freeze()).isNotNull();
        f.follows.clear();

        Call delete = f.locks.take(Op.DELETE_PREPARING).replay();
        f.drain();

        assertThat(delete.lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_MISS);
        assertThat(done).isCompleted();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(fresh.inBattle()).isFalse();
        assertThat(f.rebuilds("login", "reverted")).isEqualTo(1);
        assertThat(f.follows.freezeClearedPlayers).containsExactly(fresh);
    }

    /**
     * R2-a 的守卫——<b>删除被拒（返回 2：锁已被确认标成 F）时不摘</b>。确认在旧实例上受理（旧实例已取消、没有冻结 → 走迟到确认），它的 CONFIRM 脚本
     * 排在新实例的恢复读与复核之后才执行：新实例按当时还是 P 的锁重建出 PREPARING 冻结，随后锁被标成 F，旧实例的迟到确认回调因实例已换被丢弃。
     * 这一局在打：取消的删除回 2、锁保留，新实例的冻结也必须保留，等下一次补发确认升级。
     */
    @Test
    void 备战中_删锁被拒因为锁已确认开战_新实例按锁重建的备战冻结保留_下一次补发确认升级() {
        ScenePlayer old = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        long deadline = f.deadline();
        f.locks.hold(Op.DELETE_PREPARING, Op.CONFIRM);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        f.confirm(PLAYER, X, deadline);
        Call lateConfirm = f.locks.take(Op.CONFIRM);
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.drain();
        BattleFreeze rebuilt = fresh.battle().freeze();
        assertThat(rebuilt).as("恢复读与复核时锁还是 P").isNotNull();
        assertThat(rebuilt.phase()).isEqualTo(Phase.PREPARING);
        lateConfirm.complete();
        f.drain();
        assertThat(f.locks.lockState(PLAYER)).as("旧实例受理的确认把锁标成了 F").isEqualTo("F");
        assertThat(rebuilt.phase()).as("它的回调认的是旧实例：新实例的冻结没被升级").isEqualTo(Phase.PREPARING);
        assertThat(old).isNotSameAs(fresh);
        f.follows.clear();

        Call delete = f.locks.take(Op.DELETE_PREPARING).complete();
        f.drain();

        assertThat(delete.lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_FIGHTING);
        assertThat(done).isCompleted();
        assertThat(f.locks.lockBattleId(PLAYER)).as("锁保留").isEqualTo(X);
        assertThat(fresh.battle().freeze()).as("这一局在打：不摘").isSameAs(rebuilt);
        assertThat(f.rebuilds("login", "reverted")).isZero();
        assertThat(f.follows.freezeCleared).isEmpty();

        f.locks.release();
        f.confirm(PLAYER, X, deadline);
        f.drain();
        assertThat(rebuilt.phase()).as("下一次补发确认照常升级").isEqualTo(Phase.FIGHTING);
        assertThat(f.reconnectHints(fresh)).containsExactly(X);
    }

    /**
     * R2-a 的守卫——<b>FIGHTING 的冻结不摘</b>（单独钉「阶段」这一条）：取消之后确认紧跟着到达（旧实例已没有冻结 → 迟到确认，CONFIRM 把锁标成 F），
     * 新实例的恢复读看到的已是 F 的锁，重建出 FIGHTING 冻结（复核在途，144 还没推）。这时取消的那条删除以出错 / 超时完成（结局不明）——
     * 这一局在打，冻结必须保留；复核回来后照常推 144。
     */
    @Test
    void 备战中_删锁结局不明时新实例挂的是按已确认的锁重建的FIGHTING冻结_不摘() {
        f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.DELETE_PREPARING, Op.TOUCH);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        f.confirm(PLAYER, X, f.deadline());
        assertThat(f.locks.lockState(PLAYER)).as("迟到确认的脚本已执行：锁标成 F（回调还没回到逻辑线程）").isEqualTo("F");
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.drain();
        BattleFreeze rebuilt = fresh.battle().freeze();
        assertThat(rebuilt).isNotNull();
        assertThat(rebuilt.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(rebuilt.preparedHere()).as("复核在途：144 还没推过").isFalse();
        f.follows.clear();

        f.locks.take(Op.DELETE_PREPARING).fail(new RuntimeException("Redis 超时"));
        f.drain();

        assertThat(done).isCompleted();
        assertThat(fresh.battle().freeze()).as("在打的局：删锁结局不明也不摘").isSameAs(rebuilt);
        assertThat(f.rebuilds("login", "reverted")).isZero();
        assertThat(f.follows.freezeCleared).isEmpty();

        f.locks.take(Op.TOUCH).complete();
        f.drain();
        assertThat(f.rebuilds("login", "rebuilt")).isEqualTo(1);
        assertThat(f.reconnectHints(fresh)).as("复核命中后照常推 144").containsExactly(X);
    }

    /**
     * 同一条守卫的另一种来路（这里「阶段」与「144 已推过」两条同时成立）：确认到达新实例时内存当场升级、推 144，它的续锁脚本还在途（锁上仍是 P），
     * 这时取消的那条删除执行、把锁删了（返回 1）。冻结已是 FIGHTING = 确认到过，不是「按备战锁重建、等不到确认」的那种孤儿，这里不撤
     * （取消与确认乱序的后果由 reaper 兜，见 {@link BattleLockDeletionRegressionTest}）。
     */
    @Test
    void 备战中_删锁完成时新实例的重建冻结已被确认升级_不摘() {
        f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.DELETE_PREPARING);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.drain();
        BattleFreeze rebuilt = fresh.battle().freeze();
        assertThat(rebuilt.phase()).isEqualTo(Phase.PREPARING);
        f.locks.hold(Op.CONFIRM);
        f.confirm(PLAYER, X, f.deadline());
        assertThat(rebuilt.phase()).as("确认到达：内存当场升级，续锁脚本在途").isEqualTo(Phase.FIGHTING);
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("P");
        f.follows.clear();

        Call delete = f.locks.take(Op.DELETE_PREPARING).complete();
        f.drain();

        assertThat(delete.lastReply()).as("锁上还是 P：删掉了").isEqualTo(BattleRedis.PREPARING_DELETE_DONE);
        assertThat(done).isCompleted();
        assertThat(fresh.battle().freeze()).as("已是 FIGHTING 的冻结不归这条规则管").isSameAs(rebuilt);
        assertThat(rebuilt.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.rebuilds("login", "reverted")).isZero();
        assertThat(f.follows.freezeCleared).isEmpty();
    }

    /**
     * R2-a 的守卫——<b>别的局的冻结不摘</b>：新实例上的备战冻结是按下一局 Y 的锁重建的（X 的锁早已不在），X 的删除落空（返回 0），与 Y 无关。
     */
    @Test
    void 备战中_删锁完成时新实例挂的是别的局的重建冻结_不摘() {
        f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.DELETE_PREPARING);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        // X 的锁已不在，玩家身上是下一局 Y 的备战锁（直接摆进假 Redis）
        f.locks.putLock(PLAYER, Y, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.drain();
        BattleFreeze rebuilt = fresh.battle().freeze();
        assertThat(rebuilt.battleId()).isEqualTo(Y);
        assertThat(rebuilt.phase()).isEqualTo(Phase.PREPARING);
        assertThat(rebuilt.preparedHere()).isFalse();
        f.follows.clear();

        Call delete = f.locks.take(Op.DELETE_PREPARING).complete();
        f.drain();

        assertThat(delete.lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_MISS);
        assertThat(done).isCompleted();
        assertThat(fresh.battle().freeze()).as("Y 的冻结与 X 的删除无关").isSameAs(rebuilt);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(Y);
        assertThat(f.rebuilds("login", "reverted")).isZero();
        assertThat(f.follows.freezeCleared).isEmpty();
    }

    /**
     * R2-a 不只发生在换实例时：<b>同一个实例</b>在删锁在途期间重跑了进场恢复（这里是 reaper 对 RETRY 的重读），同样会按还没删掉的锁重建出备战冻结。
     * 删锁完成时照样摘掉；那次摘除已经给这名玩家补过组队跟随，删锁回调原有的「删完补一次」不再重复补。
     */
    @Test
    void 备战中_删锁在途时同一实例重跑进场恢复按锁重建_删锁完成时摘掉_跟随只补一次() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.DELETE_PREPARING);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        assertThat(player.inBattle()).isFalse();
        BattleFixture.setRecovery(player, PlayerBattle.Recovery.RETRY);
        f.reap();
        BattleFreeze rebuilt = player.battle().freeze();
        assertThat(rebuilt).as("重跑的恢复读看到了还没删掉的备战锁").isNotNull();
        assertThat(rebuilt.phase()).isEqualTo(Phase.PREPARING);
        assertThat(rebuilt.preparedHere()).isFalse();
        f.follows.clear();

        f.locks.take(Op.DELETE_PREPARING).complete();
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(player.inBattle()).isFalse();
        assertThat(f.rebuilds("login", "reverted")).isEqualTo(1);
        assertThat(f.follows.freezeClearedPlayers).as("摘除时补过一次，删锁回调不再补第二次").containsExactly(player);
    }

    // ------------------------------------------------------------------ PREPARING 且写锁在途：延后

    /**
     * 写锁在途时的取消只记下（cancelRequested）、当场应答、不解冻、不删锁；写锁成功回来后才解冻并删锁，备战回 1006（计 cancelled）。
     * 立刻删的话删除可能被 Redisson 排到写锁之前，留下一把没人管的备战锁。
     */
    @Test
    void 写锁在途_只记下不解冻不删锁_写锁完成后才解冻删锁_删完才补跟随() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.hold(Op.PREPARE_LOCK, Op.DELETE_PREPARING);
        CompletableFuture<PrepareBattleResponse> prepared = f.prepare(PLAYER, X);
        BattleFreeze freeze = player.battle().freeze();

        CompletableFuture<Void> done = f.cancel(PLAYER, X);

        assertThat(done).as("延后的取消当场应答").isCompleted();
        assertThat(player.battle().freeze()).as("写锁没回来之前冻结还在").isSameAs(freeze);
        assertThat(freeze.cancelRequested()).isTrue();
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK);
        assertCountedOnce("deferred");

        f.locks.take(Op.PREPARE_LOCK).complete();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(prepared))).isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK, Op.DELETE_PREPARING);
        assertThat(f.locks.lockBattleId(PLAYER)).as("删除还没执行").isEqualTo(X);
        assertThat(f.follows.freezeCleared).as("锁还没删，不补跟随").isEmpty();
        assertThat(f.prepares("cancelled")).isEqualTo(1);

        f.locks.take(Op.DELETE_PREPARING).complete();
        f.drain();

        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeCleared).containsExactly(PLAYER);
    }

    @Test
    void 写锁在途_重复取消幂等_写锁完成后只删一次() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.hold(Op.PREPARE_LOCK);
        CompletableFuture<PrepareBattleResponse> prepared = f.prepare(PLAYER, X);

        assertThat(f.cancel(PLAYER, X)).isCompleted();
        assertThat(f.cancel(PLAYER, X)).isCompleted();
        assertThat(f.cancels("deferred")).isEqualTo(2);
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK);

        f.locks.take(Op.PREPARE_LOCK).complete();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(prepared))).isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK, Op.DELETE_PREPARING);
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeCleared).containsExactly(PLAYER);
    }

    /** 写锁在途时取消的是别的局：不记 cancelRequested，写锁回来后备战照常成功。 */
    @Test
    void 写锁在途_取消的是别的局_不记下_备战照常成功() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.hold(Op.PREPARE_LOCK);
        CompletableFuture<PrepareBattleResponse> prepared = f.prepare(PLAYER, X);

        assertThat(f.cancel(PLAYER, Y)).isCompleted();
        assertThat(player.battle().freeze().cancelRequested()).isFalse();
        assertCountedOnce("mismatch");

        f.locks.take(Op.PREPARE_LOCK).complete();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(prepared))).isZero();
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.PREPARING);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
    }

    /** 记下了取消、写锁却发现锁被别的局占着：按被占收尾（1006、锁不动、不删别人的锁），取消没有别的事可做。 */
    @Test
    void 写锁在途记下取消_写锁回来是被占_不删别人的锁() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, Y, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        f.locks.hold(Op.PREPARE_LOCK);
        CompletableFuture<PrepareBattleResponse> prepared = f.prepare(PLAYER, X);
        f.cancel(PLAYER, X);

        f.locks.take(Op.PREPARE_LOCK).complete();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(prepared))).isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(Y);
        assertThat(f.prepares("lock_held")).isEqualTo(1);
        assertThat(f.prepares("cancelled")).isZero();
    }

    /** 记下了取消、写锁以出错完成：按写锁失败收尾（1003、尽力删一次备战锁），不重复删。 */
    @Test
    void 写锁在途记下取消_写锁出错_按写锁失败收尾_只尽力删一次() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.hold(Op.PREPARE_LOCK);
        CompletableFuture<PrepareBattleResponse> prepared = f.prepare(PLAYER, X);
        f.cancel(PLAYER, X);

        f.locks.take(Op.PREPARE_LOCK).executeThenFail(new RuntimeException("Redis 超时"));
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(prepared))).isEqualTo(SERVICE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK, Op.DELETE_PREPARING);
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.prepares("redis_error")).isEqualTo(1);
        assertThat(f.prepares("cancelled")).isZero();
    }

    // ------------------------------------------------------------------ 与 5.2 的交出冻结

    /** 交出冻结中的玩家没有战斗冻结（两种冻结互斥）：取消按「在线没有冻结」忽略，不碰锁、不动交出。 */
    @Test
    void 交出冻结中的玩家_取消按没有冻结忽略_不碰锁() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        f.freezeForHandOff(player);

        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
        assertThat(player.frozen()).isTrue();
        assertCountedOnce("idempotent");
    }
}
