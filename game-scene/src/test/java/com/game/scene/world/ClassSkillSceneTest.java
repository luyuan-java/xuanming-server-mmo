package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static com.game.table.SkillErrorTip.skill_error.*;

import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerSkillBookState;
import com.game.player.store.state.PlayerSkillEntry;
import com.game.player.store.state.PlayerState;
import com.game.proto.BattleRouting;
import com.game.proto.UpgradeSkillRequest;
import com.game.scene.attribute.AttributeService;
import com.game.scene.attribute.AttributeTables;
import com.game.scene.battle.BattleFreezeTestAccess;
import com.game.scene.battle.BattleSnapshots;
import com.game.scene.battle.SceneBattleTables;
import com.game.scene.currency.CurrencyService;
import com.game.scene.skill.ClassSkillCatalog;
import com.game.scene.skill.ClassSkillService;
import com.game.scene.testing.CurrencyAudit;
import com.game.scene.testing.ManualClock;
import com.game.table.ConfigTables;
import com.game.table.SkillTable;
import com.google.protobuf.UnknownFieldSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 真实领域加载→修习→整份存档→重建，以及表投影、快照和状态拒绝。 */
class ClassSkillSceneTest {
    private static ConfigTables config;
    private static ClassSkillCatalog catalog;
    private final CurrencyService currency = new CurrencyService((CurrencyAudit) (p, t, d, b, a, r) -> { });
    private final AttributeService attributes = new AttributeService(AttributeTables.from(config), new ManualClock(), currency);
    private final ClassSkillService service = new ClassSkillService(currency, attributes);

    @BeforeAll static void tables() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables")) ? Path.of("../config-data/tables") : Path.of("config-data/tables");
        config = ConfigTables.load(dir);
        catalog = ClassSkillCatalog.from(config);
    }

    private ScenePlayer player(int classId, int level, PlayerState state) {
        return player(classId, level, state, catalog, List.of());
    }

    private ScenePlayer player(int classId, int level, PlayerState state, ClassSkillCatalog rows, List<Integer> seeds) {
        return new ScenePlayer(7, 8, new SessionKey(1, 2), 3, classId, 1, "", level, seeds,
                Vec3.ORIGIN, state, 0, rows);
    }

    private static PlayerState funded(long balance) {
        return PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(balance)).build();
    }

    private static UpgradeSkillRequest upgrade(int id, int rank, int count) {
        return UpgradeSkillRequest.newBuilder().setSkillTableId(id).setExpectedRank(rank).setCount(count).build();
    }

    private static UnknownFieldSet unknown(int tag) {
        return UnknownFieldSet.newBuilder().addField(tag, UnknownFieldSet.Field.newBuilder().addVarint(123).build()).build();
    }

    @Test void 按职业补种子并保持已学重数_错误职业和未知种子不授予() {
        var p = player(1, 85, PlayerState.getDefaultInstance(), catalog, List.of(1101, 1106, 1201, 99999, 0));
        assertThat(p.skills()).containsExactly(1101, 1106);
        assertThat(p.learnedSkills().rank(1101)).isEqualTo(1);
        p.learnedSkills().setRank(1101, 7);
        var restored = player(1, 85, p.persistentState(), catalog, List.of(1101, 1106));
        assertThat(restored.learnedSkills().rank(1101)).isEqualTo(7);
        assertThat(restored.learnedSkills().entries()).hasSize(2);
    }

    @Test void 未达解锁等级不发种子_零重原条目达标后保留未知字段补一重() {
        var row = SkillTable.newBuilder().setId(9).setClassId(1).setUnlockLevel(10).setMaxRank(10).build();
        var rows = new ClassSkillCatalog(List.of(row), List.of());
        var entry = PlayerSkillEntry.newBuilder().setSkillId(9).setUnknownFields(unknown(90)).build();
        var state = PlayerState.newBuilder().setSkills(PlayerSkillBookState.newBuilder().addEntries(entry)).build();
        var low = player(1, 9, state, rows, List.of(9));
        assertThat(low.skills()).isEmpty();
        assertThat(low.learnedSkills().toState().getEntries(0)).isEqualTo(entry);
        var ready = player(1, 10, low.persistentState(), rows, List.of(9));
        assertThat(ready.learnedSkills().rank(9)).isEqualTo(1);
        assertThat(ready.learnedSkills().toState().getEntries(0).getUnknownFields()).isEqualTo(unknown(90));
    }

    @Test void 正式修习同时保存扣款和重数_旧请求重放不再扣款() {
        var p = player(1, 85, funded(100_000));
        var response = service.upgrade(p, upgrade(1101, 0, 1));
        assertThat(response.getErrorMessage().getId()).isZero();
        assertThat(response.getUpgradedCount()).isEqualTo(1);
        assertThat(p.wallet().balance(0)).isEqualTo(99_890);
        var persisted = p.persistentState();
        var restored = player(1, 85, persisted);
        assertThat(restored.learnedSkills().rank(1101)).isEqualTo(1);
        assertThat(service.upgrade(restored, upgrade(1101, 0, 1)).getErrorMessage().getId()).isEqualTo(kSkillRankChanged_VALUE);
        assertThat(restored.persistentState()).isEqualTo(persisted);
    }

    @Test void 根技能书和条目未知字段经过真实升级与保存全部保留且不下发() {
        var entry = PlayerSkillEntry.newBuilder().setSkillId(1101).setRank(1).setUnknownFields(unknown(93)).build();
        var state = funded(10_000).toBuilder().setUnknownFields(unknown(91)).setSkills(
                PlayerSkillBookState.newBuilder().setUnknownFields(unknown(92)).addEntries(entry)).build();
        var p = player(1, 85, state);
        assertThat(service.upgrade(p, upgrade(1101, 1, 1)).getUpgradedCount()).isEqualTo(1);
        var written = p.persistentState();
        assertThat(written.getUnknownFields()).isEqualTo(unknown(91));
        assertThat(written.getSkills().getUnknownFields()).isEqualTo(unknown(92));
        assertThat(written.getSkills().getEntries(0).getUnknownFields()).isEqualTo(unknown(93));
        var restored = player(1, 85, written);
        assertThat(restored.persistentState()).isEqualTo(written);
        assertThat(ClassSkillService.book(restored).getSkillList().getSkillList(0).getUnknownFields().asMap()).isEmpty();
        assertThat(restored.learnedSkills().rank(1101)).isEqualTo(2);
    }

    @Test void 十次修习余额不足部分成功_只扣实际费用() {
        var p = player(1, 85, funded(520));
        var result = service.upgrade(p, upgrade(1101, 0, 10));
        assertThat(result.getErrorMessage().getId()).isZero();
        assertThat(result.getUpgradedCount()).isEqualTo(2);
        assertThat(result.getStopTipId()).isEqualTo(kSkillGoldNotEnough_VALUE);
        assertThat(result.getState().getCurrencyBalance()).isZero();
        assertThat(p.learnedSkills().rank(1101)).isEqualTo(2);
    }

    @Test void 超版本重数和未知技能原样保存但只按本版生效() {
        var p = player(1, 85, funded(50_000));
        p.learnedSkills().setRank(1101, -1);
        p.learnedSkills().setRank(999999, 42);
        p.learnedSkills().setRank(1201, 4);
        p.learnedSkills().setRank(1, 0);
        var before = p.persistentState();
        assertThat(p.skills()).containsExactly(1101);
        assertThat(service.upgrade(p, upgrade(1101, -1, 1)).getErrorMessage().getId()).isEqualTo(kSkillMaxRankReached_VALUE);
        assertThat(p.persistentState()).isEqualTo(before);
        var book = ClassSkillService.book(p);
        assertThat(book.getSkillList().getSkillList(0).getRank()).isEqualTo(-1);
        assertThat(book.getCatalogList().stream().filter(e -> e.getSkillTableId() == 1101).findFirst().orElseThrow().getNextRankCost()).isZero();
        var snapshot = BattleSnapshots.build(p, SceneBattleTables.from(config), List.of(), BattleRouting.getDefaultInstance());
        assertThat(snapshot.getSkillRanksOrThrow(1101)).isEqualTo(config.skill().find(1101).orElseThrow().getMaxRank());
        assertThat(snapshot.getSkillTableIdsList()).containsExactly(1101);
    }

    @Test void 被动进入重数快照但不进主动列表_降级只降低生效重数() {
        var p = player(1, 1, funded(0));
        p.learnedSkills().setRank(1101, 8);
        p.learnedSkills().setRank(1106, 8);
        var snapshot = BattleSnapshots.build(p, SceneBattleTables.from(config), List.of(), BattleRouting.getDefaultInstance());
        assertThat(snapshot.getSkillRanksOrThrow(1101)).isEqualTo(1);
        assertThat(snapshot.getSkillRanksOrThrow(1106)).isEqualTo(1);
        assertThat(snapshot.getSkillTableIdsList()).containsExactly(1101);
        assertThat(p.learnedSkills().rank(1106)).isEqualTo(8);
    }

    @Test void 参数职业忙碌预期重数按顺序拒绝_始终回完整状态() {
        var p = player(1, 85, funded(50_000));
        BattleFreezeTestAccess.freeze(p);
        assertThat(service.upgrade(p, upgrade(1101, 0, 2)).getErrorMessage().getId()).isEqualTo(kSkillUpgradeCountInvalid_VALUE);
        assertThat(service.upgrade(p, upgrade(1201, 0, 1)).getErrorMessage().getId()).isEqualTo(kSkillNotInClass_VALUE);
        var result = service.upgrade(p, upgrade(1101, 99, 1));
        assertThat(result.getErrorMessage().getId()).isEqualTo(kSkillUpgradeBusy_VALUE);
        assertThat(result.hasState()).isTrue();
        assertThat(result.getState().getCatalogList()).allSatisfy(e -> assertThat(e.getUpgradeBlockTipId()).isEqualTo(kSkillUpgradeBusy_VALUE));
        assertThat(p.wallet().balance(0)).isEqualTo(50_000);
        assertThat(p.learnedSkills().entries()).isEmpty();
        BattleFreezeTestAccess.clear(p);
        assertThat(service.upgrade(p, upgrade(1101, 99, 1)).getErrorMessage().getId()).isEqualTo(kSkillRankChanged_VALUE);
        assertThat(service.upgrade(p, upgrade(1101, 0, 1)).getUpgradedCount()).isEqualTo(1);
    }

    @Test void 交接冻结和选目标期间不允许修习_解冻可重试() {
        var p = player(1, 85, funded(50_000));
        var before = p.persistentState();
        WorldTestAccess.startResolving(p);
        assertThat(service.upgrade(p, upgrade(1101, 0, 1)).getErrorMessage().getId()).isEqualTo(kSkillUpgradeBusy_VALUE);
        WorldTestAccess.startFreezing(p);
        assertThat(service.upgrade(p, upgrade(1101, 0, 1)).getErrorMessage().getId()).isEqualTo(kSkillUpgradeBusy_VALUE);
        assertThat(p.persistentState()).isEqualTo(before);
        WorldTestAccess.clearSwitch(p);
        assertThat(service.upgrade(p, upgrade(1101, 0, 1)).getUpgradedCount()).isEqualTo(1);
    }

    @Test void 审计抛异常不拆开扣款与修习() {
        var failing = new CurrencyService((CurrencyAudit) (p, t, d, b, a, r) -> { throw new IllegalStateException("audit failed"); });
        var p = player(1, 85, funded(1000));
        assertThat(new ClassSkillService(failing, attributes).upgrade(p, upgrade(1101, 0, 1)).getUpgradedCount()).isEqualTo(1);
        assertThat(p.wallet().balance(0)).isEqualTo(890);
        assertThat(p.learnedSkills().rank(1101)).isEqualTo(1);
    }

    @Test void 被动从原始属性重算不叠加_升级保持血蓝比例() {
        var health = SkillTable.newBuilder().setId(91).setClassId(1).setUnlockLevel(1).setMaxRank(10)
                .setPassiveStat("maxHp").setPassivePercent(0.20).build();
        var crit = health.toBuilder().setId(92).setPassiveStat("crit").setPassivePercent(0.05).build();
        var rows = new ClassSkillCatalog(List.of(health, crit), List.of());
        var p = player(1, 85, funded(10_000), rows, List.of(91, 92));
        attributes.initializeOnLoad(p);
        var first = p.attributes().derived();
        p.attributes().setHealth(first.maxHealth() / 2);
        attributes.skillsChanged(p);
        assertThat(p.attributes().derived()).isEqualTo(first);
        assertThat(p.attributes().health()).isEqualTo(first.maxHealth() / 2);
        assertThat(p.attributes().combat().getPhysicalCritRate()).isEqualTo(5);
        assertThat(p.attributes().combat().getMagicCritRate()).isEqualTo(5);
        assertThat(service.upgrade(p, upgrade(91, 1, 1)).getUpgradedCount()).isEqualTo(1);
        assertThat(p.attributes().derived().maxHealth()).isGreaterThan(first.maxHealth());
        assertThat((double) p.attributes().health() / p.attributes().derived().maxHealth()).isBetween(0.49, 0.51);
        var second = p.attributes().derived();
        attributes.skillsChanged(p);
        assertThat(p.attributes().derived()).isEqualTo(second);
    }
}
