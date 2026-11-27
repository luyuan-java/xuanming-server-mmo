package com.game.scene.appearance;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.api.proto.PlayerEnter;
import com.game.common.RunMode;
import com.game.player.store.state.BagItemState;
import com.game.player.store.state.BagState;
import com.game.player.store.state.PlayerState;
import com.game.proto.ActorAppearanceChangedS2C;
import com.game.proto.AppearanceStateNotify;
import com.game.proto.CancelAppearanceTrialRequest;
import com.game.proto.CancelAppearanceTrialResponse;
import com.game.proto.EquipPetCosmeticRequest;
import com.game.proto.EquipPetCosmeticResponse;
import com.game.proto.EquipSkillVfxRequest;
import com.game.proto.EquipSkillVfxResponse;
import com.game.proto.GetAppearanceInfoRequest;
import com.game.proto.GetAppearanceInfoResponse;
import com.game.proto.MessageContent;
import com.game.proto.StartAppearanceTrialRequest;
import com.game.proto.StartAppearanceTrialResponse;
import com.game.proto.SwitchAppearanceRequest;
import com.game.proto.SwitchAppearanceResponse;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.bag.BagService;
import com.game.scene.bag.BagTables;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingAssetAudit;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.ClientRequestHandler;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerSnapshots;
import com.game.scene.world.Scene;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.Vec3;
import com.game.scene.world.WorldTestAccess;
import com.game.table.ConfigTables;
import com.google.protobuf.Message;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 角色形象切换（10 元角色形象卡）与 1 小时免费试用（bit 存储）全链路协议测试。 */
class AppearanceFeatureTest {

    private static final long LINK = 1;
    private static final int SESSION = 11;
    private static final long PLAYER = 2001;
    private static final String SERVICE = AppearanceFeature.SERVICE;
    private static final int NOTIFY_CHANGED = Contracts.REGISTRY.requireId(SERVICE, "NotifyActorAppearanceChanged");
    private static final int NOTIFY_STATE = Contracts.REGISTRY.requireId(SERVICE, "NotifyAppearanceState");

    private static ConfigTables config;
    private final RecordingSink sink = new RecordingSink();
    private final FakePlayerRepository repo = new FakePlayerRepository();
    private final ManualClock clock = new ManualClock();
    private final AtomicLong guidSeq = new AtomicLong(1L << 60);
    private final AtomicLong requestSeq = new AtomicLong(1);
    private SceneWorld world;
    private ClientRequestHandler handler;
    private BagService bagService;
    private AppearanceService appearanceService;

    @BeforeAll
    static void loadTables() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
        config = ConfigTables.load(dir);
    }

    private void start(String initialAppearance, int cardCount) {
        BagTables bagTables = BagTables.from(config);
        AppearanceTables appearanceTables = AppearanceTables.from(config);
        bagService = new BagService(bagTables, count -> {
            long[] out = new long[count];
            for (int i = 0; i < count; i++) out[i] = guidSeq.incrementAndGet();
            return out;
        }, new RecordingAssetAudit(), GainAnomalyDetector.off(), SceneMetrics.noop());
        appearanceService = new AppearanceService(appearanceTables, bagService, clock, Contracts.REGISTRY);

        world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo,
                new AtomicLong(5000)::incrementAndGet, clock, SceneMetrics.noop(), player -> {
                    bagService.initializeOnLoad(player);
                    appearanceService.initializeOnLoad(player);
                }, PlayerSnapshots.NONE);
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV,
                List.of(new AppearanceFeature(appearanceService)));
        Scene scene = world.createScene(1);

        PlayerState.Builder state = PlayerState.newBuilder();
        if (cardCount > 0) {
            state.setBag(BagState.newBuilder().addItems(BagItemState.newBuilder()
                    .setItemUuid(guidSeq.incrementAndGet())
                    .setConfigId(appearanceTables.rule().cardItemId())
                    .setStackSize(cardCount)
                    .setPos(0)
                    .setBagType(0)
                    .setAcquireSeq(1)
                    .build()));
        }
        repo.put(new PlayerData(PLAYER, 1, 1, 1, initialAppearance, 1, 0, Vec3.ORIGIN, state.build()));
        world.onPlayerEnter(LINK, PlayerEnter.newBuilder()
                .setSessionId(SESSION).setPlayerId(PLAYER).setSceneId(scene.sceneId()).setOwnerEpoch(1).build());
        repo.completeAll();
        sink.clear();
    }

    private MessageContent call(String method, Message body) {
        int msgId = Contracts.REGISTRY.requireId(SERVICE, method);
        long reqId = requestSeq.getAndIncrement();
        sink.clear();
        handler.onClientForward(LINK, ClientForward.newBuilder()
                .setSessionId(SESSION)
                .setPlayerId(PLAYER)
                .setMessageId(msgId)
                .setBody(body.toByteString())
                .setRequestId(reqId)
                .build());
        return sink.to(LINK, SESSION).stream()
                .filter(m -> m.getMessageId() == msgId && m.getId() == reqId)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 消费形象卡永久切换_幂等重放不重复扣卡_缺卡与非法形象拒绝() throws Exception {
        start("00_reference_topright_boy", 1);
        GetAppearanceInfoResponse infoResp = GetAppearanceInfoResponse.parseFrom(
                call("GetAppearanceInfo", GetAppearanceInfoRequest.getDefaultInstance()).getSerializedMessage());
        assertThat(infoResp.getErrorMessage().getId()).isZero();
        assertThat(infoResp.getInfo().getOwnedCardCount()).isEqualTo(1);
        assertThat(infoResp.getInfo().getMallPriceFen()).isEqualTo(1000L);
        assertThat(infoResp.getInfo().getAppearancesCount()).isEqualTo(9);
        assertThat(infoResp.getInfo().getAppearancesList().stream()
                .filter(e -> e.getCardUnlockable()).count()).isEqualTo(8);
        assertThat(infoResp.getInfo().getAppearancesList().stream()
                .filter(e -> !e.getCardUnlockable()).map(e -> e.getAppearanceId()).toList())
                .containsExactly("20_star_formation_master_girl");
        assertThat(infoResp.getInfo().getAppearancesList().stream()
                .filter(e -> e.getOwned()).map(e -> e.getAppearanceId()).toList())
                .containsExactly("00_reference_topright_boy");

        // 玩法专属形象 20_star_formation_master_girl（card_unlockable=0）不可用形象卡买断
        SwitchAppearanceResponse exclusive = SwitchAppearanceResponse.parseFrom(
                call("SwitchAppearance", SwitchAppearanceRequest.newBuilder()
                        .setClientRequestId(5000L).setAppearanceId("20_star_formation_master_girl").build())
                        .getSerializedMessage());
        assertThat(exclusive.getErrorMessage().getId()).isEqualTo(AppearanceService.APPEARANCE_NOT_CARD_UNLOCKABLE);
        assertThat(exclusive.getInfo().getOwnedCardCount()).isZero(); // 失败不返回快照，卡仍保留 1 张

        SwitchAppearanceRequest req = SwitchAppearanceRequest.newBuilder()
                .setClientRequestId(5001L)
                .setAppearanceId("01_ice_sword_girl")
                .build();
        SwitchAppearanceResponse first = SwitchAppearanceResponse.parseFrom(
                call("SwitchAppearance", req).getSerializedMessage());
        assertThat(first.getErrorMessage().getId()).isZero();
        assertThat(first.getReplayed()).isFalse();
        assertThat(first.getInfo().getPermanentAppearanceId()).isEqualTo("01_ice_sword_girl");
        assertThat(first.getInfo().getOwnedCardCount()).isZero();
        assertThat(first.getInfo().getAppearancesList().stream()
                .filter(e -> e.getOwned()).map(e -> e.getAppearanceId()).toList())
                .containsExactly("00_reference_topright_boy", "01_ice_sword_girl");
        assertThat(sink.messageIdsTo(LINK, SESSION)).contains(NOTIFY_CHANGED);
        ActorAppearanceChangedS2C changed = ActorAppearanceChangedS2C.parseFrom(
                sink.to(LINK, SESSION).stream().filter(m -> m.getMessageId() == NOTIFY_CHANGED)
                        .findFirst().orElseThrow().getSerializedMessage());
        assertThat(changed.getAppearanceId()).isEqualTo("01_ice_sword_girl");
        assertThat(changed.getIsTrial()).isFalse();

        // 同键重放：不扣卡、返回 replayed=true
        SwitchAppearanceResponse replay = SwitchAppearanceResponse.parseFrom(
                call("SwitchAppearance", req).getSerializedMessage());
        assertThat(replay.getErrorMessage().getId()).isZero();
        assertThat(replay.getReplayed()).isTrue();

        // 同键换参或切换 expect_free_switch：回 1005
        SwitchAppearanceResponse mismatch = SwitchAppearanceResponse.parseFrom(
                call("SwitchAppearance", SwitchAppearanceRequest.newBuilder()
                        .setClientRequestId(5001L).setAppearanceId("02_fire_talisman_boy").build())
                        .getSerializedMessage());
        assertThat(mismatch.getErrorMessage().getId()).isEqualTo(AppearanceService.INVALID_PARAMETER);
        SwitchAppearanceResponse flippedFree = SwitchAppearanceResponse.parseFrom(
                call("SwitchAppearance", SwitchAppearanceRequest.newBuilder()
                        .setClientRequestId(5001L).setAppearanceId("01_ice_sword_girl")
                        .setExpectFreeSwitch(true).build())
                        .getSerializedMessage());
        assertThat(flippedFree.getErrorMessage().getId()).isEqualTo(AppearanceService.INVALID_PARAMETER);

        // 0 卡免费切回已拥有的初始形象 00_reference_topright_boy（expect_free_switch=true）
        SwitchAppearanceResponse freeBack = SwitchAppearanceResponse.parseFrom(
                call("SwitchAppearance", SwitchAppearanceRequest.newBuilder()
                        .setClientRequestId(5004L).setAppearanceId("00_reference_topright_boy")
                        .setExpectFreeSwitch(true).build())
                        .getSerializedMessage());
        assertThat(freeBack.getErrorMessage().getId()).isZero();
        assertThat(freeBack.getInfo().getPermanentAppearanceId()).isEqualTo("00_reference_topright_boy");
        assertThat(freeBack.getInfo().getOwnedCardCount()).isZero();

        // 对已拥有的非当前形象发起试用：回 kAppearanceAlreadyOwned
        StartAppearanceTrialResponse ownedTrial = StartAppearanceTrialResponse.parseFrom(
                call("StartAppearanceTrial", StartAppearanceTrialRequest.newBuilder()
                        .setClientRequestId(5005L).setAppearanceId("01_ice_sword_girl").build())
                        .getSerializedMessage());
        assertThat(ownedTrial.getErrorMessage().getId()).isEqualTo(AppearanceService.APPEARANCE_ALREADY_OWNED);

        // expect_free_switch=true 切未拥有形象：fail-closed 回 kAppearanceNotOwned
        SwitchAppearanceResponse notOwned = SwitchAppearanceResponse.parseFrom(
                call("SwitchAppearance", SwitchAppearanceRequest.newBuilder()
                        .setClientRequestId(5006L).setAppearanceId("02_fire_talisman_boy")
                        .setExpectFreeSwitch(true).build())
                        .getSerializedMessage());
        assertThat(notOwned.getErrorMessage().getId()).isEqualTo(AppearanceService.APPEARANCE_NOT_OWNED);

        // 缺卡再切未拥有形象：回 kAppearanceNoCard
        SwitchAppearanceResponse noCard = SwitchAppearanceResponse.parseFrom(
                call("SwitchAppearance", SwitchAppearanceRequest.newBuilder()
                        .setClientRequestId(5002L).setAppearanceId("02_fire_talisman_boy").build())
                        .getSerializedMessage());
        assertThat(noCard.getErrorMessage().getId()).isEqualTo(AppearanceService.APPEARANCE_NO_CARD);

        // 已退场/删除角色被拒
        for (String removed : List.of("30_han_xiangzi", "04_mountain_guardian_boy",
                "08_alchemy_prodigy_boy", "17_ghost_script_calligrapher_boy")) {
            SwitchAppearanceResponse invalid = SwitchAppearanceResponse.parseFrom(
                    call("SwitchAppearance", SwitchAppearanceRequest.newBuilder()
                            .setClientRequestId(5010L + removed.length()).setAppearanceId(removed).build())
                            .getSerializedMessage());
            assertThat(invalid.getErrorMessage().getId()).isEqualTo(AppearanceService.APPEARANCE_INVALID);
        }
    }

    @Test
    void 免费试用一小时记bit_不可二次试用_到期自动还原并支持提前取消() throws Exception {
        start("00_reference_topright_boy", 0);
        StartAppearanceTrialResponse started = StartAppearanceTrialResponse.parseFrom(
                call("StartAppearanceTrial", StartAppearanceTrialRequest.newBuilder()
                        .setClientRequestId(6001L)
                        .setAppearanceId("02_fire_talisman_boy")
                        .build()).getSerializedMessage());
        assertThat(started.getErrorMessage().getId()).isZero();
        assertThat(started.getInfo().getActiveAppearanceId()).isEqualTo("02_fire_talisman_boy");
        assertThat(started.getInfo().getPermanentAppearanceId()).isEqualTo("00_reference_topright_boy");
        assertThat(started.getInfo().getTrialExpiresAt()).isEqualTo(clock.epochMillis() / 1000L + 3600L);

        // 同一形象二次试用被 bit 拦截
        StartAppearanceTrialResponse dup = StartAppearanceTrialResponse.parseFrom(
                call("StartAppearanceTrial", StartAppearanceTrialRequest.newBuilder()
                        .setClientRequestId(6002L)
                        .setAppearanceId("02_fire_talisman_boy")
                        .build()).getSerializedMessage());
        assertThat(dup.getErrorMessage().getId()).isEqualTo(AppearanceService.APPEARANCE_TRIAL_ALREADY_USED);

        // 内部授形 05_celestial_musician_girl：不中断当前试用且可 0 卡免费切换，重复授形幂等
        ScenePlayer player = WorldTestAccess.player(world, LINK, SESSION);
        sink.clear();
        assertThat(appearanceService.grantAppearance(world, player, "05_celestial_musician_girl")).isZero();
        assertThat(sink.messageIdsTo(LINK, SESSION)).contains(NOTIFY_STATE);
        assertThat(player.effectiveAppearanceId(clock.epochMillis() / 1000L)).isEqualTo("02_fire_talisman_boy");
        sink.clear();
        assertThat(appearanceService.grantAppearance(world, player, "05_celestial_musician_girl")).isZero();
        assertThat(sink.messageIdsTo(LINK, SESSION)).isEmpty();

        // 推进 1 小时，tickExpiries 自动还原永久形象并广播 + 推 AppearanceStateNotify(trial_expired=true)
        sink.clear();
        clock.advanceMillis(3600_000L);
        appearanceService.tickExpiries(world);
        assertThat(player.effectiveAppearanceId(clock.epochMillis() / 1000L)).isEqualTo("00_reference_topright_boy");
        assertThat(sink.messageIdsTo(LINK, SESSION)).contains(NOTIFY_CHANGED, NOTIFY_STATE);
        AppearanceStateNotify expiredNotify = AppearanceStateNotify.parseFrom(
                sink.to(LINK, SESSION).stream().filter(m -> m.getMessageId() == NOTIFY_STATE)
                        .findFirst().orElseThrow().getSerializedMessage());
        assertThat(expiredNotify.getTrialExpired()).isTrue();
        assertThat(expiredNotify.getInfo().getTrialAppearanceId()).isEmpty();

        // 试用另一个形象并提前取消
        StartAppearanceTrialResponse second = StartAppearanceTrialResponse.parseFrom(
                call("StartAppearanceTrial", StartAppearanceTrialRequest.newBuilder()
                        .setClientRequestId(6003L)
                        .setAppearanceId("09_bamboo_archer_girl")
                        .build()).getSerializedMessage());
        assertThat(second.getErrorMessage().getId()).isZero();

        CancelAppearanceTrialResponse cancelled = CancelAppearanceTrialResponse.parseFrom(
                call("CancelAppearanceTrial", CancelAppearanceTrialRequest.getDefaultInstance()).getSerializedMessage());
        assertThat(cancelled.getErrorMessage().getId()).isZero();
        assertThat(cancelled.getInfo().getTrialAppearanceId()).isEmpty();
        assertThat(cancelled.getInfo().getActiveAppearanceId()).isEqualTo("00_reference_topright_boy");

        CancelAppearanceTrialResponse noTrial = CancelAppearanceTrialResponse.parseFrom(
                call("CancelAppearanceTrial", CancelAppearanceTrialRequest.getDefaultInstance()).getSerializedMessage());
        assertThat(noTrial.getErrorMessage().getId()).isEqualTo(AppearanceService.APPEARANCE_NO_ACTIVE_TRIAL);
    }

    @Test
    void 人物收藏系列进度实时派生_试用不计入_集齐点亮印章与合篇() throws Exception {
        start("00_reference_topright_boy", 1);
        GetAppearanceInfoResponse init = GetAppearanceInfoResponse.parseFrom(
                call("GetAppearanceInfo", GetAppearanceInfoRequest.getDefaultInstance()).getSerializedMessage());
        assertThat(init.getInfo().getSeriesProgressCount()).isEqualTo(3);
        var lantern = init.getInfo().getSeriesProgress(0);
        assertThat(lantern.getSeriesId()).isEqualTo(1);
        assertThat(lantern.getSeriesKey()).isEqualTo("lantern_courtyard");
        assertThat(lantern.getOwnedCount()).isEqualTo(1);
        assertThat(lantern.getTotalCount()).isEqualTo(3);
        assertThat(lantern.getCompleted()).isFalse();
        assertThat(lantern.getMemberAppearanceIdsList()).containsExactly(
                "00_reference_topright_boy", "09_bamboo_archer_girl", "10_crimson_spear_girl");

        // 试用同系列成员不计入系列拥有数
        StartAppearanceTrialResponse trial = StartAppearanceTrialResponse.parseFrom(
                call("StartAppearanceTrial", StartAppearanceTrialRequest.newBuilder()
                        .setClientRequestId(7001L).setAppearanceId("09_bamboo_archer_girl").build())
                        .getSerializedMessage());
        assertThat(trial.getInfo().getSeriesProgress(0).getOwnedCount()).isEqualTo(1);
        assertThat(trial.getInfo().getSeriesProgress(0).getCompleted()).isFalse();

        // 形象卡解锁 09 + 内部授形 10 -> 留灯院旧友 3/3 集齐
        SwitchAppearanceResponse unlocked = SwitchAppearanceResponse.parseFrom(
                call("SwitchAppearance", SwitchAppearanceRequest.newBuilder()
                        .setClientRequestId(7002L).setAppearanceId("09_bamboo_archer_girl").build())
                        .getSerializedMessage());
        assertThat(unlocked.getInfo().getSeriesProgress(0).getOwnedCount()).isEqualTo(2);
        assertThat(unlocked.getInfo().getSeriesProgress(0).getCompleted()).isFalse();

        ScenePlayer player = WorldTestAccess.player(world, LINK, SESSION);
        sink.clear();
        assertThat(appearanceService.grantAppearance(world, player, "10_crimson_spear_girl")).isZero();
        AppearanceStateNotify grantedNotify = AppearanceStateNotify.parseFrom(
                sink.to(LINK, SESSION).stream().filter(m -> m.getMessageId() == NOTIFY_STATE)
                        .findFirst().orElseThrow().getSerializedMessage());
        var done = grantedNotify.getInfo().getSeriesProgress(0);
        assertThat(done.getOwnedCount()).isEqualTo(3);
        assertThat(done.getTotalCount()).isEqualTo(3);
        assertThat(done.getCompleted()).isTrue();
        assertThat(done.getCompletionBadgeName()).isEqualTo("留灯同袍");
        assertThat(done.getEpilogueTitle()).isEqualTo("灯下三碗面");
        assertThat(grantedNotify.getInfo().getSeriesProgress(1).getOwnedCount()).isZero();
        assertThat(grantedNotify.getInfo().getSeriesProgress(2).getOwnedCount()).isZero();
    }

    @Test
    void 九名现役角色人物志配置完整且无已退场人物残留_半残行规则校验拦截() {
        assertThat(AppearanceTables.biographyFieldsValid("", "", "", "", "")).isTrue();
        assertThat(AppearanceTables.biographyFieldsValid("   ", "\t", "", "\n", "")).isTrue();
        assertThat(AppearanceTables.biographyFieldsValid("陆照禾", "阿禾", "小传", "第四只碗", "正文")).isTrue();
        assertThat(AppearanceTables.biographyFieldsValid("霜见雪", "", "小传", "霜阶扫雪", "正文")).isTrue();
        assertThat(AppearanceTables.biographyFieldsValid("霜见雪", "", "", "霜阶扫雪", "正文")).isFalse();
        assertThat(AppearanceTables.biographyFieldsValid("", "小名", "", "", "")).isFalse();
        assertThat(AppearanceTables.biographyFieldsValid("星璇", "", "小传", "十八星印", "   ")).isFalse();

        var rows = config.characterAppearance().all();
        assertThat(rows).hasSize(9);
        assertThat(rows).allSatisfy(row -> {
            assertThat(AppearanceTables.biographyFieldsValid(
                    row.getArchiveName(), row.getNickname(), row.getBiographySummary(),
                    row.getStoryTitle(), row.getStoryBody())).isTrue();
            assertThat(row.getArchiveName()).isNotBlank();
            assertThat(row.getBiographySummary()).isNotBlank();
            assertThat(row.getStoryTitle()).isNotBlank();
            assertThat(row.getStoryBody()).isNotBlank();
            assertThat(row.getBiographySummary()).doesNotContain("闻砚生");
            assertThat(row.getStoryBody()).doesNotContain("闻砚生");
        });
    }

    @Test
    void 灵宠幻化与技能特效_未拥有拒绝_授形不自动穿戴_按实例隔离与战斗快照透传() throws Exception {
        start("00_reference_topright_boy", 0);
        ScenePlayer player = WorldTestAccess.player(world, LINK, SESSION);
        var petA = new com.game.scene.player.PlayerPets.Pet(70001L, 12, 10, 1000L);
        var petB = new com.game.scene.player.PlayerPets.Pet(70002L, 12, 10, 1001L);
        player.pets().add(petA);
        player.pets().add(petB);

        // 初始目录完整但全部未拥有，未拥有穿戴必须被拒绝
        GetAppearanceInfoResponse init = GetAppearanceInfoResponse.parseFrom(
                call("GetAppearanceInfo", GetAppearanceInfoRequest.getDefaultInstance()).getSerializedMessage());
        assertThat(init.getInfo().getPetCosmeticsCount()).isEqualTo(3);
        assertThat(init.getInfo().getSkillVfxCosmeticsCount()).isEqualTo(3);
        assertThat(init.getInfo().getPetCosmeticsList()).allSatisfy(e -> assertThat(e.getOwned()).isFalse());
        assertThat(init.getInfo().getSkillVfxCosmeticsList()).allSatisfy(e -> assertThat(e.getOwned()).isFalse());

        EquipPetCosmeticResponse notOwnedPet = EquipPetCosmeticResponse.parseFrom(
                call("EquipPetCosmetic", EquipPetCosmeticRequest.newBuilder()
                        .setClientRequestId(8001L).setPetId(70001L).setCosmeticId(1)
                        .setExpectFreeEquip(true).build())
                        .getSerializedMessage());
        assertThat(notOwnedPet.getErrorMessage().getId()).isEqualTo(AppearanceService.APPEARANCE_NOT_OWNED);

        EquipPetCosmeticResponse noCardPet = EquipPetCosmeticResponse.parseFrom(
                call("EquipPetCosmetic", EquipPetCosmeticRequest.newBuilder()
                        .setClientRequestId(80011L).setPetId(70001L).setCosmeticId(1).build())
                        .getSerializedMessage());
        assertThat(noCardPet.getErrorMessage().getId()).isEqualTo(AppearanceService.APPEARANCE_NO_CARD);

        EquipPetCosmeticResponse missingPet = EquipPetCosmeticResponse.parseFrom(
                call("EquipPetCosmetic", EquipPetCosmeticRequest.newBuilder()
                        .setClientRequestId(8002L).setPetId(99999L).setCosmeticId(1).build())
                        .getSerializedMessage());
        assertThat(missingPet.getErrorMessage().getId()).isEqualTo(AppearanceService.PET_NOT_FOUND);

        // 授形灵宠幻化 #1：只置位拥有位图，不自动穿戴
        assertThat(appearanceService.grantPetCosmetic(world, player, 1)).isZero();
        assertThat(petA.cosmeticId()).isZero();
        assertThat(petB.cosmeticId()).isZero();

        // 给 petA 穿戴 #1：petB 仍保持原形（按灵宠实例隔离）
        EquipPetCosmeticResponse eqPet = EquipPetCosmeticResponse.parseFrom(
                call("EquipPetCosmetic", EquipPetCosmeticRequest.newBuilder()
                        .setClientRequestId(8003L).setPetId(70001L).setCosmeticId(1).build())
                        .getSerializedMessage());
        assertThat(eqPet.getErrorMessage().getId()).isZero();
        assertThat(eqPet.getEquippedCosmeticId()).isEqualTo(1);
        assertThat(petA.cosmeticId()).isEqualTo(1);
        assertThat(petB.cosmeticId()).isZero();
        assertThat(eqPet.getInfo().getEquippedPetCosmeticsCount()).isEqualTo(1);
        assertThat(eqPet.getInfo().getEquippedPetCosmetics(0).getPetId()).isEqualTo(70001L);

        // 恢复原形
        EquipPetCosmeticResponse clearPet = EquipPetCosmeticResponse.parseFrom(
                call("EquipPetCosmetic", EquipPetCosmeticRequest.newBuilder()
                        .setClientRequestId(8004L).setPetId(70001L).setCosmeticId(0).build())
                        .getSerializedMessage());
        assertThat(clearPet.getErrorMessage().getId()).isZero();
        assertThat(petA.cosmeticId()).isZero();
        assertThat(clearPet.getInfo().getEquippedPetCosmeticsCount()).isZero();

        // 技能特效：未拥有免费穿戴拒绝、无券拒绝、技能错配拒绝、授形不自动启用、启用后透传 BattlePlayerSnapshot
        EquipSkillVfxResponse notOwnedVfx = EquipSkillVfxResponse.parseFrom(
                call("EquipSkillVfx", EquipSkillVfxRequest.newBuilder()
                        .setClientRequestId(8005L).setSkillTableId(1201).setCosmeticId(1)
                        .setExpectFreeEquip(true).build())
                        .getSerializedMessage());
        assertThat(notOwnedVfx.getErrorMessage().getId()).isEqualTo(AppearanceService.APPEARANCE_NOT_OWNED);

        EquipSkillVfxResponse noCardVfx = EquipSkillVfxResponse.parseFrom(
                call("EquipSkillVfx", EquipSkillVfxRequest.newBuilder()
                        .setClientRequestId(80051L).setSkillTableId(1201).setCosmeticId(1).build())
                        .getSerializedMessage());
        assertThat(noCardVfx.getErrorMessage().getId()).isEqualTo(AppearanceService.APPEARANCE_NO_CARD);

        assertThat(appearanceService.grantSkillVfx(world, player, 1)).isZero();
        assertThat(player.appearance().equippedSkillVfx(1201)).isZero();

        EquipSkillVfxResponse wrongSkill = EquipSkillVfxResponse.parseFrom(
                call("EquipSkillVfx", EquipSkillVfxRequest.newBuilder()
                        .setClientRequestId(8006L).setSkillTableId(1202).setCosmeticId(1).build())
                        .getSerializedMessage());
        assertThat(wrongSkill.getErrorMessage().getId()).isEqualTo(AppearanceService.APPEARANCE_INVALID);

        EquipSkillVfxResponse eqVfx = EquipSkillVfxResponse.parseFrom(
                call("EquipSkillVfx", EquipSkillVfxRequest.newBuilder()
                        .setClientRequestId(8007L).setSkillTableId(1201).setCosmeticId(1).build())
                        .getSerializedMessage());
        assertThat(eqVfx.getErrorMessage().getId()).isZero();
        assertThat(eqVfx.getEquippedCosmeticId()).isEqualTo(1);
        assertThat(eqVfx.getInfo().getEquippedSkillVfxMap()).containsEntry(1201, 1);

        // 战斗快照透传技能特效，不影响任何战斗数值
        var battleTables = com.game.scene.battle.SceneBattleTables.from(config);
        var snap = com.game.scene.battle.BattleSnapshots.build(
                player, battleTables, List.of(), com.game.proto.BattleRouting.getDefaultInstance());
        assertThat(snap.getEquippedSkillVfxMap()).containsEntry(1201, 1);

        // 恢复默认特效
        EquipSkillVfxResponse clearVfx = EquipSkillVfxResponse.parseFrom(
                call("EquipSkillVfx", EquipSkillVfxRequest.newBuilder()
                        .setClientRequestId(8008L).setSkillTableId(1201).setCosmeticId(0).build())
                        .getSerializedMessage());
        assertThat(clearVfx.getErrorMessage().getId()).isZero();
        assertThat(clearVfx.getInfo().getEquippedSkillVfxMap()).isEmpty();
    }

    @Test
    void 灵宠幻化与技能特效支持百宝阁外观券原子解锁_幂等重放与专属拦截() throws Exception {
        start("00_reference_topright_boy", 0);
        ScenePlayer player = WorldTestAccess.player(world, LINK, SESSION);
        var pet = new com.game.scene.player.PlayerPets.Pet(71001L, 12, 10, 1000L);
        player.pets().add(pet);
        assertThat(bagService.addItems(player, com.game.scene.player.BagType.INVENTORY,
                java.util.Map.of(3411, 2L, 3412, 2L), true,
                com.game.scene.audit.AssetAudit.Reason.GM_GRANT, 0L, "").ok()).isTrue();

        GetAppearanceInfoResponse info = GetAppearanceInfoResponse.parseFrom(
                call("GetAppearanceInfo", GetAppearanceInfoRequest.getDefaultInstance()).getSerializedMessage());
        assertThat(info.getInfo().getPetCosmetics(0).getCardItemId()).isEqualTo(3411);
        assertThat(info.getInfo().getPetCosmetics(0).getOwnedCardCount()).isEqualTo(2);
        assertThat(info.getInfo().getPetCosmetics(0).getSpiritShopGoodsId()).isEqualTo(303);
        assertThat(info.getInfo().getPetCosmetics(2).getCardItemId()).isZero();
        assertThat(info.getInfo().getSkillVfxCosmetics(0).getCardItemId()).isEqualTo(3412);
        assertThat(info.getInfo().getSkillVfxCosmetics(0).getOwnedCardCount()).isEqualTo(2);
        assertThat(info.getInfo().getSkillVfxCosmetics(0).getSpiritShopGoodsId()).isEqualTo(304);
        assertThat(info.getInfo().getSkillVfxCosmetics(2).getCardItemId()).isZero();

        // 专属幻化 #3 拒绝耗券
        EquipPetCosmeticResponse exclusivePet = EquipPetCosmeticResponse.parseFrom(
                call("EquipPetCosmetic", EquipPetCosmeticRequest.newBuilder()
                        .setClientRequestId(9101L).setPetId(71001L).setCosmeticId(3).build())
                        .getSerializedMessage());
        assertThat(exclusivePet.getErrorMessage().getId()).isEqualTo(AppearanceService.APPEARANCE_NOT_CARD_UNLOCKABLE);

        // 耗 1 张 3411 解锁并穿戴 #1 + 同键重放不二次扣券 + 换参拒绝
        EquipPetCosmeticRequest petUnlockReq = EquipPetCosmeticRequest.newBuilder()
                .setClientRequestId(9102L).setPetId(71001L).setCosmeticId(1).build();
        EquipPetCosmeticResponse petUnlocked = EquipPetCosmeticResponse.parseFrom(
                call("EquipPetCosmetic", petUnlockReq).getSerializedMessage());
        assertThat(petUnlocked.getErrorMessage().getId()).isZero();
        assertThat(petUnlocked.getReplayed()).isFalse();
        assertThat(petUnlocked.getInfo().getPetCosmetics(0).getOwned()).isTrue();
        assertThat(petUnlocked.getInfo().getPetCosmetics(0).getOwnedCardCount()).isEqualTo(1);
        assertThat(player.bags().bag(com.game.scene.player.BagType.INVENTORY).total(3411)).isEqualTo(1L);

        EquipPetCosmeticResponse petReplay = EquipPetCosmeticResponse.parseFrom(
                call("EquipPetCosmetic", petUnlockReq).getSerializedMessage());
        assertThat(petReplay.getErrorMessage().getId()).isZero();
        assertThat(petReplay.getReplayed()).isTrue();
        assertThat(player.bags().bag(com.game.scene.player.BagType.INVENTORY).total(3411)).isEqualTo(1L);

        EquipPetCosmeticResponse petMismatch = EquipPetCosmeticResponse.parseFrom(
                call("EquipPetCosmetic", EquipPetCosmeticRequest.newBuilder()
                        .setClientRequestId(9102L).setPetId(71001L).setCosmeticId(2).build())
                        .getSerializedMessage());
        assertThat(petMismatch.getErrorMessage().getId()).isEqualTo(AppearanceService.INVALID_PARAMETER);

        // 专属特效 #3 拒绝耗券；耗 1 张 3412 解锁并启用 #1 + 同键重放幂等
        EquipSkillVfxResponse exclusiveVfx = EquipSkillVfxResponse.parseFrom(
                call("EquipSkillVfx", EquipSkillVfxRequest.newBuilder()
                        .setClientRequestId(9201L).setSkillTableId(1203).setCosmeticId(3).build())
                        .getSerializedMessage());
        assertThat(exclusiveVfx.getErrorMessage().getId()).isEqualTo(AppearanceService.APPEARANCE_NOT_CARD_UNLOCKABLE);

        EquipSkillVfxRequest vfxUnlockReq = EquipSkillVfxRequest.newBuilder()
                .setClientRequestId(9202L).setSkillTableId(1201).setCosmeticId(1).build();
        EquipSkillVfxResponse vfxUnlocked = EquipSkillVfxResponse.parseFrom(
                call("EquipSkillVfx", vfxUnlockReq).getSerializedMessage());
        assertThat(vfxUnlocked.getErrorMessage().getId()).isZero();
        assertThat(vfxUnlocked.getReplayed()).isFalse();
        assertThat(vfxUnlocked.getInfo().getSkillVfxCosmetics(0).getOwned()).isTrue();
        assertThat(vfxUnlocked.getInfo().getSkillVfxCosmetics(0).getOwnedCardCount()).isEqualTo(1);
        assertThat(player.bags().bag(com.game.scene.player.BagType.INVENTORY).total(3412)).isEqualTo(1L);

        EquipSkillVfxResponse vfxReplay = EquipSkillVfxResponse.parseFrom(
                call("EquipSkillVfx", vfxUnlockReq).getSerializedMessage());
        assertThat(vfxReplay.getErrorMessage().getId()).isZero();
        assertThat(vfxReplay.getReplayed()).isTrue();
        assertThat(player.bags().bag(com.game.scene.player.BagType.INVENTORY).total(3412)).isEqualTo(1L);
    }

    @Test
    void 季享卡直赠与每周衣柜试穿_Steam式系列85折补齐包与严禁绑石() throws Exception {
        com.game.scene.battle.BattleFixture f = new com.game.scene.battle.BattleFixture();
        ScenePlayer player = f.enter(11, 1088);
        assertThat(f.currency.add(player, com.game.scene.player.Wallet.BOUND_DIAMOND, 999L,
                com.game.scene.audit.AssetAudit.Reason.GM_GRANT).ok()).isTrue();
        assertThat(f.currency.add(player, com.game.scene.player.Wallet.DIAMOND, 1000L,
                com.game.scene.audit.AssetAudit.Reason.GM_GRANT).ok()).isTrue();

        int getAfkId = Contracts.REGISTRY.requireId(com.game.scene.afkpass.AfkPassFeature.SERVICE, "GetAfkPass");
        int buyAfkId = Contracts.REGISTRY.requireId(com.game.scene.afkpass.AfkPassFeature.SERVICE, "BuyAfkPass");
        long r1 = f.request(player, getAfkId, com.game.proto.GetAfkPassRequest.getDefaultInstance());
        var afkBefore = com.game.proto.GetAfkPassResponse.parseFrom(
                f.pushes(player, getAfkId).stream().filter(m -> m.getId() == r1).findFirst().orElseThrow().getSerializedMessage());
        assertThat(afkBefore.getInfo().getPlansCount()).isEqualTo(2);
        assertThat(afkBefore.getInfo().getMarketListingLimit()).isEqualTo(10);
        assertThat(afkBefore.getInfo().getStallDurationHours()).isEqualTo(48);

        // 买 90 天季享卡 (plan_id=2, 780 真实灵石): 直赠 3 张绑定 3405 + frame_lightcard_season_s1
        long r2 = f.request(player, buyAfkId, com.game.proto.BuyAfkPassRequest.newBuilder()
                .setClientRequestId(9301L).setPlanId(2).setExpectedCurrencyType(com.game.scene.player.Wallet.DIAMOND)
                .setExpectedPrice(780L).build());
        var afkBought = com.game.proto.BuyAfkPassResponse.parseFrom(
                f.pushes(player, buyAfkId).stream().filter(m -> m.getId() == r2).findFirst().orElseThrow().getSerializedMessage());
        assertThat(afkBought.getErrorMessage().getId()).isZero();
        assertThat(afkBought.getInfo().getMarketListingLimit()).isEqualTo(20);
        assertThat(afkBought.getInfo().getStallDurationHours()).isEqualTo(96);
        assertThat(afkBought.getInfo().getWeeklyFreeTrialQuota()).isEqualTo(3);
        assertThat(player.wallet().balance(com.game.scene.player.Wallet.DIAMOND)).isEqualTo(220L);
        assertThat(player.bags().bag(com.game.scene.player.BagType.INVENTORY).total(3405)).isEqualTo(3L);
        assertThat(player.travelPass().hasCosmeticKey("frame_lightcard_season_s1")).isTrue();

        // 月卡生效期间：同一角色试用完一次取消后，可用每周 3 次月卡随心试穿再次试穿
        int trialId = Contracts.REGISTRY.requireId(SERVICE, "StartAppearanceTrial");
        int cancelId = Contracts.REGISTRY.requireId(SERVICE, "CancelAppearanceTrial");
        long t1 = f.request(player, trialId, StartAppearanceTrialRequest.newBuilder()
                .setClientRequestId(9311L).setAppearanceId("01_ice_sword_girl").build());
        assertThat(StartAppearanceTrialResponse.parseFrom(
                f.pushes(player, trialId).stream().filter(m -> m.getId() == t1).findFirst().orElseThrow().getSerializedMessage())
                .getErrorMessage().getId()).isZero();
        f.request(player, cancelId, CancelAppearanceTrialRequest.getDefaultInstance());
        long t2 = f.request(player, trialId, StartAppearanceTrialRequest.newBuilder()
                .setClientRequestId(9312L).setAppearanceId("01_ice_sword_girl").build());
        var secondTrial = StartAppearanceTrialResponse.parseFrom(
                f.pushes(player, trialId).stream().filter(m -> m.getId() == t2).findFirst().orElseThrow().getSerializedMessage());
        assertThat(secondTrial.getErrorMessage().getId()).isZero();
        assertThat(secondTrial.getInfo().getLightCardTrialUsed()).isEqualTo(1);
        assertThat(secondTrial.getInfo().getLightCardTrialQuota()).isEqualTo(3);

        // Steam 式系列补齐包 (series_id=1，先查出权威缺口与 8.5 折价)
        int infoId = Contracts.REGISTRY.requireId(SERVICE, "GetAppearanceInfo");
        long i1 = f.request(player, infoId, GetAppearanceInfoRequest.getDefaultInstance());
        var appInfo = GetAppearanceInfoResponse.parseFrom(
                f.pushes(player, infoId).stream().filter(m -> m.getId() == i1).findFirst().orElseThrow().getSerializedMessage());
        var s1 = appInfo.getInfo().getSeriesProgressList().stream()
                .filter(sp -> sp.getSeriesId() == 1).findFirst().orElseThrow();
        assertThat(s1.getMissingUnlockableCount()).isEqualTo(2);
        assertThat(s1.getBundleDiscountDiamond()).isEqualTo(170L);

        int bundleId = Contracts.REGISTRY.requireId(SERVICE, "BuyAppearanceSeriesBundle");
        var bundleReq = com.game.proto.BuyAppearanceSeriesBundleRequest.newBuilder()
                .setClientRequestId(9321L)
                .setSeriesId(1)
                .addAllExpectedMissingAppearanceIds(s1.getMissingAppearanceIdsList())
                .setExpectedPriceDiamond(170L)
                .build();
        long b1 = f.request(player, bundleId, bundleReq);
        var bundleResp = com.game.proto.BuyAppearanceSeriesBundleResponse.parseFrom(
                f.pushes(player, bundleId).stream().filter(m -> m.getId() == b1).findFirst().orElseThrow().getSerializedMessage());
        assertThat(bundleResp.getErrorMessage().getId()).isZero();
        assertThat(bundleResp.getReplayed()).isFalse();
        assertThat(bundleResp.getUnlockedCount()).isEqualTo(2);
        assertThat(bundleResp.getSpentDiamond()).isEqualTo(170L);
        assertThat(player.wallet().balance(com.game.scene.player.Wallet.DIAMOND)).isEqualTo(50L);
        assertThat(player.wallet().balance(com.game.scene.player.Wallet.BOUND_DIAMOND)).isEqualTo(999L);

        // 同键重放幂等
        long b2 = f.request(player, bundleId, bundleReq);
        var bundleReplay = com.game.proto.BuyAppearanceSeriesBundleResponse.parseFrom(
                f.pushes(player, bundleId).stream().filter(m -> m.getId() == b2).findFirst().orElseThrow().getSerializedMessage());
        assertThat(bundleReplay.getErrorMessage().getId()).isZero();
        assertThat(bundleReplay.getReplayed()).isTrue();
        assertThat(player.wallet().balance(com.game.scene.player.Wallet.DIAMOND)).isEqualTo(50L);
    }
}
