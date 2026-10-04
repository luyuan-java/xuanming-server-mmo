package com.game.scene.attribute;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.common.RunMode;
import com.game.player.store.state.AttributeScheme;
import com.game.player.store.state.AttributeState;
import com.game.player.store.state.PlayerState;
import com.game.player.store.state.Vitals;
import com.game.proto.AllocateAttributePointsRequest;
import com.game.proto.AllocateAttributePointsResponse;
import com.game.proto.AttributeDimensionInfo;
import com.game.proto.AttributePanelChangedS2C;
import com.game.proto.AttributePanelInfo;
import com.game.proto.AttributePoolInfo;
import com.game.proto.AutoAllocateAttributePointsRequest;
import com.game.proto.AutoAllocateAttributePointsResponse;
import com.game.proto.CreateAttributeSchemeRequest;
import com.game.proto.CreateAttributeSchemeResponse;
import com.game.proto.DerivedAttributeInfo;
import com.game.proto.GetAttributePanelRequest;
import com.game.proto.GetAttributePanelResponse;
import com.game.proto.GmSetPlayerLevelRequest;
import com.game.proto.GmSetPlayerLevelResponse;
import com.game.proto.MessageContent;
import com.game.proto.RenameAttributeSchemeRequest;
import com.game.proto.RenameAttributeSchemeResponse;
import com.game.proto.ResetAttributePointsRequest;
import com.game.proto.ResetAttributePointsResponse;
import com.game.proto.SwitchAttributeSchemeRequest;
import com.game.proto.SwitchAttributeSchemeResponse;
import com.game.scene.audit.AssetAudit;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.PlayerAttributes;
import com.game.scene.player.Wallet;
import com.game.scene.testing.CurrencyAudit;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.ClientRequestHandler;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerSave;
import com.game.scene.world.Scene;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.Vec3;
import com.game.scene.world.WorldTestAccess;
import com.game.table.ConfigTables;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.UnknownFieldSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 属性加点端到端（分发入口 → 处理器 → 面板），用仓库里同步来的真实配表，数值逐项对照基线公式与 robot attribute_smoke 的断言。
 * 新号默认职业 1（破军：力量收益比例 +30%，其余维度走 class_id=0 兜底行）。
 */
class AttributeFeatureTest {

    private static final long LINK = 1;
    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final String SERVICE = "SceneAttributeClientPlayer";
    private static final int NOTIFY_PANEL = Contracts.REGISTRY.requireId(SERVICE, "NotifyAttributePanelChanged");

    private static AttributeTables tables;

    private record Audited(long playerId, int type, long delta, long before, long after, Reason reason) {
    }

    private final RecordingSink sink = new RecordingSink();
    private final FakePlayerRepository repo = new FakePlayerRepository();
    private final ManualClock clock = new ManualClock();
    private final List<Audited> audits = new ArrayList<>();
    /** 等级连带（任务等级事实）被调到时：已发出的消息数 + 等级。 */
    private final List<String> levelEvents = new ArrayList<>();
    private final AssetAudit audit = (CurrencyAudit) (playerId, type, delta, before, after, reason) ->
            audits.add(new Audited(playerId, type, delta, before, after, reason));
    private SceneWorld world;
    private ClientRequestHandler handler;
    private long requestIds;

    @BeforeAll
    static void loadTables() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
        tables = AttributeTables.from(ConfigTables.load(dir));
    }

    private void start(RunMode mode, int classId, int level, PlayerState state) {
        AttributeService service = new AttributeService(tables, clock, new CurrencyService(audit));
        AtomicLong ids = new AtomicLong(5000);
        FakeSceneTables sceneTables = new FakeSceneTables();
        world = new SceneWorld(sceneTables, Contracts.IDS, sink, repo, ids::incrementAndGet, clock, SceneMetrics.noop(),
                service::initializeOnLoad, com.game.scene.world.PlayerSnapshots.NONE);
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS, mode,
                List.of(new AttributeFeature(service, Contracts.REGISTRY,
                        player -> levelEvents.add(sink.to(LINK, SESSION).size() + ":" + player.level()))));
        Scene scene = world.createScene(1);
        repo.put(new PlayerData(PLAYER, 1, classId, 1, "", level, 0, Vec3.ORIGIN, state));
        world.onPlayerEnter(LINK, PlayerEnter.newBuilder()
                .setSessionId(SESSION).setPlayerId(PLAYER).setSceneId(scene.sceneId()).setOwnerEpoch(1).build());
        repo.completeAll();
        sink.clear();
    }

    private void start() {
        start(RunMode.DEV, 1, 1, null);
    }

    // ------------------------------------------------------------------ 面板

    @Test
    void 新号面板_只列角色池与角色维度_默认方案_二级属性按职业初值加1级自然成长_当前气血法力满() throws Exception {
        start();

        GetAttributePanelResponse response = GetAttributePanelResponse.parseFrom(
                call("GetAttributePanel", GetAttributePanelRequest.getDefaultInstance()).getSerializedMessage());

        assertThat(response.hasErrorMessage()).isTrue();
        assertThat(response.getErrorMessage().getId()).isZero();
        AttributePanelInfo panel = response.getPanel();
        assertThat(panel.getLevel()).isEqualTo(1);
        assertThat(panel.getPoolsList()).extracting(AttributePoolInfo::getPoolId).as("宝宝池 4 不进角色面板").containsExactly(1);
        AttributePoolInfo pool = panel.getPools(0);
        assertThat(pool.getName()).isEqualTo("属性点");
        assertThat(pool.getTotal()).isEqualTo(5);
        assertThat(pool.getRemaining()).isEqualTo(5);
        assertThat(pool.getUnlocked()).isTrue();
        assertThat(pool.getUnlockLevel()).isEqualTo(1);
        assertThat(pool.getResetCostGold()).as("30 级以下洗点免费").isZero();
        assertThat(panel.getDimensionsList()).extracting(AttributeDimensionInfo::getDimensionId)
                .containsExactly(101, 102, 103, 104);
        AttributeDimensionInfo strength = panel.getDimensions(2);
        assertThat(strength.getName()).isEqualTo("力量");
        assertThat(strength.getDesc()).isEqualTo("力量:提高物伤。");
        assertThat(strength.getPoolId()).isEqualTo(1);
        assertThat(strength.getValue()).as("面板值是点数：每级自然成长 1 点").isEqualTo(1);
        assertThat(strength.getAllocated()).isZero();
        assertThat(strength.getSort()).isEqualTo(3);
        assertThat(panel.getSchemesList()).hasSize(1);
        assertThat(panel.getSchemes(0).getSchemeId()).isEqualTo(1);
        assertThat(panel.getSchemes(0).getName()).isEqualTo("方案一");
        assertThat(panel.getActiveSchemeId()).isEqualTo(1);
        assertThat(panel.getMaxSchemes()).isEqualTo(3);
        assertThat(panel.getCreateSchemeCostGold()).isEqualTo(1000);
        assertThat(panel.getSwitchCooldownUntil()).as("从没切换过：0 + 60").isEqualTo(60);
        assertDerived(panel, 550, 840, 50, 40, 276, 60);
        assertThat(panel.getDerived().getHealth()).isEqualTo(550);
        assertThat(panel.getDerived().getMana()).isEqualTo(840);
    }

    // ------------------------------------------------------------------ GM 设等级

    @Test
    void GM设等级_先推170再回应答_面板按新等级_点数加145_满血按增量补满() throws Exception {
        start();

        List<MessageContent> sent = callAll("GmSetPlayerLevel", GmSetPlayerLevelRequest.newBuilder().setLevel(30).build(), 2);

        MessageContent push = sent.get(0);
        assertThat(push.getMessageId()).isEqualTo(NOTIFY_PANEL);
        assertThat(push.getId()).as("推送信封 id 为 0，客户端不会当成 175 的应答").isZero();
        AttributePanelInfo pushed = AttributePanelChangedS2C.parseFrom(push.getSerializedMessage()).getPanel();
        GmSetPlayerLevelResponse response = GmSetPlayerLevelResponse.parseFrom(sent.get(1).getSerializedMessage());
        assertThat(response.getErrorMessage().getId()).isZero();
        assertThat(response.getPanel()).isEqualTo(pushed);
        assertThat(pushed.getLevel()).isEqualTo(30);
        assertThat(pushed.getPools(0).getTotal() - 5).isEqualTo(145);
        assertThat(pushed.getPools(0).getResetCostGold()).isEqualTo(500);
        assertDerived(pushed, 2000, 2000, 1500, 1200, 1320, 1800);
        assertThat(levelEvents).as("等级连带在推 170 之后、回应答之前").containsExactly("1:30");
        assertThat(pushed.getDerived().getHealth()).isEqualTo(2000);
        assertThat(pushed.getDerived().getMana()).isEqualTo(2000);
        assertThat(player().level()).isEqualTo(30);
    }

    @Test
    void GM设同一等级照常成功并推170_越界回1005不推() throws Exception {
        start();

        assertThat(callAll("GmSetPlayerLevel", GmSetPlayerLevelRequest.newBuilder().setLevel(1).build(), 2).get(0)
                .getMessageId()).isEqualTo(NOTIFY_PANEL);
        for (int bad : new int[] {0, 86, -1}) {
            GmSetPlayerLevelResponse response = GmSetPlayerLevelResponse.parseFrom(
                    call("GmSetPlayerLevel", GmSetPlayerLevelRequest.newBuilder().setLevel(bad).build()).getSerializedMessage());
            assertThat(response.getErrorMessage().getId()).as("level=%d", bad).isEqualTo(1005);
            assertThat(response.hasPanel()).isFalse();
        }
        assertThat(levelEvents).as("同一等级也发，越界不发").containsExactly("1:1");
        assertThat(player().level()).isEqualTo(1);
    }

    @Test
    void 生产模式_GM设等级在分发入口回1006_等级不变不推170() throws Exception {
        start(RunMode.PROD, 1, 1, null);

        GmSetPlayerLevelResponse response = GmSetPlayerLevelResponse.parseFrom(
                call("GmSetPlayerLevel", GmSetPlayerLevelRequest.newBuilder().setLevel(30).build()).getSerializedMessage());

        assertThat(response.getErrorMessage().getId()).isEqualTo(1006);
        assertThat(player().level()).isEqualTo(1);
    }

    @Test
    void 降级_超量分配整池清零返还_所有方案都收敛_气血只夹不补() throws Exception {
        start();
        setLevel(30);
        allocate(Map.of(103, 150));
        player().wallet().add(Wallet.GOLD, 1000);
        create("");
        switchTo(2);
        allocate(Map.of(101, 40));

        AttributePanelInfo panel = setLevel(10);

        assertThat(panel.getPools(0).getTotal()).isEqualTo(50);
        assertThat(panel.getPools(0).getRemaining()).as("方案 2 的 40 点没超 50，保留").isEqualTo(10);
        assertThat(player().attributes().scheme(1).allocated(103)).as("方案 1 的 150 点超了，整池清零").isZero();
        assertThat(panel.getDerived().getHealth()).isEqualTo(panel.getDerived().getMaxHealth());
        assertThat(panel.getDerived().getMaxHealth()).isLessThan(2000);
    }

    // ------------------------------------------------------------------ 自动加点 / 加点

    @Test
    void 自动加点只算不落_破军全投力量_回显池号() throws Exception {
        start();
        setLevel(30);

        AutoAllocateAttributePointsResponse auto = AutoAllocateAttributePointsResponse.parseFrom(call(
                "AutoAllocateAttributePoints", AutoAllocateAttributePointsRequest.newBuilder().setPoolId(1).build())
                .getSerializedMessage());

        assertThat(auto.getErrorMessage().getId()).isZero();
        assertThat(auto.getPoolId()).isEqualTo(1);
        assertThat(auto.getSuggestedMap()).containsExactlyInAnyOrderEntriesOf(Map.of(101, 0, 102, 0, 103, 150, 104, 0));
        assertThat(panel().getPools(0).getRemaining()).isEqualTo(150);
    }

    @Test
    void 自动加点拒绝码_宝宝池25000_没有剩余点25014_拒绝时不回显池号() throws Exception {
        start();
        AutoAllocateAttributePointsResponse pet = AutoAllocateAttributePointsResponse.parseFrom(call(
                "AutoAllocateAttributePoints", AutoAllocateAttributePointsRequest.newBuilder().setPoolId(4).build())
                .getSerializedMessage());
        allocate(Map.of(103, 5));
        AutoAllocateAttributePointsResponse none = AutoAllocateAttributePointsResponse.parseFrom(call(
                "AutoAllocateAttributePoints", AutoAllocateAttributePointsRequest.newBuilder().setPoolId(1).build())
                .getSerializedMessage());

        assertThat(pet.getErrorMessage().getId()).isEqualTo(25000);
        assertThat(pet.getPoolId()).isZero();
        assertThat(none.getErrorMessage().getId()).isEqualTo(25014);
        assertThat(none.getSuggestedMap()).isEmpty();
    }

    @Test
    void 加点_按建议全投_剩余归零_物伤按破军比例公式_幂等重发回25014_减点回25004() throws Exception {
        start();
        setLevel(30);

        AttributePanelInfo panel = allocate(Map.of(103, 150));

        assertThat(panel.getPools(0).getRemaining()).isZero();
        assertDerived(panel, 2000, 2000, 1901, 1200, 1320, 1800);
        assertThat(panel.getDimensions(2).getValue()).as("30 点自然成长 + 150 点分配").isEqualTo(180);
        assertThat(allocateTip(1, Map.of(103, 150))).isEqualTo(25014);
        assertThat(allocateTip(1, Map.of(103, 149))).isEqualTo(25004);
    }

    @Test
    void 加点拒绝码() throws Exception {
        start();
        setLevel(30);

        assertThat(allocateTip(0, Map.of(103, 1))).as("池号 0").isEqualTo(1005);
        assertThat(allocateTip(1, Map.of())).as("空目标").isEqualTo(1005);
        assertThat(allocateTip(4, Map.of(401, 1))).as("宝宝池").isEqualTo(25000);
        assertThat(allocateTip(9, Map.of(103, 1))).as("表里没有的池").isEqualTo(25000);
        assertThat(allocateTip(1, Map.of(401, 1))).as("不属本池的维度").isEqualTo(25002);
        assertThat(allocateTip(1, Map.of(103, 151))).as("超剩余").isEqualTo(25003);
        assertThat(allocateTip(1, Map.of(103, -1))).as("uint32 绕成超大值").isEqualTo(25003);
        assertThat(allocateTip(1, Map.of(103, 200, 999, 1)))
                .as("按维度号升序逐项判：999 不属本池先于增量总和判定").isEqualTo(25002);
        assertThat(panel().getPools(0).getRemaining()).isEqualTo(150);
    }

    @Test
    void 加点后按比例保持当前气血_不能当治疗() throws Exception {
        start(RunMode.DEV, 3, 30, null);
        player().attributes().setHealth(1000);

        AttributePanelInfo panel = allocate(Map.of(101, 150));

        assertThat(panel.getDerived().getMaxHealth()).as("丹心体质 +25%").isEqualTo(2373);
        assertThat(panel.getDerived().getHealth()).isEqualTo(1000L * 2373 / 2000);
    }

    @Test
    void 升级按绝对增量补当前值_降级只夹() throws Exception {
        start(RunMode.DEV, 1, 30, null);
        player().attributes().setHealth(1000);

        AttributePanelInfo up = setLevel(31);
        AttributePanelInfo down = setLevel(5);

        assertThat(up.getDerived().getMaxHealth()).isEqualTo(2050);
        assertThat(up.getDerived().getHealth()).isEqualTo(1050);
        assertThat(down.getDerived().getMaxHealth()).isEqualTo(750);
        assertThat(down.getDerived().getHealth()).isEqualTo(750);
    }

    // ------------------------------------------------------------------ 洗点

    @Test
    void 洗点_30级以下免费_30级起扣500金币_余额不足25012且不动_没分配过25014() throws Exception {
        start();
        setLevel(10);
        allocate(Map.of(103, 50));
        assertThat(reset(1).getPanel().getPools(0).getRemaining()).isEqualTo(50);
        assertThat(audits).isEmpty();
        assertThat(resetTip(1)).isEqualTo(25014);

        setLevel(30);
        allocate(Map.of(103, 150));
        assertThat(resetTip(1)).isEqualTo(25012);
        assertThat(panel().getPools(0).getRemaining()).isZero();

        player().wallet().add(Wallet.GOLD, 600);
        ResetAttributePointsResponse paid = reset(1);
        assertThat(paid.getErrorMessage().getId()).isZero();
        assertThat(paid.getPanel().getPools(0).getRemaining()).isEqualTo(150);
        assertThat(player().wallet().balance(Wallet.GOLD)).isEqualTo(100);
        assertThat(audits).containsExactly(new Audited(PLAYER, Wallet.GOLD, -500, 600, 100, Reason.ATTRIBUTE_RESET));
        assertThat(resetTip(4)).as("宝宝池").isEqualTo(25000);
    }

    // ------------------------------------------------------------------ 方案

    @Test
    void 开方案_超出免费数扣1000金币_自动命名_不切换_满3个回25007() throws Exception {
        start();
        assertThat(create("").getErrorMessage().getId()).isEqualTo(25012);

        player().wallet().add(Wallet.GOLD, 2500);
        CreateAttributeSchemeResponse second = create("");
        CreateAttributeSchemeResponse third = create("");
        CreateAttributeSchemeResponse fourth = create("");

        assertThat(second.getErrorMessage().getId()).isZero();
        assertThat(second.getSchemeId()).isEqualTo(2);
        assertThat(second.getPanel().getActiveSchemeId()).as("新方案不自动生效").isEqualTo(1);
        assertThat(third.getSchemeId()).isEqualTo(3);
        assertThat(third.getPanel().getSchemesList()).extracting(s -> s.getSchemeId() + s.getName())
                .containsExactly("1方案一", "2方案二", "3方案三");
        assertThat(fourth.getErrorMessage().getId()).as("满了先于扣费判").isEqualTo(25007);
        assertThat(fourth.hasPanel()).isFalse();
        assertThat(player().wallet().balance(Wallet.GOLD)).isEqualTo(500);
        assertThat(audits).extracting(Audited::reason).containsExactly(Reason.ATTRIBUTE_SCHEME_CREATE,
                Reason.ATTRIBUTE_SCHEME_CREATE);
    }

    @Test
    void 方案名校验_空白与零宽不算可见_超12个码点不合法_emoji按1个() throws Exception {
        start();
        player().wallet().add(Wallet.GOLD, 10_000);

        assertThat(create("   ").getErrorMessage().getId()).isEqualTo(25009);
        assertThat(create("　​").getErrorMessage().getId()).isEqualTo(25009);
        assertThat(create("a\u0001").getErrorMessage().getId()).isEqualTo(25009);
        assertThat(create("一二三四五六七八九十一二三").getErrorMessage().getId()).isEqualTo(25009);
        CreateAttributeSchemeResponse emoji = create("😀😀😀😀😀😀😀😀😀😀😀😀");
        assertThat(emoji.getErrorMessage().getId()).as("12 个码点（24 个 UTF-16 单元）").isZero();
    }

    @Test
    void 改名_先校验名字再找方案_同名照常成功() throws Exception {
        start();

        assertThat(rename(99, "　").getErrorMessage().getId()).as("名字非法先于方案不存在").isEqualTo(25009);
        assertThat(rename(99, "攻击流").getErrorMessage().getId()).isEqualTo(25006);
        RenameAttributeSchemeResponse renamed = rename(1, "攻击流");
        assertThat(renamed.getErrorMessage().getId()).isZero();
        assertThat(renamed.getPanel().getSchemes(0).getName()).isEqualTo("攻击流");
        assertThat(rename(1, "攻击流").getErrorMessage().getId()).isZero();
    }

    @Test
    void 切换方案_方案隔离_60秒冷却按墙钟_已是当前25010_不存在25006() throws Exception {
        start();
        setLevel(30);
        allocate(Map.of(103, 150));
        player().wallet().add(Wallet.GOLD, 1000);
        create("");

        assertThat(switchTip(9)).isEqualTo(25006);
        assertThat(switchTip(1)).isEqualTo(25010);
        AttributePanelInfo second = switchTo(2);
        long now = ManualClock.EPOCH_MILLIS_START / 1000;
        assertThat(second.getActiveSchemeId()).isEqualTo(2);
        assertThat(second.getPools(0).getRemaining()).as("新方案是干净的").isEqualTo(150);
        assertThat(second.getDerived().getPhysicalAttack()).isEqualTo(1500);
        assertThat(second.getSwitchCooldownUntil()).isEqualTo(now + 60);

        assertThat(switchTip(1)).isEqualTo(25008);
        clock.advanceMillis(59_000);
        assertThat(switchTip(1)).isEqualTo(25008);
        clock.advanceMillis(1_000);
        AttributePanelInfo back = switchTo(1);
        assertThat(back.getDimensions(2).getAllocated()).isEqualTo(150);
        assertThat(back.getDerived().getPhysicalAttack()).isEqualTo(1901);
        assertThat(back.getDerived().getHealth()).isEqualTo(back.getDerived().getMaxHealth());
    }

    // ------------------------------------------------------------------ 持久化与加载

    @Test
    void 离场写回方案与等级_重新进场原样恢复() throws Exception {
        start();
        setLevel(30);
        allocate(Map.of(103, 150));
        player().wallet().add(Wallet.GOLD, 1000);
        create("备用");
        switchTo(2);
        AttributePanelInfo before = panel();

        world.onPlayerLeave(LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER).setVoluntary(true).build());
        PlayerSave save = repo.saves().getLast();
        AttributeState stored = save.state().getAttribute();
        assertThat(save.level()).isEqualTo(30);
        assertThat(stored.getSchemesList()).extracting(AttributeScheme::getSchemeId).containsExactly(1, 2);
        assertThat(stored.getSchemes(0).getAllocatedMap()).containsExactly(Map.entry(103, 150));
        assertThat(stored.getSchemes(1).getAllocatedMap()).as("只存非 0").isEmpty();
        assertThat(stored.getActiveSchemeId()).isEqualTo(2);
        assertThat(stored.getNextSchemeId()).isEqualTo(3);
        assertThat(stored.getLastSwitchTime()).isEqualTo(ManualClock.EPOCH_MILLIS_START / 1000);

        sink.clear();
        start(RunMode.DEV, 1, save.level(), save.state());
        assertThat(panel()).isEqualTo(before);
    }

    @Test
    void 残血残蓝随离场写回_重新进场保留_0法力也保留() throws Exception {
        start();
        player().attributes().setHealth(100);
        player().attributes().setMana(0);

        world.onPlayerLeave(LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER).setVoluntary(true).build());
        PlayerSave save = repo.saves().getLast();
        assertThat(save.state().getVitals()).isEqualTo(Vitals.newBuilder().setHealth(100).setMana(0).build());

        sink.clear();
        start(RunMode.DEV, 1, save.level(), save.state());
        assertThat(panel().getDerived().getHealth()).isEqualTo(100);
        assertThat(panel().getDerived().getMana()).as("活着的只夹不补：0 法力仍是 0（同基线）").isZero();
    }

    @Test
    void 满血满蓝不写气血段_读回来按上限回满() throws Exception {
        start();
        panel();

        world.onPlayerLeave(LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER).setVoluntary(true).build());

        assertThat(repo.saves().getLast().state().hasVitals()).isFalse();
    }

    @Test
    void 阵亡存档进场回满_活着的超上限存档值夹到上限_uint64极大值不变负数() throws Exception {
        start(RunMode.DEV, 1, 1, PlayerState.newBuilder().setVitals(Vitals.newBuilder().setHealth(0).setMana(5)).build());
        DerivedAttributeInfo dead = panel().getDerived();
        assertThat(dead.getHealth()).as("阵亡：基础复活回满").isEqualTo(dead.getMaxHealth());
        assertThat(dead.getMana()).isEqualTo(dead.getMaxMana());

        start(RunMode.DEV, 1, 1, PlayerState.newBuilder().setVitals(Vitals.newBuilder().setHealth(-1).setMana(99_999)).build());
        DerivedAttributeInfo huge = panel().getDerived();
        assertThat(huge.getHealth()).isEqualTo(huge.getMaxHealth());
        assertThat(huge.getMana()).isEqualTo(huge.getMaxMana());
    }

    @Test
    void 没动过加点_存档不带属性数据_查面板不算改动() throws Exception {
        start();
        panel();

        world.onPlayerLeave(LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER).setVoluntary(true).build());

        assertThat(repo.saves().getLast().state().hasAttribute()).isFalse();
    }

    @Test
    void 加载规整_表里删掉的维度清掉_超限存档等级压回85_超量分配收敛_不认识的字段原样带回() throws Exception {
        UnknownFieldSet future = UnknownFieldSet.newBuilder()
                .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(7).build()).build();
        AttributeState stored = AttributeState.newBuilder()
                .addSchemes(AttributeScheme.newBuilder().setSchemeId(1).setName("方案一")
                        .putAllocated(103, 100).putAllocated(999, 5).putAllocated(401, 3))
                .addSchemes(AttributeScheme.newBuilder().setSchemeId(5).setName("旧方案").putAllocated(103, 500))
                .setActiveSchemeId(1).setNextSchemeId(6).setUnknownFields(future)
                .build();

        start(RunMode.DEV, 1, 200, PlayerState.newBuilder().setAttribute(stored).build());

        ScenePlayer player = player();
        assertThat(player.level()).isEqualTo(85);
        PlayerAttributes attributes = player.attributes();
        assertThat(attributes.scheme(1).allocatedView()).as("999 不在表里被清掉；401 在表里（宝宝维度）保留但不计入角色")
                .containsOnlyKeys(103, 401);
        assertThat(attributes.scheme(5).allocated(103)).as("500 > 85 级总量 425，整池清零").isZero();
        AttributePanelInfo panel = panel();
        assertThat(panel.getPools(0).getRemaining()).isEqualTo(325);
        assertThat(WorldTestAccess.persistentState(player).getAttribute().getUnknownFields()).isEqualTo(future);
    }

    @Test
    void 当前方案指向不存在的方案_写操作回25006_二级属性按未分配算() throws Exception {
        AttributeState stored = AttributeState.newBuilder()
                .addSchemes(AttributeScheme.newBuilder().setSchemeId(1).setName("方案一").putAllocated(103, 5))
                .setActiveSchemeId(7).setNextSchemeId(2)
                .build();
        start(RunMode.DEV, 1, 1, PlayerState.newBuilder().setAttribute(stored).build());

        assertThat(allocateTip(1, Map.of(103, 5))).isEqualTo(25006);
        assertThat(resetTip(1)).isEqualTo(25006);
        assertThat(panel().getDerived().getPhysicalAttack()).isEqualTo(50);
        assertThat(panel().getPools(0).getRemaining()).as("没有当前方案按已用 0 算（同基线）").isEqualTo(5);
    }

    // ------------------------------------------------------------------ 工具

    private ScenePlayer player() {
        return WorldTestAccess.player(world, LINK, SESSION);
    }

    private AttributePanelInfo panel() throws InvalidProtocolBufferException {
        return GetAttributePanelResponse.parseFrom(
                call("GetAttributePanel", GetAttributePanelRequest.getDefaultInstance()).getSerializedMessage()).getPanel();
    }

    private AttributePanelInfo setLevel(int level) throws InvalidProtocolBufferException {
        List<MessageContent> sent = callAll("GmSetPlayerLevel", GmSetPlayerLevelRequest.newBuilder().setLevel(level).build(), 2);
        GmSetPlayerLevelResponse response = GmSetPlayerLevelResponse.parseFrom(sent.get(1).getSerializedMessage());
        assertThat(response.getErrorMessage().getId()).isZero();
        return response.getPanel();
    }

    private AttributePanelInfo allocate(Map<Integer, Integer> target) throws InvalidProtocolBufferException {
        AllocateAttributePointsResponse response = allocateResponse(1, target);
        assertThat(response.getErrorMessage().getId()).isZero();
        return response.getPanel();
    }

    private int allocateTip(int poolId, Map<Integer, Integer> target) throws InvalidProtocolBufferException {
        AllocateAttributePointsResponse response = allocateResponse(poolId, target);
        assertThat(response.hasPanel()).isEqualTo(response.getErrorMessage().getId() == 0);
        return response.getErrorMessage().getId();
    }

    private AllocateAttributePointsResponse allocateResponse(int poolId, Map<Integer, Integer> target)
            throws InvalidProtocolBufferException {
        return AllocateAttributePointsResponse.parseFrom(call("AllocateAttributePoints",
                AllocateAttributePointsRequest.newBuilder().setPoolId(poolId).putAllAllocated(target).build())
                .getSerializedMessage());
    }

    private ResetAttributePointsResponse reset(int poolId) throws InvalidProtocolBufferException {
        return ResetAttributePointsResponse.parseFrom(call("ResetAttributePoints",
                ResetAttributePointsRequest.newBuilder().setPoolId(poolId).build()).getSerializedMessage());
    }

    private int resetTip(int poolId) throws InvalidProtocolBufferException {
        ResetAttributePointsResponse response = reset(poolId);
        assertThat(response.hasPanel()).isEqualTo(response.getErrorMessage().getId() == 0);
        return response.getErrorMessage().getId();
    }

    private CreateAttributeSchemeResponse create(String name) throws InvalidProtocolBufferException {
        return CreateAttributeSchemeResponse.parseFrom(call("CreateAttributeScheme",
                CreateAttributeSchemeRequest.newBuilder().setName(name).build()).getSerializedMessage());
    }

    private RenameAttributeSchemeResponse rename(int schemeId, String name) throws InvalidProtocolBufferException {
        return RenameAttributeSchemeResponse.parseFrom(call("RenameAttributeScheme",
                RenameAttributeSchemeRequest.newBuilder().setSchemeId(schemeId).setName(name).build()).getSerializedMessage());
    }

    private AttributePanelInfo switchTo(int schemeId) throws InvalidProtocolBufferException {
        SwitchAttributeSchemeResponse response = switchResponse(schemeId);
        assertThat(response.getErrorMessage().getId()).isZero();
        return response.getPanel();
    }

    private int switchTip(int schemeId) throws InvalidProtocolBufferException {
        SwitchAttributeSchemeResponse response = switchResponse(schemeId);
        assertThat(response.hasPanel()).isEqualTo(response.getErrorMessage().getId() == 0);
        return response.getErrorMessage().getId();
    }

    private SwitchAttributeSchemeResponse switchResponse(int schemeId) throws InvalidProtocolBufferException {
        return SwitchAttributeSchemeResponse.parseFrom(call("SwitchAttributeScheme",
                SwitchAttributeSchemeRequest.newBuilder().setSchemeId(schemeId).build()).getSerializedMessage());
    }

    private static void assertDerived(AttributePanelInfo panel, long maxHealth, long maxMana, long physical, long magic,
                                      long speed, long defense) {
        DerivedAttributeInfo derived = panel.getDerived();
        assertThat(List.of(derived.getMaxHealth(), derived.getMaxMana(), derived.getPhysicalAttack(),
                derived.getMagicAttack(), derived.getSpeed(), derived.getDefense()))
                .containsExactly(maxHealth, maxMana, physical, magic, speed, defense);
    }

    /** 发一条请求，返回它的应答（每条请求恰好一个下行）。 */
    private MessageContent call(String method, Message request) {
        return callAll(method, request, 1).get(0);
    }

    /** 发一条请求，返回它引起的全部下行（恰好 {@code expected} 条，最后一条是应答）。 */
    private List<MessageContent> callAll(String method, Message request, int expected) {
        int messageId = Contracts.REGISTRY.requireId(SERVICE, method);
        long requestId = ++requestIds;
        int before = sink.to(LINK, SESSION).size();
        handler.onClientForward(LINK, ClientForward.newBuilder()
                .setSessionId(SESSION)
                .setPlayerId(PLAYER)
                .setMessageId(messageId)
                .setBody(request.toByteString())
                .setRequestId(requestId)
                .build());
        List<MessageContent> sent = sink.to(LINK, SESSION);
        assertThat(sent).hasSize(before + expected);
        MessageContent reply = sent.getLast();
        assertThat(reply.getMessageId()).isEqualTo(messageId);
        assertThat(reply.getId()).isEqualTo(requestId);
        return List.copyOf(sent.subList(before, sent.size()));
    }
}
