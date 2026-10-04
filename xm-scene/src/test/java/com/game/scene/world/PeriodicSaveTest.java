package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.enterFrame;
import static com.game.scene.world.SceneWorldTest.leave;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.Facing;
import com.game.player.store.state.PlayerState;
import com.game.proto.Rotation;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakePlayerRepository.PendingProgress;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.scene.testing.RecordingSink.Kicked;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.google.protobuf.UnknownFieldSet;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 在线周期存盘：分槽、脏比对、在途、结局处理，以及持久化数据（朝向）随进场恢复。 */
class PeriodicSaveTest {

    private static final long LINK = 1;
    private static final Vec3 SAVED_AT = new Vec3(10, 20, 0);
    private static final PlayerState FACING_EAST =
            PlayerState.newBuilder().setFacing(Facing.newBuilder().setZ(90)).build();

    private RecordingSink sink;
    private FakePlayerRepository repo;
    private SimpleMeterRegistry meters;
    private SceneWorld world;
    private Scene scene;

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        repo = new FakePlayerRepository();
        meters = new SimpleMeterRegistry();
        AtomicLong ids = new AtomicLong(1000);
        world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo, ids::incrementAndGet, new ManualClock(),
                new SceneMetrics(meters));
        scene = world.createScene(1);
    }

    /** 老号：存档就在场景 1 的 SAVED_AT（进场原地落下，内存状态与库里一致）。 */
    private ScenePlayer enterUnchanged(int sessionId, long playerId) {
        repo.put(new PlayerData(playerId, 1, 2, 0, "", 5, 1, SAVED_AT, FACING_EAST));
        return enter(sessionId, playerId);
    }

    /** 新号：存档在场景 0 原点，进场落到出生点（内存状态与库里不同）。 */
    private ScenePlayer enterChanged(int sessionId, long playerId) {
        repo.putNewPlayer(playerId, 1);
        return enter(sessionId, playerId);
    }

    private ScenePlayer enter(int sessionId, long playerId) {
        world.onPlayerEnter(LINK, enterFrame(sessionId, playerId, scene.sceneId(), 1));
        repo.completeAll();
        return world.playerBySession(new SessionKey(LINK, sessionId));
    }

    private double periodic(String result) {
        return meters.counter("xm.scene.periodic.saves", "result", result).count();
    }

    @Test
    void 朝向随进场从持久化数据恢复() {
        ScenePlayer player = enterUnchanged(11, 1000);
        assertThat(player.rotation()).isEqualTo(Rotation.newBuilder().setZ(90).build());
        assertThat(player.persistentState()).isEqualTo(FACING_EAST);
    }

    @Test
    void 与库里一致的玩家到期也不写() {
        enterUnchanged(11, 1000);
        assertThat(world.saveDuePlayers(1)).isZero();
        assertThat(repo.pendingProgress()).isZero();
        assertThat(periodic("unchanged")).isEqualTo(1);
    }

    @Test
    void 有变化才写_成功后同样的状态不再写() {
        ScenePlayer player = enterChanged(11, 1000);
        assertThat(world.saveDuePlayers(1)).isEqualTo(1);
        PendingProgress p = repo.takeProgress();
        assertThat(p.save()).isEqualTo(player.toSave());
        assertThat(p.save().position()).as("出生点改派后的位置").isNotEqualTo(Vec3.ORIGIN);

        p.complete(ProgressResult.SAVED);
        assertThat(world.saveDuePlayers(1)).isZero();
        assertThat(periodic("written")).isEqualTo(1);
        assertThat(periodic("unchanged")).isEqualTo(1);
    }

    @Test
    void 按player_id分槽_每人每周期恰好到期一次() {
        enterChanged(11, 3000);
        enterChanged(12, 3001);
        enterChanged(13, 3002);

        assertThat(world.saveDuePlayers(3)).isEqualTo(1);
        assertThat(repo.takeProgress().save().playerId()).isEqualTo(3000);
        assertThat(world.saveDuePlayers(3)).isEqualTo(1);
        assertThat(repo.takeProgress().save().playerId()).isEqualTo(3001);
        assertThat(world.saveDuePlayers(3)).isEqualTo(1);
        assertThat(repo.takeProgress().save().playerId()).isEqualTo(3002);
        assertThat(repo.pendingProgress()).isZero();
    }

    @Test
    void 在途时跳过_失败后下个周期重写() {
        enterChanged(11, 1000);
        world.saveDuePlayers(1);
        PendingProgress first = repo.takeProgress();

        assertThat(world.saveDuePlayers(1)).as("上一次还没回来").isZero();
        assertThat(periodic("in_flight")).isEqualTo(1);

        first.complete(ProgressResult.FAILED);
        assertThat(world.playerBySession(new SessionKey(LINK, 11)).lastPersisted())
                .as("失败的结局未知（可能已提交），作废比对基准").isNull();
        assertThat(world.saveDuePlayers(1)).as("下次无条件重写").isEqualTo(1);
        assertThat(repo.takeProgress().save()).isEqualTo(first.save());
    }

    @Test
    void 存储积压时推到下个周期_不占在途() {
        enterChanged(11, 1000);
        repo.setAcceptsProgress(false);
        assertThat(world.saveDuePlayers(1)).isZero();
        assertThat(repo.pendingProgress()).isZero();
        assertThat(periodic("deferred")).isEqualTo(1);

        repo.setAcceptsProgress(true);
        assertThat(world.saveDuePlayers(1)).isEqualTo(1);
    }

    @Test
    void 不认识的玩法数据原样带回_更新版本写入的数据不被抹掉() {
        UnknownFieldSet future = UnknownFieldSet.newBuilder()
                .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(7).build()).build();
        PlayerState stored = FACING_EAST.toBuilder().setUnknownFields(future).build();
        repo.put(new PlayerData(1000, 1, 2, 0, "", 5, 1, SAVED_AT, stored));
        ScenePlayer player = enter(11, 1000);

        assertThat(player.persistentState()).isEqualTo(stored);
        assertThat(world.saveDuePlayers(1)).as("与库里一致（含不认识的玩法数据）").isZero();
    }

    @Test
    void 玩法数据内不认识的字段也原样带回_全0余额时货币数据不被省略() {
        UnknownFieldSet debts = UnknownFieldSet.newBuilder()
                .addField(3, UnknownFieldSet.Field.newBuilder().addVarint(7).build()).build();
        long id = 1000;
        for (CurrencyState currency : List.of(
                CurrencyState.newBuilder().addBalances(5).addBalances(0).addBalances(0).setUnknownFields(debts).build(),
                CurrencyState.newBuilder().addBalances(0).addBalances(0).addBalances(0).setUnknownFields(debts).build())) {
            PlayerState stored = FACING_EAST.toBuilder().setCurrency(currency).build();
            repo.put(new PlayerData(id, 1, 2, 0, "", 5, 1, SAVED_AT, stored));
            ScenePlayer player = enter((int) id, id);

            assertThat(player.persistentState()).isEqualTo(stored);
            id++;
        }
        assertThat(world.saveDuePlayers(1)).as("与库里一致（含玩法数据内不认识的字段）").isZero();
    }

    @Test
    void 被围栏拒绝_按失去归属移除并踢人_不再写回() {
        enterChanged(11, 1000);
        world.saveDuePlayers(1);
        repo.takeProgress().complete(ProgressResult.FENCED);

        assertThat(world.playerCount()).isZero();
        assertThat(sink.kicks()).containsExactly(new Kicked(LINK, 11, 1000, 1, SceneWorld.KICKED_BY_ANOTHER));
        assertThat(repo.saves()).as("已失去归属，写回只会被拒").isEmpty();
    }

    @Test
    void 在途期间离场_最终写回照常_迟到的结局无害() {
        enterChanged(11, 1000);
        world.saveDuePlayers(1);
        PendingProgress inFlight = repo.takeProgress();

        world.onPlayerLeave(LINK, leave(11, 1000));
        assertThat(repo.saves()).hasSize(1);

        inFlight.complete(ProgressResult.FENCED);
        assertThat(sink.kicks()).as("离场后才回来的围栏拒绝不再踢人").isEmpty();
        assertThat(world.saveDuePlayers(1)).isZero();
    }

    @Test
    void 停服后不再周期存盘() {
        enterChanged(11, 1000);
        world.shutdown();
        assertThat(world.saveDuePlayers(1)).isZero();
        assertThat(repo.pendingProgress()).isZero();
    }

    @Test
    void 存盘周期必须为正() {
        assertThatThrownBy(() -> world.saveDuePlayers(0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ 立即存盘（资产通道用）

    @Test
    void 立即存盘_与库相同不写_不同就写_在途不叠加_积压不提交_不计周期指标() {
        ScenePlayer player = enterUnchanged(11, 1000);
        assertThat(world.requestSave(player)).isEqualTo(SceneWorld.SaveRequest.UNCHANGED);
        assertThat(repo.pendingProgress()).isZero();

        player.wallet().add(0, 5);
        repo.setAcceptsProgress(false);
        assertThat(world.requestSave(player)).isEqualTo(SceneWorld.SaveRequest.DEFERRED);
        repo.setAcceptsProgress(true);
        assertThat(world.requestSave(player)).isEqualTo(SceneWorld.SaveRequest.WRITTEN);
        assertThat(world.requestSave(player)).isEqualTo(SceneWorld.SaveRequest.IN_FLIGHT);
        assertThat(repo.pendingProgress()).isEqualTo(1);
        assertThat(player.persistedState().hasCurrency()).as("结局回来之前快照不变").isFalse();

        repo.takeProgress().complete(ProgressResult.SAVED);
        assertThat(player.persistedState().getCurrency().getBalances(0)).isEqualTo(5);
        assertThat(world.requestSave(player)).isEqualTo(SceneWorld.SaveRequest.UNCHANGED);
        assertThat(periodic("written")).isZero();
    }

    @Test
    void 立即存盘_离场后或停服后不提交() {
        ScenePlayer player = enterChanged(11, 1000);
        world.onPlayerLeave(LINK, leave(11, 1000));
        assertThat(world.requestSave(player)).isEqualTo(SceneWorld.SaveRequest.STOPPED);

        ScenePlayer other = enterChanged(12, 1001);
        world.shutdown();
        assertThat(world.requestSave(other)).isEqualTo(SceneWorld.SaveRequest.STOPPED);
        assertThat(repo.pendingProgress()).isZero();
    }
}
