package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.asset.AssetOpSignatures;
import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.api.proto.AssetStream;
import com.game.api.proto.ClientForward;
import com.game.api.proto.PlayerEnter;
import com.game.common.RunMode;
import com.game.contract.MessageMethod;
import com.game.proto.AllocateAttributePointsRequest;
import com.game.proto.AllocatePetPointsRequest;
import com.game.proto.AttributePanelChangedS2C;
import com.game.proto.AutoAllocateAttributePointsRequest;
import com.game.proto.AutoAllocatePetPointsRequest;
import com.game.proto.CreateAttributeSchemeRequest;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.GetActivityListRequest;
import com.game.proto.GetAttributePanelRequest;
import com.game.proto.GetBagRequest;
import com.game.proto.GetCurrencyListRequest;
import com.game.proto.GetCurrencyListResponse;
import com.game.proto.GetMissionListRequest;
import com.game.proto.GetPetListRequest;
import com.game.proto.GmAddCurrencyRequest;
import com.game.proto.GmBlockCurrencyRequest;
import com.game.proto.GmDeductCurrencyRequest;
import com.game.proto.GmGrantPetRequest;
import com.game.proto.GmGrantPetResponse;
import com.game.proto.GmSetPlayerLevelRequest;
import com.game.proto.GmUnblockCurrencyRequest;
import com.game.proto.ListSkillsRequest;
import com.game.proto.MessageContent;
import com.game.proto.MissionActionRequest;
import com.game.proto.MoveStartC2S;
import com.game.proto.MoveStopC2S;
import com.game.proto.MoveSyncC2S;
import com.game.proto.RecallPetRequest;
import com.game.proto.ReleaseSkillRequest;
import com.game.proto.RenameAttributeSchemeRequest;
import com.game.proto.RenamePetRequest;
import com.game.proto.ResetAttributePointsRequest;
import com.game.proto.ResetPetPointsRequest;
import com.game.proto.SceneInfoComp;
import com.game.proto.SceneInfoRequest;
import com.game.proto.SortBagRequest;
import com.game.proto.SummonPetRequest;
import com.game.proto.SwitchAttributeSchemeRequest;
import com.game.proto.TipInfoMessage;
import com.game.proto.Vector3;
import com.game.scene.asset.AssetOpAuth;
import com.game.scene.asset.AssetOpService;
import com.game.scene.attribute.AttributeFeature;
import com.game.scene.attribute.AttributeService;
import com.game.scene.attribute.AttributeTables;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.bag.BagFeature;
import com.game.scene.bag.BagService;
import com.game.scene.bag.BagTables;
import com.game.scene.currency.CurrencyFeature;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.mission.ActivityFeature;
import com.game.scene.mission.MissionFeature;
import com.game.scene.mission.MissionService;
import com.game.scene.mission.MissionTables;
import com.game.scene.pet.PetFeature;
import com.game.scene.pet.PetService;
import com.game.scene.pet.PetTables;
import com.game.scene.player.BagType;
import com.game.scene.player.ItemGuids;
import com.game.scene.skill.SkillFeature;
import com.game.scene.skill.SkillService;
import com.game.scene.skill.SkillTables;
import com.game.scene.team.TeamFollow;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakePlayerRepository.PendingHandOff;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.FakeSwitchTargets;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingAssetAudit;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.PlayerRepository.HandOffOutcome;
import com.game.table.ConfigTables;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 冻结闸（批次 5.2，scene-handoff-spec §5.9、§10.3「冻结闸」）：全功能装配（同 SceneNode）下逐方法核对冻结策略表；
 * 冻结中 READ_ONLY 照常、GATED 回基线码、REJECT 回应答内 1005、DROP 不回包、84 照常，资产通道 RETRY 27003；
 * 冻结中把全部入口打一遍之后交出，冻结快照与内存一致（{@code post_freeze_mutations} 为 0）；选目标中不冻结、一律照常。
 */
class FreezeGateTest {

    private static final long LINK = 1;
    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final long E = 5;
    private static final int LOCAL_NODE = 3;
    private static final int TARGET_NODE = 4;
    private static final long REMOTE_SCENE = 900_001;
    private static final String SECRET = "freeze-gate-test-secret-0123456789ab";
    private static final int INVALID_PARAMETER = 1005;
    private static final int ASSET_FROZEN = 27003;
    private static final int CHANGING_SCENE = 3014;

    /** 冻结中的预期：什么都不发。 */
    private static final int DROPPED = -1;
    /** 冻结中的预期：不回应答、只推 31。 */
    private static final int PUSH_ONLY = -2;
    /** 冻结中的预期：进了处理器（应答码由处理器自己定，不是冻结闸的 1005）。 */
    private static final int PASSED = -3;

    private static ConfigTables config;

    /** 一次探测：方法、请求、冻结中的预期（tip 码或上面三个特殊值）。 */
    private record Probe(String service, String method, Message request, int frozen) {

        String key() {
            return service + method;
        }
    }

    private final RecordingSink sink = new RecordingSink();
    private final FakePlayerRepository repo = new FakePlayerRepository();
    private final FakeSwitchTargets targets = new FakeSwitchTargets();
    private final ManualClock clock = new ManualClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RecordingAssetAudit audit = new RecordingAssetAudit();
    private final AtomicLong guidSeq = new AtomicLong(1L << 60);
    private SceneWorld world;
    private ClientRequestHandler handler;
    private AssetOpService assetOps;
    private CurrencyService currency;
    private ScenePlayer player;
    private long requestIds;

    @BeforeAll
    static void loadTables() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
        config = ConfigTables.load(dir);
    }

    /** 同 SceneNode 的装配：全部玩法功能、开了跨节点换图（假选目标）、DEV 运行模式（GM 方法放行）。玩家 30 级、1000 银两。 */
    @BeforeEach
    void start() {
        SceneMetrics metrics = new SceneMetrics(meters);
        ItemGuids guids = count -> {
            long[] out = new long[count];
            for (int i = 0; i < count; i++) {
                out[i] = guidSeq.incrementAndGet();
            }
            return out;
        };
        currency = new CurrencyService(audit, GainAnomalyDetector.off(), metrics, clock);
        BagService bags = new BagService(BagTables.from(config), guids, audit, GainAnomalyDetector.off(), metrics);
        AttributeService attributes = new AttributeService(AttributeTables.from(config), clock, currency);
        MissionService missions = new MissionService(MissionTables.from(config), bags, clock);
        SkillService skills = new SkillService(SkillTables.from(config), clock, metrics, Contracts.IDS);
        PetService petService = new PetService(PetTables.from(config), currency, guids, clock, new SplittableRandom(7));
        PetFeature pets = new PetFeature(petService, Contracts.REGISTRY);
        world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo, new AtomicLong(5000)::incrementAndGet,
                clock, metrics, p -> {
                    bags.initializeOnLoad(p);
                    attributes.initializeOnLoad(p);
                    petService.initializeOnLoad(p);
                    missions.initializeOnLoad(p);
                    AssetOpService.checkLedgerOnLoad(p);
                }, PlayerSnapshots.NONE, PlayerLocations.NONE, TeamFollow.NONE,
                new CrossNodeSwitch(LOCAL_NODE, targets, owned -> { }, Duration.ofSeconds(4), Duration.ofSeconds(30)));
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV, List.of(
                new CurrencyFeature(currency),
                new AttributeFeature(attributes, Contracts.REGISTRY, call -> {
                    pets.onOwnerLevelChanged(call);
                    missions.onLevelChanged(call.player());
                }),
                new BagFeature(bags), new MissionFeature(missions), new ActivityFeature(missions),
                new SkillFeature(skills), pets));
        assetOps = new AssetOpService(world, currency, bags, new AssetOpAuth(caller -> SECRET), clock, metrics);
        Scene scene = world.createScene(1);
        world.createScene(2);
        repo.put(new PlayerData(PLAYER, E, 1, 1, "", 30, 0, Vec3.ORIGIN, null));
        world.onPlayerEnter(LINK, PlayerEnter.newBuilder()
                .setSessionId(SESSION).setPlayerId(PLAYER).setSceneId(scene.sceneId()).setOwnerEpoch(E).build());
        repo.completeAll();
        player = world.playerById(PLAYER);
        assertThat(player).isNotNull();
        assertThat(currency.add(player, 0, 1000, Reason.GM_GRANT).ok()).isTrue();
        sink.clear();
    }

    // ------------------------------------------------------------------ 策略表与注册规则

    @Test
    void 冻结策略表_全功能装配下逐方法与规格一致() {
        Map<String, FreezePolicy> expected = new TreeMap<>();
        expected.put("SceneSceneClientPlayerEnterScene", FreezePolicy.ALLOW);
        expected.put("SceneSceneClientPlayerSceneInfoC2S", FreezePolicy.READ_ONLY);
        expected.put("SceneMovementClientPlayerMoveStart", FreezePolicy.DROP);
        expected.put("SceneMovementClientPlayerMoveSync", FreezePolicy.DROP);
        expected.put("SceneMovementClientPlayerMoveStop", FreezePolicy.DROP);
        expected.put("SceneSkillClientPlayerListSkills", FreezePolicy.READ_ONLY);
        expected.put("SceneSkillClientPlayerReleaseSkill", FreezePolicy.ALLOW);
        expected.put("SceneCurrencyClientPlayerGetCurrencyList", FreezePolicy.READ_ONLY);
        expected.put("SceneCurrencyClientPlayerGmAddCurrency", FreezePolicy.GATED);
        expected.put("SceneCurrencyClientPlayerGmDeductCurrency", FreezePolicy.GATED);
        expected.put("SceneCurrencyClientPlayerGmBlockCurrency", FreezePolicy.REJECT);
        expected.put("SceneCurrencyClientPlayerGmUnblockCurrency", FreezePolicy.REJECT);
        expected.put("SceneAttributeClientPlayerGetAttributePanel", FreezePolicy.READ_ONLY);
        for (String write : List.of("AllocateAttributePoints", "ResetAttributePoints", "CreateAttributeScheme",
                "SwitchAttributeScheme", "RenameAttributeScheme", "GmSetPlayerLevel")) {
            expected.put("SceneAttributeClientPlayer" + write, FreezePolicy.GATED);
        }
        expected.put("SceneAttributeClientPlayerAutoAllocateAttributePoints", FreezePolicy.REJECT);
        expected.put("SceneBagClientPlayerGetBag", FreezePolicy.READ_ONLY);
        expected.put("SceneBagClientPlayerSortBag", FreezePolicy.GATED);
        expected.put("SceneMissionClientPlayerGetMissionList", FreezePolicy.READ_ONLY);
        expected.put("SceneMissionClientPlayerAcceptMission", FreezePolicy.GATED);
        expected.put("SceneMissionClientPlayerClaimMissionReward", FreezePolicy.GATED);
        expected.put("SceneActivityClientPlayerGetActivityList", FreezePolicy.READ_ONLY);
        expected.put("ScenePetClientPlayerGetPetList", FreezePolicy.READ_ONLY);
        for (String write : List.of("SummonPet", "RecallPet", "AllocatePetPoints", "ResetPetPoints",
                "AutoAllocatePetPoints", "RenamePet", "GmGrantPet")) {
            expected.put("ScenePetClientPlayer" + write, FreezePolicy.GATED);
        }

        assertThat(registeredPolicies()).containsExactlyInAnyOrderEntriesOf(expected);
        // 规格 §5.9 按消息号列：抽查几处号与名对得上
        assertThat(handler.freezePolicy(63)).isEqualTo(FreezePolicy.ALLOW);
        assertThat(handler.freezePolicy(84)).isEqualTo(FreezePolicy.ALLOW);
        assertThat(handler.freezePolicy(173)).isEqualTo(FreezePolicy.REJECT);
        assertThat(handler.freezePolicy(94)).isEqualTo(FreezePolicy.REJECT);
        assertThat(handler.freezePolicy(95)).isEqualTo(FreezePolicy.REJECT);
        assertThat(List.of(134, 132, 131)).allSatisfy(id -> assertThat(handler.freezePolicy(id)).isEqualTo(FreezePolicy.DROP));
        assertThat(handler.freezePolicy(136)).as("没有处理器的方法没有策略（冻结与否都回 1006）").isNull();
    }

    @Test
    void 玩法功能声明DROP_启动即失败() {
        SceneFeature dropper = r -> r.on("SceneCurrencyClientPlayer", "GetCurrencyList", GetCurrencyListRequest.class,
                FreezePolicy.DROP, (call, req) -> { });

        assertThatThrownBy(() -> new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV,
                List.of(dropper))).isInstanceOf(IllegalStateException.class).hasMessageContaining("DROP");
    }

    @Test
    void 没声明策略的方法缺省REJECT_冻结中回应答内1005不进处理器_无应答的静默丢_解冻后照常() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SceneFeature undeclared = r -> {
            r.on("SceneCurrencyClientPlayer", "GetCurrencyList", GetCurrencyListRequest.class, (call, req) -> {
                calls.incrementAndGet();
                call.reply(GetCurrencyListResponse.newBuilder().setErrorMessage(SceneMessageIds.tip(0)).build());
            });
            r.on("SceneAttributeClientPlayer", "NotifyAttributePanelChanged", AttributePanelChangedS2C.class,
                    (call, req) -> calls.incrementAndGet());
        };
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV, List.of(undeclared));
        int getCurrencyList = Contracts.REGISTRY.requireId("SceneCurrencyClientPlayer", "GetCurrencyList");
        int notify = Contracts.REGISTRY.requireId("SceneAttributeClientPlayer", "NotifyAttributePanelChanged");
        assertThat(handler.freezePolicy(getCurrencyList)).isEqualTo(FreezePolicy.REJECT);
        WorldTestAccess.startFreezing(player);

        forward(getCurrencyList, GetCurrencyListRequest.getDefaultInstance(), 41);
        forward(notify, AttributePanelChangedS2C.getDefaultInstance(), 42);

        assertThat(calls).hasValue(0);
        assertThat(sink.to(LINK, SESSION)).singleElement().satisfies(reply -> {
            assertThat(reply.getMessageId()).isEqualTo(getCurrencyList);
            assertThat(reply.getId()).isEqualTo(41L);
            assertThat(reply.hasErrorMessage()).as("拒绝码在应答体里，不在信封").isFalse();
            assertThat(GetCurrencyListResponse.parseFrom(reply.getSerializedMessage()).getErrorMessage().getId())
                    .isEqualTo(INVALID_PARAMETER);
        });
        assertThat(frozenRejections("request")).isEqualTo(2);

        WorldTestAccess.clearSwitch(player);
        forward(getCurrencyList, GetCurrencyListRequest.getDefaultInstance(), 43);
        assertThat(calls).hasValue(1);
        assertThat(tipOf(sink.to(LINK, SESSION).getLast())).isZero();
    }

    // ------------------------------------------------------------------ 冻结中逐方法

    @Test
    void 冻结中逐方法按策略处理_全部入口打一遍后交出_冻结快照与内存一致() throws Exception {
        long petId = grantPet();
        List<Probe> probes = probes(petId);
        assertThat(probes).extracting(Probe::key).as("探测覆盖全部注册的方法（新加方法要在这里补一行）")
                .containsExactlyInAnyOrderElementsOf(registeredPolicies().keySet());
        PendingHandOff handOff = freezeViaEnterScene();
        int itemAudits = audit.items.size();
        int currencyAudits = audit.currencies.size();
        sink.clear();

        for (Probe probe : probes) {
            List<MessageContent> sent = send(probe);
            int messageId = Contracts.REGISTRY.requireId(probe.service(), probe.method());
            switch (probe.frozen()) {
                case DROPPED -> assertThat(sent).as(probe.key()).isEmpty();
                case PUSH_ONLY -> assertThat(sent).as(probe.key()).extracting(MessageContent::getMessageId)
                        .containsExactly(Contracts.IDS.notifySceneInfo());
                case PASSED -> assertThat(sent).as(probe.key()).last().satisfies(reply -> {
                    assertThat(reply.getMessageId()).isEqualTo(messageId);
                    assertThat(tipOf(reply)).isNotEqualTo(INVALID_PARAMETER);
                });
                default -> assertThat(sent).as(probe.key()).singleElement().satisfies(reply -> {
                    assertThat(reply.getMessageId()).isEqualTo(messageId);
                    assertThat(tipOf(reply)).as(probe.key()).isEqualTo(probe.frozen());
                });
            }
        }
        AssetOpResponse asset = assetOps.handle(AssetRpc.DEBIT, signedDebit(1, 30));

        assertThat(asset.getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_RETRY);
        assertThat(asset.getReason()).isEqualTo(ASSET_FROZEN);
        assertThat(player.toSave()).as("冻结中没有任何入口改动可持久化状态").isEqualTo(handOff.frozen());
        assertThat(audit.items).hasSize(itemAudits);
        assertThat(audit.currencies).hasSize(currencyAudits);
        assertThat(frozenRejections("request")).as("173 / 94 / 95").isEqualTo(3);
        assertThat(frozenRejections("move")).isEqualTo(3);
        assertThat(frozenRejections("asset_op")).isEqualTo(1);
        assertThat(meters.get("xm.scene.moves").tag("result", "frozen").counter().count()).isEqualTo(3);

        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        assertThat(meters.get("xm.scene.transfer.post.freeze.mutations").counter().count()).isZero();
        assertThat(sink.transfers()).hasSize(1);
        assertThat(world.playerById(PLAYER)).isNull();
    }

    @Test
    void 选目标中不冻结_同一批入口照常改动_冻结闸一次不计() throws Exception {
        long petId = grantPet();
        PlayerSave before = player.toSave();
        enterScene(REMOTE_SCENE, 1);
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);
        sink.clear();

        // 资产通道先办（后面那一轮会把银两花在洗点 / 改名上）
        assertThat(assetOps.handle(AssetRpc.DEBIT, signedDebit(1, 30)).getOutcome())
                .isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        for (Probe probe : probes(petId)) {
            send(probe);
        }

        assertThat(lastTip("SceneCurrencyClientPlayer", "GmAddCurrency")).isZero();
        assertThat(lastTip("SceneCurrencyClientPlayer", "GmBlockCurrency")).isZero();
        assertThat(lastTip("SceneAttributeClientPlayer", "AutoAllocateAttributePoints")).as("173 选目标中照常").isZero();
        assertThat(lastTip("SceneAttributeClientPlayer", "GmSetPlayerLevel")).isZero();
        assertThat(lastTip("SceneSceneClientPlayer", "EnterScene")).as("在途再发 63").isEqualTo(CHANGING_SCENE);
        assertThat(player.toSave()).isNotEqualTo(before);
        for (String kind : List.of("request", "move", "asset_op")) {
            assertThat(frozenRejections(kind)).as(kind).isZero();
        }
        assertThat(meters.get("xm.scene.moves").tag("result", "frozen").counter().count()).isZero();
    }

    @Test
    void 交出没提交原地解冻_写操作与173恢复照常() throws Exception {
        PendingHandOff handOff = freezeViaEnterScene();
        assertThat(sendTip("SceneCurrencyClientPlayer", "GmAddCurrency",
                GmAddCurrencyRequest.newBuilder().setCurrencyType(0).setAmount(5).build())).isEqualTo(ASSET_FROZEN);
        assertThat(sendTip("SceneAttributeClientPlayer", "AutoAllocateAttributePoints",
                AutoAllocateAttributePointsRequest.newBuilder().setPoolId(1).build())).isEqualTo(INVALID_PARAMETER);

        handOff.complete(new HandOffOutcome.LeaseTooShort());

        assertThat(player.frozen()).isFalse();
        assertThat(sendTip("SceneCurrencyClientPlayer", "GmAddCurrency",
                GmAddCurrencyRequest.newBuilder().setCurrencyType(0).setAmount(5).build())).isZero();
        assertThat(sendTip("SceneAttributeClientPlayer", "AutoAllocateAttributePoints",
                AutoAllocateAttributePointsRequest.newBuilder().setPoolId(1).build())).isZero();
        assertThat(player.wallet().balance(0)).isEqualTo(1005);
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 每个注册方法一条探测，请求尽量是「不冻结就会成」的（冻结中的 1005 / 27003 才说明是闸拒的，不是参数错）。
     * 顺序照「先写后读」排：选目标中的那一轮里写操作的效果在后面的读里可见。
     */
    private List<Probe> probes(long petId) {
        List<Probe> probes = new ArrayList<>();
        String currencySvc = "SceneCurrencyClientPlayer";
        probes.add(new Probe(currencySvc, "GmAddCurrency",
                GmAddCurrencyRequest.newBuilder().setCurrencyType(0).setAmount(10).build(), ASSET_FROZEN));
        probes.add(new Probe(currencySvc, "GmDeductCurrency",
                GmDeductCurrencyRequest.newBuilder().setCurrencyType(0).setAmount(1).build(), ASSET_FROZEN));
        probes.add(new Probe(currencySvc, "GmBlockCurrency",
                GmBlockCurrencyRequest.newBuilder().setCurrencyType(1).build(), INVALID_PARAMETER));
        probes.add(new Probe(currencySvc, "GmUnblockCurrency",
                GmUnblockCurrencyRequest.newBuilder().setCurrencyType(2).build(), INVALID_PARAMETER));
        probes.add(new Probe(currencySvc, "GetCurrencyList", GetCurrencyListRequest.getDefaultInstance(), 0));

        String attributeSvc = "SceneAttributeClientPlayer";
        probes.add(new Probe(attributeSvc, "GmSetPlayerLevel",
                GmSetPlayerLevelRequest.newBuilder().setLevel(31).build(), INVALID_PARAMETER));
        probes.add(new Probe(attributeSvc, "AutoAllocateAttributePoints",
                AutoAllocateAttributePointsRequest.newBuilder().setPoolId(1).build(), INVALID_PARAMETER));
        probes.add(new Probe(attributeSvc, "AllocateAttributePoints",
                AllocateAttributePointsRequest.newBuilder().setPoolId(1).putAllocated(103, 5).build(), INVALID_PARAMETER));
        probes.add(new Probe(attributeSvc, "ResetAttributePoints",
                ResetAttributePointsRequest.newBuilder().setPoolId(1).build(), INVALID_PARAMETER));
        probes.add(new Probe(attributeSvc, "CreateAttributeScheme",
                CreateAttributeSchemeRequest.newBuilder().setName("").build(), INVALID_PARAMETER));
        probes.add(new Probe(attributeSvc, "SwitchAttributeScheme",
                SwitchAttributeSchemeRequest.newBuilder().setSchemeId(1).build(), INVALID_PARAMETER));
        probes.add(new Probe(attributeSvc, "RenameAttributeScheme",
                RenameAttributeSchemeRequest.newBuilder().setSchemeId(1).setName("冻结方案").build(), INVALID_PARAMETER));
        probes.add(new Probe(attributeSvc, "GetAttributePanel", GetAttributePanelRequest.getDefaultInstance(), 0));

        probes.add(new Probe("SceneBagClientPlayer", "SortBag",
                SortBagRequest.newBuilder().setBagType(BagType.INVENTORY.code()).build(), INVALID_PARAMETER));
        probes.add(new Probe("SceneBagClientPlayer", "GetBag",
                GetBagRequest.newBuilder().setBagType(BagType.INVENTORY.code()).build(), 0));

        String missionSvc = "SceneMissionClientPlayer";
        probes.add(new Probe(missionSvc, "AcceptMission",
                MissionActionRequest.newBuilder().setMissionId(12).build(), INVALID_PARAMETER));
        probes.add(new Probe(missionSvc, "ClaimMissionReward",
                MissionActionRequest.newBuilder().setMissionId(12).build(), INVALID_PARAMETER));
        probes.add(new Probe(missionSvc, "GetMissionList", GetMissionListRequest.getDefaultInstance(), 0));
        probes.add(new Probe("SceneActivityClientPlayer", "GetActivityList",
                GetActivityListRequest.getDefaultInstance(), 0));

        String petSvc = "ScenePetClientPlayer";
        probes.add(new Probe(petSvc, "GmGrantPet", GmGrantPetRequest.newBuilder().setPetTableId(1).build(),
                INVALID_PARAMETER));
        probes.add(new Probe(petSvc, "SummonPet", SummonPetRequest.newBuilder().setPetId(petId).build(),
                INVALID_PARAMETER));
        probes.add(new Probe(petSvc, "AllocatePetPoints",
                AllocatePetPointsRequest.newBuilder().setPetId(petId).putAllocated(403, 1).build(), INVALID_PARAMETER));
        probes.add(new Probe(petSvc, "ResetPetPoints", ResetPetPointsRequest.newBuilder().setPetId(petId).build(),
                INVALID_PARAMETER));
        probes.add(new Probe(petSvc, "RenamePet", RenamePetRequest.newBuilder().setPetId(petId).setName("小冻").build(),
                INVALID_PARAMETER));
        probes.add(new Probe(petSvc, "RecallPet", RecallPetRequest.getDefaultInstance(), INVALID_PARAMETER));
        // 188 只算不落、不过写前置（同基线）：冻结中照常回建议
        probes.add(new Probe(petSvc, "AutoAllocatePetPoints",
                AutoAllocatePetPointsRequest.newBuilder().setPetId(petId).build(), PASSED));
        probes.add(new Probe(petSvc, "GetPetList", GetPetListRequest.getDefaultInstance(), 0));

        probes.add(new Probe("SceneSkillClientPlayer", "ReleaseSkill", ReleaseSkillRequest.newBuilder()
                .setSkillTableId(1).setTargetId(player.entity())
                .setPosition(Vector3.newBuilder().setX(1).setY(2).setZ(3)).build(), PASSED));
        probes.add(new Probe("SceneSkillClientPlayer", "ListSkills", ListSkillsRequest.getDefaultInstance(), 0));

        String moveSvc = "SceneMovementClientPlayer";
        probes.add(new Probe(moveSvc, "MoveStart", MoveStartC2S.getDefaultInstance(), DROPPED));
        probes.add(new Probe(moveSvc, "MoveSync", MoveSyncC2S.getDefaultInstance(), DROPPED));
        probes.add(new Probe(moveSvc, "MoveStop", MoveStopC2S.getDefaultInstance(), DROPPED));

        probes.add(new Probe("SceneSceneClientPlayer", "EnterScene", enterSceneRequest(REMOTE_SCENE),
                CHANGING_SCENE));
        probes.add(new Probe("SceneSceneClientPlayer", "SceneInfoC2S", SceneInfoRequest.getDefaultInstance(),
                PUSH_ONLY));
        return probes;
    }

    /** 冻结前发一只宝宝（让宝宝的写操作都有对象），返回宝宝号。 */
    private long grantPet() throws Exception {
        MessageContent reply = send(new Probe("ScenePetClientPlayer", "GmGrantPet",
                GmGrantPetRequest.newBuilder().setPetTableId(1).build(), 0)).getLast();
        GmGrantPetResponse granted = GmGrantPetResponse.parseFrom(reply.getSerializedMessage());
        assertThat(granted.getErrorMessage().getId()).isZero();
        sink.clear();
        return granted.getPetId();
    }

    /** 走真实流程进 FREEZING：63 指定别的节点上的场景 → 选目标回别的节点 → 冻结，返回挂起的交出。 */
    private PendingHandOff freezeViaEnterScene() {
        enterScene(REMOTE_SCENE, 1);
        targets.take().chosen(TARGET_NODE, REMOTE_SCENE, 2);
        assertThat(player.frozen()).isTrue();
        return repo.takeHandOff();
    }

    private void enterScene(long sceneId, long requestId) {
        forward(Contracts.IDS.enterScene(), enterSceneRequest(sceneId), requestId);
    }

    private static EnterSceneC2SRequest enterSceneRequest(long sceneId) {
        return EnterSceneC2SRequest.newBuilder().setSceneInfo(SceneInfoComp.newBuilder().setSceneId(sceneId)).build();
    }

    /** 发一条探测，返回这一条引出的、发给本人的消息。 */
    private List<MessageContent> send(Probe probe) {
        int before = sink.to(LINK, SESSION).size();
        forward(Contracts.REGISTRY.requireId(probe.service(), probe.method()), probe.request(), ++requestIds);
        List<MessageContent> all = sink.to(LINK, SESSION);
        return new ArrayList<>(all.subList(before, all.size()));
    }

    private int sendTip(String service, String method, Message request) throws InvalidProtocolBufferException {
        return tipOf(send(new Probe(service, method, request, 0)).getLast());
    }

    /** 某方法最近一条应答的 tip。 */
    private int lastTip(String service, String method) throws InvalidProtocolBufferException {
        int messageId = Contracts.REGISTRY.requireId(service, method);
        List<MessageContent> replies = sink.to(LINK, SESSION).stream().filter(m -> m.getMessageId() == messageId).toList();
        assertThat(replies).as(service + method).isNotEmpty();
        return tipOf(replies.getLast());
    }

    private void forward(int messageId, Message body, long requestId) {
        handler.onClientForward(LINK, ClientForward.newBuilder()
                .setSessionId(SESSION)
                .setPlayerId(PLAYER)
                .setMessageId(messageId)
                .setBody(body.toByteString())
                .setRequestId(requestId)
                .build());
    }

    /** 应答体里的 {@code error_message}（没有这个字段的应答取信封上的）。 */
    private static int tipOf(MessageContent reply) throws InvalidProtocolBufferException {
        MessageMethod method = Contracts.REGISTRY.byId(reply.getMessageId()).orElseThrow();
        Message response = method.responsePrototype().getParserForType().parseFrom(reply.getSerializedMessage());
        FieldDescriptor field = response.getDescriptorForType().findFieldByName("error_message");
        if (field == null) {
            return reply.getErrorMessage().getId();
        }
        return ((TipInfoMessage) response.getField(field)).getId();
    }

    /** 当前 handler 上注册的全部方法与它们的冻结策略（按「服务裸名 + 方法名」）。 */
    private Map<String, FreezePolicy> registeredPolicies() {
        Map<String, FreezePolicy> out = new TreeMap<>();
        for (MessageMethod method : Contracts.REGISTRY.all()) {
            FreezePolicy policy = handler.freezePolicy(method.messageId());
            if (policy != null) {
                out.put(method.key(), policy);
            }
        }
        return out;
    }

    private AssetOpRequest signedDebit(long seq, long amount) {
        AssetOpRequest request = AssetOpRequest.newBuilder().setPlayerId(PLAYER)
                .setStream(AssetStream.ASSET_STREAM_GUILD_DEBIT).setSeq(seq).setStreamEpoch(1_700_000_000_000L)
                .setTxType(24).setCorrelationId(500 + seq)
                .setBundle(AssetBundle.newBuilder().addCurrencies(AssetCurrency.newBuilder().setCurrencyType(0)
                        .setAmount(amount)))
                .build();
        return AssetOpSignatures.sign(AssetRpc.DEBIT, request, AssetOpSignatures.CALLER_GUILD, SECRET,
                clock.epochMillis());
    }

    private double frozenRejections(String kind) {
        return meters.get("xm.scene.frozen.rejections").tag("kind", kind.toLowerCase(Locale.ROOT)).counter().count();
    }
}
