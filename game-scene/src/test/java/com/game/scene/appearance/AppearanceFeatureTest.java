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
        BagService bagService = new BagService(bagTables, count -> {
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
}
