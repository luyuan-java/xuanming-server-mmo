package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.common.RunMode;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerState;
import com.game.proto.ListSkillsRequest;
import com.game.proto.ListSkillsResponse;
import com.game.proto.UpgradeSkillRequest;
import com.game.proto.UpgradeSkillResponse;
import com.game.scene.attribute.AttributeService;
import com.game.scene.attribute.AttributeTables;
import com.game.scene.battle.BattleFreezeTestAccess;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.skill.ClassSkillFeature;
import com.game.scene.skill.ClassSkillService;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.CurrencyAudit;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.table.ConfigTables;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 真实 77/317 分发、归属代次沿用与最终存档到另一节点加载。中间件和客户端联机另行验收。 */
class ClassSkillClientTest {
    private static ConfigTables config;
    private static ConfigSceneTables tables;

    @BeforeAll static void load() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables")) ? Path.of("../config-data/tables") : Path.of("config-data/tables");
        config = ConfigTables.load(dir);
        tables = ConfigSceneTables.from(config, dir.getParent().resolve("navmesh/current_maps"));
    }

    private static final class Fixture {
        final FakePlayerRepository repo = new FakePlayerRepository();
        final RecordingSink sink = new RecordingSink();
        final ManualClock clock = new ManualClock();
        final CurrencyService currency = new CurrencyService((CurrencyAudit) (p, t, d, b, a, r) -> { });
        final AttributeService attributes = new AttributeService(AttributeTables.from(config), clock, currency);
        final SceneWorld world = new SceneWorld(tables, Contracts.IDS, sink, repo, new AtomicLong(5000)::incrementAndGet,
                clock, SceneMetrics.noop(), attributes::initializeOnLoad, PlayerSnapshots.NONE);
        final Scene scene = world.createScene(1);
        final ClientRequestHandler handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV,
                List.of(new ClassSkillFeature(new ClassSkillService(currency, attributes), attributes, Contracts.REGISTRY)));

        Fixture(PlayerState state, long epoch) {
            repo.put(new PlayerData(7, epoch, 1, 1, "", 85, 1, tables.spawnPoint(1), state));
            enter(epoch);
        }

        void enter(long epoch) {
            world.onPlayerEnter(1, PlayerEnter.newBuilder().setSessionId(11).setPlayerId(7)
                    .setOwnerEpoch(epoch).setSceneId(scene.sceneId()).build());
            repo.completeAll();
            assertThat(player()).isNotNull();
            sink.clear();
        }

        ScenePlayer player() { return world.playerById(7); }

        UpgradeSkillResponse upgrade(int skill, int rank) throws Exception {
            handler.onClientForward(1, ClientForward.newBuilder().setSessionId(11).setPlayerId(7).setRequestId(43)
                    .setMessageId(317).setBody(UpgradeSkillRequest.newBuilder().setSkillTableId(skill)
                            .setExpectedRank(rank).setCount(1).build().toByteString()).build());
            var frame = sink.to(1, 11).getLast();
            assertThat(frame.getId()).isEqualTo(43);
            assertThat(frame.getMessageId()).isEqualTo(317);
            assertThat(frame.hasErrorMessage()).isFalse();
            return UpgradeSkillResponse.parseFrom(frame.getSerializedMessage());
        }
    }

    private static PlayerState funded() {
        return PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(10_000)).build();
    }

    @Test void 技能书下发本职业种子目录重数和余额_回显请求号() throws Exception {
        var f = new Fixture(funded(), 1);
        f.handler.onClientForward(1, ClientForward.newBuilder().setSessionId(11).setPlayerId(7).setRequestId(42)
                .setMessageId(77).setBody(ListSkillsRequest.getDefaultInstance().toByteString()).build());
        var frame = f.sink.to(1, 11).getLast();
        assertThat(frame.getId()).isEqualTo(42);
        var book = ListSkillsResponse.parseFrom(frame.getSerializedMessage());
        assertThat(book.getSkillList().getSkillListList()).extracting(e -> e.getSkillTableId()).containsExactly(1101, 1106);
        assertThat(book.getSkillList().getSkillListList()).allSatisfy(e -> assertThat(e.getRank()).isEqualTo(1));
        assertThat(book.getCatalogList()).allSatisfy(e -> assertThat(e.getClassId()).isEqualTo(1));
        assertThat(book.getClassId()).isEqualTo(1);
        assertThat(book.getPlayerLevel()).isEqualTo(85);
        assertThat(book.getCurrencyBalance()).isEqualTo(10_000);
    }

    @Test void 修习应答和重复进场保留内存重数_最终存档在下一节点完整恢复() throws Exception {
        var f = new Fixture(funded(), 1);
        assertThat(f.upgrade(1101, 1).getUpgradedCount()).isEqualTo(1);
        assertThat(f.player().wallet().balance(0)).isEqualTo(9_590);
        f.enter(1);
        assertThat(f.player().learnedSkills().rank(1101)).isEqualTo(2);
        assertThat(f.upgrade(1101, 1).getErrorMessage().getId()).isEqualTo(7008);
        f.world.onPlayerLeave(1, PlayerLeave.newBuilder().setSessionId(11).setPlayerId(7).setVoluntary(true).build());
        var save = f.repo.saves().getLast();
        assertThat(save.ownerEpoch()).isEqualTo(1);
        var next = new Fixture(save.state(), 2);
        assertThat(next.player().ownerEpoch()).isEqualTo(2);
        assertThat(next.player().learnedSkills().rank(1101)).isEqualTo(2);
        assertThat(next.player().wallet().balance(0)).isEqualTo(9_590);
        assertThat(next.upgrade(1101, 1).getErrorMessage().getId()).isEqualTo(7008);
    }

    @Test void 战斗和交接冻结经真实分发回完整拒绝状态_解冻后可修习() throws Exception {
        var f = new Fixture(funded(), 1);
        var before = f.player().persistentState();
        BattleFreezeTestAccess.freeze(f.player());
        var battle = f.upgrade(1101, 1);
        assertThat(battle.getErrorMessage().getId()).isEqualTo(7013);
        assertThat(battle.hasState()).isTrue();
        assertThat(f.player().persistentState()).isEqualTo(before);
        BattleFreezeTestAccess.clear(f.player());
        WorldTestAccess.startFreezing(f.player());
        var frozen = f.upgrade(1101, 1);
        assertThat(frozen.getErrorMessage().getId()).isEqualTo(7013);
        assertThat(frozen.hasState()).isTrue();
        assertThat(f.player().persistentState()).isEqualTo(before);
        WorldTestAccess.clearSwitch(f.player());
        assertThat(f.upgrade(1101, 1).getUpgradedCount()).isEqualTo(1);
    }

    @Test void 被动修习推属性面板然后回完整书() throws Exception {
        var f = new Fixture(funded(), 1);
        var result = f.upgrade(1106, 1);
        assertThat(result.getUpgradedCount()).isEqualTo(1);
        assertThat(f.sink.to(1, 11)).extracting(e -> e.getMessageId()).containsExactly(
                Contracts.REGISTRY.requireId("SceneAttributeClientPlayer", "NotifyAttributePanelChanged"), 317);
    }
}
