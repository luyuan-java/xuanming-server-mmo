package com.game.scene.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.api.proto.PlayerEnter;
import com.game.common.RunMode;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerSkillBookState;
import com.game.player.store.state.PlayerState;
import com.game.player.store.state.SkillLoadoutSchemeState;
import com.game.proto.BattleRouting;
import com.game.proto.SaveSkillLoadoutRequest;
import com.game.proto.SaveSkillLoadoutResponse;
import com.game.proto.SwitchSkillLoadoutRequest;
import com.game.proto.SwitchSkillLoadoutResponse;
import com.game.proto.UpgradeSkillRequest;
import com.game.scene.attribute.AttributeService;
import com.game.scene.attribute.AttributeTables;
import com.game.scene.battle.BattleSnapshots;
import com.game.scene.battle.SceneBattleTables;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.PlayerSkills;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.CurrencyAudit;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.ClientRequestHandler;
import com.game.scene.world.ConfigSceneTables;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerSnapshots;
import com.game.scene.world.Scene;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.table.CommonErrorTip;
import com.game.table.ConfigTables;
import com.game.table.SkillErrorTip;
import com.google.protobuf.UnknownFieldSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 人物战法构筑 3 套流派方案（502/503）、互斥组、自动入槽、属性/支援/战斗快照一致性与未知字段往返。 */
class SkillLoadoutTest {
    private static ConfigTables config;
    private static ConfigSceneTables sceneTables;
    private static SceneBattleTables battleTables;

    @BeforeAll
    static void load() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables") : Path.of("config-data/tables");
        config = ConfigTables.load(dir);
        sceneTables = ConfigSceneTables.from(config, dir.getParent().resolve("navmesh/current_maps"));
        battleTables = SceneBattleTables.from(config);
    }

    private static final class Fixture {
        final FakePlayerRepository repo = new FakePlayerRepository();
        final RecordingSink sink = new RecordingSink();
        final ManualClock clock = new ManualClock();
        final CurrencyService currency = new CurrencyService((CurrencyAudit) (p, t, d, b, a, r) -> { });
        final AttributeService attributes = new AttributeService(AttributeTables.from(config), clock, currency);
        final ClassSkillService service = new ClassSkillService(currency, attributes);
        final SceneWorld world = new SceneWorld(sceneTables, Contracts.IDS, sink, repo,
                new AtomicLong(5000)::incrementAndGet, clock, SceneMetrics.noop(),
                attributes::initializeOnLoad, PlayerSnapshots.NONE);
        final Scene scene = world.createScene(1);
        final ClientRequestHandler handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS,
                RunMode.DEV, List.of(new ClassSkillFeature(service, attributes, Contracts.REGISTRY)));

        Fixture() {
            PlayerState state = PlayerState.newBuilder()
                    .setCurrency(CurrencyState.newBuilder().addBalances(100_000L)).build();
            repo.put(new PlayerData(7, 1, 1, 1, "", 85, 1, sceneTables.spawnPoint(1), state));
            world.onPlayerEnter(1, PlayerEnter.newBuilder().setSessionId(11).setPlayerId(7)
                    .setOwnerEpoch(1).setSceneId(scene.sceneId()).build());
            repo.completeAll();
            sink.clear();
        }

        ScenePlayer player() { return world.playerById(7); }

        SaveSkillLoadoutResponse dispatchSave(SaveSkillLoadoutRequest req) throws Exception {
            int msgId = Contracts.REGISTRY.requireId("SceneSkillClientPlayer", "SaveSkillLoadout");
            handler.onClientForward(1, ClientForward.newBuilder().setSessionId(11).setPlayerId(7)
                    .setRequestId(101).setMessageId(msgId).setBody(req.toByteString()).build());
            var reply = sink.to(1, 11).stream().filter(m -> m.getMessageId() == msgId).reduce((a, b) -> b).orElseThrow();
            return SaveSkillLoadoutResponse.parseFrom(reply.getSerializedMessage());
        }

        SwitchSkillLoadoutResponse dispatchSwitch(SwitchSkillLoadoutRequest req) throws Exception {
            int msgId = Contracts.REGISTRY.requireId("SceneSkillClientPlayer", "SwitchSkillLoadout");
            handler.onClientForward(1, ClientForward.newBuilder().setSessionId(11).setPlayerId(7)
                    .setRequestId(102).setMessageId(msgId).setBody(req.toByteString()).build());
            var reply = sink.to(1, 11).stream().filter(m -> m.getMessageId() == msgId).reduce((a, b) -> b).orElseThrow();
            return SwitchSkillLoadoutResponse.parseFrom(reply.getSerializedMessage());
        }
    }

    @Test
    void 兼容态互斥去重_自定义保存校验槽位与互斥_切换实时影响属性支援与战斗快照() throws Exception {
        Fixture f = new Fixture();
        ScenePlayer player = f.player();

        // 12101 与 12102 同属 exclusive_group=1；12005 是治疗被动；13101 是追击；14001 是开场。
        player.learnedSkills().setRank(12005, 10);
        player.learnedSkills().setRank(12101, 1);
        player.learnedSkills().setRank(12102, 1);
        player.learnedSkills().setRank(13101, 1);
        player.learnedSkills().setRank(14001, 1);

        // 兼容态（未自定义保存）：12101 生效而同互斥组较高 ID 的 12102 不生效。
        var compatBook = ClassSkillService.book(player);
        assertThat(compatBook.getLoadout().getCustomEnabled()).isFalse();
        assertThat(compatBook.getLoadout().getSchemesList()).hasSize(3);
        assertThat(compatBook.getCatalogList()).filteredOn(c -> c.getSkillTableId() == 12101)
                .singleElement().satisfies(c -> {
                    assertThat(c.getExclusiveGroup()).isEqualTo(1);
                    assertThat(c.getEquipped()).isTrue();
                });
        assertThat(compatBook.getCatalogList()).filteredOn(c -> c.getSkillTableId() == 12102)
                .singleElement().satisfies(c -> assertThat(c.getEquipped()).isFalse());

        // 同互斥组同时装配拒绝（kSkillLoadoutConflict）。
        var conflict = f.service.saveLoadout(player, SaveSkillLoadoutRequest.newBuilder()
                .setSchemeId(1).setRoleTag(1).setStateVersion(1L)
                .addPassiveSkillIds(12101).addPassiveSkillIds(12102)
                .setRequestId("loadout-conflict-00001").build());
        assertThat(conflict.getErrorMessage().getId()).isEqualTo(SkillErrorTip.skill_error.kSkillLoadoutConflict_VALUE);

        // 未学技能拒绝（kSkillLoadoutNotLearned）。
        var notLearned = f.service.saveLoadout(player, SaveSkillLoadoutRequest.newBuilder()
                .setSchemeId(1).setRoleTag(1).setStateVersion(1L)
                .addPassiveSkillIds(12006)
                .setRequestId("loadout-notlearn-00001").build());
        assertThat(notLearned.getErrorMessage().getId()).isEqualTo(SkillErrorTip.skill_error.kSkillLoadoutNotLearned_VALUE);

        // 超过被动 3 槽上限拒绝（kSkillLoadoutSlotLimitReached）。
        var overCap = f.service.saveLoadout(player, SaveSkillLoadoutRequest.newBuilder()
                .setSchemeId(1).setRoleTag(1).setStateVersion(1L)
                .addAllPassiveSkillIds(List.of(12005, 12101, 12006, 12007))
                .setRequestId("loadout-overcap-000001").build());
        assertThat(overCap.getErrorMessage().getId()).isEqualTo(SkillErrorTip.skill_error.kSkillLoadoutSlotLimitReached_VALUE);

        // 走真实 502 分发保存方案 1，推属性面板并回完整技能书。
        f.sink.clear();
        var saveReq = SaveSkillLoadoutRequest.newBuilder()
                .setSchemeId(1).setRoleTag(3).setStateVersion(1L)
                .addPassiveSkillIds(12101).addPassiveSkillIds(12005)
                .addPursuitSkillIds(13101)
                .addOpeningSkillIds(14001)
                .setRequestId("loadout-save-s1-000001").build();
        var saved = f.dispatchSave(saveReq);
        assertThat(saved.getErrorMessage().getId()).isZero();
        assertThat(saved.getReplayed()).isFalse();
        assertThat(saved.getState().getLoadout().getCustomEnabled()).isTrue();
        assertThat(saved.getState().getLoadout().getStateVersion()).isEqualTo(2L);
        assertThat(saved.getState().getLoadout().getSchemes(0).getPassiveSkillIdsList())
                .containsExactly(12005, 12101);
        assertThat(f.sink.to(1, 11)).extracting(m -> m.getMessageId()).containsExactly(
                Contracts.REGISTRY.requireId("SceneAttributeClientPlayer", "NotifyAttributePanelChanged"),
                Contracts.REGISTRY.requireId("SceneSkillClientPlayer", "SaveSkillLoadout"));
        assertThat(CharacterSupport.build(player).getHealing().getTotalBonusBps()).isEqualTo(1500);
        var snap1 = BattleSnapshots.build(player, battleTables, List.of(), BattleRouting.getDefaultInstance());
        assertThat(snap1.getSkillRanksMap()).containsKeys(12005, 12101, 13101, 14001).doesNotContainKey(12102);

        // 同 request_id 幂等重放（不推面板、不升版本）。
        f.sink.clear();
        var replay = f.dispatchSave(saveReq);
        assertThat(replay.getErrorMessage().getId()).isZero();
        assertThat(replay.getReplayed()).isTrue();
        assertThat(replay.getState().getLoadout().getStateVersion()).isEqualTo(2L);
        assertThat(f.sink.to(1, 11)).hasSize(1);

        // 过期版本拒绝（kSkillLoadoutVersionChanged）。
        var stale = f.service.switchLoadout(player, SwitchSkillLoadoutRequest.newBuilder()
                .setSchemeId(2).setStateVersion(1L).setRequestId("loadout-switch-stale-1").build());
        assertThat(stale.getErrorMessage().getId()).isEqualTo(SkillErrorTip.skill_error.kSkillLoadoutVersionChanged_VALUE);

        // 走真实 503 分发切换到空方案 2：治疗加成归 0，战斗快照剔除未装配非主动技。
        f.sink.clear();
        var sw2 = f.dispatchSwitch(SwitchSkillLoadoutRequest.newBuilder()
                .setSchemeId(2).setStateVersion(2L).setRequestId("loadout-switch-s2-0001").build());
        assertThat(sw2.getErrorMessage().getId()).isZero();
        assertThat(sw2.getState().getLoadout().getActiveSchemeId()).isEqualTo(2);
        assertThat(f.sink.to(1, 11)).extracting(m -> m.getMessageId()).containsExactly(
                Contracts.REGISTRY.requireId("SceneAttributeClientPlayer", "NotifyAttributePanelChanged"),
                Contracts.REGISTRY.requireId("SceneSkillClientPlayer", "SwitchSkillLoadout"));
        assertThat(CharacterSupport.build(player).getHealing().getTotalBonusBps()).isZero();
        var snap2 = BattleSnapshots.build(player, battleTables, List.of(), BattleRouting.getDefaultInstance());
        assertThat(snap2.getSkillRanksMap()).doesNotContainKeys(12005, 12101, 12102, 13101, 14001);

        // 方案 2 启用时新学被动 12006 自动入槽（空槽 + 无互斥）。
        var upgraded = f.service.upgrade(player, UpgradeSkillRequest.newBuilder()
                .setSkillTableId(12006).setExpectedRank(0).setCount(1).build());
        assertThat(upgraded.getErrorMessage().getId()).isZero();
        assertThat(upgraded.getState().getLoadout().getSchemes(1).getPassiveSkillIdsList()).containsExactly(12006);

        // 非法方案号在重放前拦截；短 request_id 拒绝。
        var badScheme = f.service.switchLoadout(player, SwitchSkillLoadoutRequest.newBuilder()
                .setSchemeId(9).setStateVersion(3L).setRequestId("loadout-switch-bad-001").build());
        assertThat(badScheme.getErrorMessage().getId()).isEqualTo(SkillErrorTip.skill_error.kSkillLoadoutSchemeInvalid_VALUE);
        var badReqId = f.service.switchLoadout(player, SwitchSkillLoadoutRequest.newBuilder()
                .setSchemeId(1).setStateVersion(3L).setRequestId("short").build());
        assertThat(badReqId.getErrorMessage().getId()).isEqualTo(CommonErrorTip.common_error.kInvalidParameter_VALUE);
    }

    @Test
    void 技能书与构筑方案未知字段存档往返原样保留() {
        UnknownFieldSet bookExtra = UnknownFieldSet.newBuilder()
                .addField(88, UnknownFieldSet.Field.newBuilder().addVarint(12345L).build()).build();
        UnknownFieldSet schemeExtra = UnknownFieldSet.newBuilder()
                .addField(77, UnknownFieldSet.Field.newBuilder().addVarint(54321L).build()).build();
        PlayerSkillBookState original = PlayerSkillBookState.newBuilder()
                .setActiveLoadoutSchemeId(2)
                .setLoadoutStateVersion(5L)
                .setLoadoutLastRequestId("loadout-roundtrip-001")
                .setLoadoutLastBaseVersion(4L)
                .setLoadoutLastSchemeId(2)
                .addLoadoutSchemes(SkillLoadoutSchemeState.newBuilder()
                        .setSchemeId(2).setRoleTag(1).addPassiveSkillIds(12005).setUnknownFields(schemeExtra))
                .setUnknownFields(bookExtra)
                .build();
        PlayerSkillBookState restored = PlayerSkills.restore(original).toState();
        assertThat(restored).isEqualTo(original);
        assertThat(restored.getUnknownFields()).isEqualTo(bookExtra);
        assertThat(restored.getLoadoutSchemes(0).getUnknownFields()).isEqualTo(schemeExtra);
    }

    @Test
    void 仙途轻享卡扩展战法与属性方案3到6_到期保留已存方案不丢失() {
        var f = new Fixture();
        f.service.withClock(f.clock);
        ScenePlayer player = f.player();
        player.learnedSkills().setRank(12005, 1);
        long nowSec = Math.floorDiv(f.clock.epochMillis(), 1000L);
        player.setAfkPassExpiresAt(0L);

        // 1. 未开通轻享卡：战法与属性上限均为 3；写方案 4 拒绝。
        var bookFree = ClassSkillService.book(player, 0, false, nowSec);
        assertThat(bookFree.getLoadout().getUnlockedSchemes()).isEqualTo(3);
        assertThat(bookFree.getLoadout().getMaxSchemes()).isEqualTo(6);
        assertThat(bookFree.getLoadout().getSchemesList()).hasSize(3);
        assertThat(f.attributes.buildPanel(player).getMaxSchemes()).isEqualTo(3);

        var denySave4 = f.service.saveLoadout(player, SaveSkillLoadoutRequest.newBuilder()
                .setSchemeId(4).setRoleTag(1).addPassiveSkillIds(12005)
                .setStateVersion(1L).setRequestId("loadout-lc-deny-0001").build());
        assertThat(denySave4.getErrorMessage().getId()).isEqualTo(SkillErrorTip.skill_error.kSkillLoadoutSchemeInvalid_VALUE);

        // 2. 开通轻享卡：战法与属性上限升至 6；可保存并激活方案 4，属性可开第 4 套方案。
        player.setAfkPassExpiresAt(nowSec + 3600L);
        var bookActive = ClassSkillService.book(player, 0, false, nowSec);
        assertThat(bookActive.getLoadout().getUnlockedSchemes()).isEqualTo(6);
        assertThat(bookActive.getLoadout().getSchemesList()).hasSize(6);
        assertThat(f.attributes.buildPanel(player).getMaxSchemes()).isEqualTo(6);

        var save4 = f.service.saveLoadout(player, SaveSkillLoadoutRequest.newBuilder()
                .setSchemeId(4).setRoleTag(1).addPassiveSkillIds(12005)
                .setStateVersion(1L).setRequestId("loadout-lc-save4-001").build());
        assertThat(save4.getErrorMessage().getId()).isZero();
        assertThat(save4.getState().getLoadout().getActiveSchemeId()).isEqualTo(4);
        assertThat(save4.getState().getLoadout().getUnlockedSchemes()).isEqualTo(6);

        // 3. 轻享卡到期：解锁数回落 3，但已存方案 4 完整保留在快照中；切回方案 1 成功，再切回方案 4 被拒。
        player.setAfkPassExpiresAt(nowSec - 1L);
        var bookExpired = ClassSkillService.book(player, 0, false, nowSec);
        assertThat(bookExpired.getLoadout().getUnlockedSchemes()).isEqualTo(3);
        assertThat(bookExpired.getLoadout().getSchemesList()).hasSize(4); // 1,2,3 + 保留的 4
        assertThat(f.attributes.buildPanel(player).getMaxSchemes()).isEqualTo(3);

        var sw1 = f.service.switchLoadout(player, SwitchSkillLoadoutRequest.newBuilder()
                .setSchemeId(1).setStateVersion(2L).setRequestId("loadout-lc-sw1-00001").build());
        assertThat(sw1.getErrorMessage().getId()).isZero();
        var sw4Denied = f.service.switchLoadout(player, SwitchSkillLoadoutRequest.newBuilder()
                .setSchemeId(4).setStateVersion(3L).setRequestId("loadout-lc-sw4-00001").build());
        assertThat(sw4Denied.getErrorMessage().getId()).isEqualTo(SkillErrorTip.skill_error.kSkillLoadoutSchemeInvalid_VALUE);
    }
}
