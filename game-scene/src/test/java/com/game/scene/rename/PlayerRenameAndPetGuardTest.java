package com.game.scene.rename;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.api.proto.PlayerEnter;
import com.game.common.RunMode;
import com.game.player.store.PlayerStore.RenameOutcome;
import com.game.player.store.PlayerStore.RenameStatus;
import com.game.proto.MessageContent;
import com.game.proto.PlayerRenamedNotify;
import com.game.proto.RenamePetRequest;
import com.game.proto.RenamePetResponse;
import com.game.proto.RenamePlayerRequest;
import com.game.proto.RenamePlayerResponse;
import com.game.proto.content_guard.CheckTextResponse;
import com.game.proto.content_guard.TextScene;
import com.game.proto.content_guard.TextVerdict;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.bag.BagService;
import com.game.scene.bag.BagTables;
import com.game.scene.currency.CurrencyService;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.pet.PetFeature;
import com.game.scene.pet.PetService;
import com.game.scene.pet.PetTables;
import com.game.scene.player.BagType;
import com.game.scene.player.ItemGuids;
import com.game.scene.player.SceneTextGuard;
import com.game.scene.player.Wallet;
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
import com.game.table.CommonErrorTip;
import com.game.table.ConfigTables;
import com.game.table.LoginErrorTip;
import com.game.table.PetErrorTip;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 角色改名（428/429）与宝宝改名（189）接入内容安全回归。 */
class PlayerRenameAndPetGuardTest {

    private static final long LINK = 1;
    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final long EPOCH = 5;

    private static ConfigTables config;

    private final RecordingSink sink = new RecordingSink();
    private final FakePlayerRepository repo = new FakePlayerRepository();
    private final ManualClock clock = new ManualClock();
    private final RecordingAssetAudit audit = new RecordingAssetAudit();
    private final AtomicLong guidSeq = new AtomicLong(1L << 60);
    private final AtomicReference<SceneTextGuard> guardRef = new AtomicReference<>();

    private BagService bags;
    private CurrencyService currency;
    private PetService petService;
    private PlayerRenameTables renameTables;
    private SceneWorld world;
    private ClientRequestHandler handler;
    private ScenePlayer player;
    private long reqSeq;

    @BeforeAll
    static void loadConfig() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
        config = ConfigTables.load(dir);
    }

    @BeforeEach
    void setUp() {
        clock.advanceMillis(1_700_000_000_000L);
        repo.setRenameClock(clock::epochMillis);
        SceneMetrics metrics = new SceneMetrics(new SimpleMeterRegistry());
        ItemGuids guids = count -> {
            long[] out = new long[count];
            for (int i = 0; i < count; i++) out[i] = guidSeq.incrementAndGet();
            return out;
        };
        currency = new CurrencyService(audit, GainAnomalyDetector.off(), metrics, clock);
        bags = new BagService(BagTables.from(config), guids, audit, GainAnomalyDetector.off(), metrics);
        petService = new PetService(PetTables.from(config), currency, guids, clock, new SplittableRandom(7));
        renameTables = PlayerRenameTables.from(config);

        SceneTextGuard delegatingGuard = (scene, pid, text) -> guardRef.get().check(scene, pid, text);
        PetFeature petFeature = new PetFeature(petService, Contracts.REGISTRY, delegatingGuard, Runnable::run, Runnable::run);
        PlayerRenameService renameService = new PlayerRenameService(renameTables, bags, clock, Contracts.REGISTRY,
                delegatingGuard, Runnable::run, Runnable::run);

        world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo, new AtomicLong(5000)::incrementAndGet,
                clock, metrics, p -> {
            bags.initializeOnLoad(p);
            petService.initializeOnLoad(p);
        }, PlayerSnapshots.NONE);
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV, List.of(
                petFeature, new PlayerRenameFeature(renameService)));
        Scene scene = world.createScene(1);
        repo.put(new PlayerData(PLAYER, EPOCH, 1, 1, "", 30, 0, Vec3.ORIGIN, null, "旧名剑侠", 0L));
        world.onPlayerEnter(LINK, PlayerEnter.newBuilder()
                .setSessionId(SESSION).setPlayerId(PLAYER).setSceneId(scene.sceneId()).setOwnerEpoch(EPOCH).build());
        repo.completeAll();
        player = world.playerById(PLAYER);
        assertThat(player).isNotNull();
        sink.clear();
    }

    @Test
    void 角色改名_缺卡同名非法直接拒绝_内容安全拒绝与不可用不扣卡_通过后扣卡并推429进入冷却() throws Exception {
        int renameId = Contracts.REGISTRY.requireId(PlayerRenameFeature.SERVICE, "RenamePlayer");
        int notifyId = Contracts.REGISTRY.requireId(PlayerRenameFeature.SERVICE, "NotifyPlayerRenamed");
        PlayerRenameTables.Rule rule = renameTables.rule();

        // 1. 非法名字 -> 2032，同名 -> 2055，缺改名卡 -> 2052
        assertThat(sendRename(renameId, "x").getErrorMessage().getId())
                .isEqualTo(LoginErrorTip.login_error.kRoleNameInvalid_VALUE);
        assertThat(sendRename(renameId, "旧名剑侠").getErrorMessage().getId())
                .isEqualTo(LoginErrorTip.login_error.kRoleRenameSameName_VALUE);
        assertThat(sendRename(renameId, "青莲剑仙").getErrorMessage().getId())
                .isEqualTo(LoginErrorTip.login_error.kRoleRenameNoCard_VALUE);

        // 发放改名卡
        assertThat(bags.addItems(player, BagType.INVENTORY,
                Map.of(rule.renameItemId(), rule.renameItemCount()), Reason.GM_GRANT, 0, "").tip()).isZero();

        // 2. 内容安全 REJECT / MASKED -> 428 受理，429 推 2034，不扣卡
        guardRef.set((scene, pid, text) -> {
            assertThat(scene).isEqualTo(TextScene.TEXT_SCENE_ROLE_NAME);
            return CheckTextResponse.newBuilder().setVerdict(TextVerdict.TEXT_VERDICT_REJECT).build();
        });
        assertThat(sendRename(renameId, "违规名").getErrorMessage().getId()).isZero();
        PlayerRenamedNotify sensNotify = lastNotify(notifyId);
        assertThat(sensNotify.getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kRoleNameSensitive_VALUE);
        assertThat(player.bags().bag(BagType.INVENTORY).total(rule.renameItemId())).isEqualTo(rule.renameItemCount());

        // 3. 内容安全不可用（null）-> 429 推 2050，不扣卡
        guardRef.set((scene, pid, text) -> null);
        assertThat(sendRename(renameId, "青莲剑仙").getErrorMessage().getId()).isZero();
        assertThat(lastNotify(notifyId).getErrorMessage().getId())
                .isEqualTo(LoginErrorTip.login_error.kRoleNameCheckUnavailable_VALUE);
        assertThat(player.bags().bag(BagType.INVENTORY).total(rule.renameItemId())).isEqualTo(rule.renameItemCount());

        // 4. 内容安全通过但存储报重名 -> 429 推 2033，不扣卡
        guardRef.set((scene, pid, text) -> CheckTextResponse.newBuilder().setVerdict(TextVerdict.TEXT_VERDICT_PASS).build());
        repo.setNextRenameOutcome(new RenameOutcome(RenameStatus.NAME_TAKEN, "", 0, 0));
        assertThat(sendRename(renameId, "青莲剑仙").getErrorMessage().getId()).isZero();
        assertThat(lastNotify(notifyId).getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kRoleNameTaken_VALUE);
        assertThat(player.bags().bag(BagType.INVENTORY).total(rule.renameItemId())).isEqualTo(rule.renameItemCount());

        // 5. 内容安全通过且存储成功 -> 扣卡、更新 player.name()、推 429 带新名与下次可改时间
        long t = clock.epochMillis();
        assertThat(sendRename(renameId, "青莲剑仙").getErrorMessage().getId()).isZero();
        PlayerRenamedNotify okNotify = lastNotify(notifyId);
        assertThat(okNotify.getErrorMessage().getId()).isZero();
        assertThat(okNotify.getName()).isEqualTo("青莲剑仙");
        assertThat(okNotify.getNextRenameTime()).isEqualTo((t + rule.cooldownMs()) / 1_000L);
        assertThat(player.name()).isEqualTo("青莲剑仙");
        assertThat(player.bags().bag(BagType.INVENTORY).total(rule.renameItemId())).isZero();

        // 6. 再补卡，冷却期内再次改名立即被拒 2051
        assertThat(bags.addItems(player, BagType.INVENTORY,
                Map.of(rule.renameItemId(), rule.renameItemCount()), Reason.GM_GRANT, 0, "").tip()).isZero();
        assertThat(sendRename(renameId, "第三名字").getErrorMessage().getId())
                .isEqualTo(LoginErrorTip.login_error.kRoleRenameCooldown_VALUE);
    }

    @Test
    void 宝宝改名接入内容安全_敏感回26017不可用回1003均不扣金币_通过后扣金币改名() throws Exception {
        assertThat(currency.add(player, Wallet.GOLD, 1_000_000L, Reason.GM_GRANT).ok()).isTrue();
        long petId = petService.grant(player, 1).petId();
        assertThat(petId).isNotZero();
        long goldBefore = player.wallet().balance(Wallet.GOLD);
        int renamePetId = Contracts.REGISTRY.requireId("ScenePetClientPlayer", "RenamePet");

        // 1. REJECT -> 26017，不扣金币
        guardRef.set((scene, pid, text) -> {
            assertThat(scene).isEqualTo(TextScene.TEXT_SCENE_PET_NAME);
            return CheckTextResponse.newBuilder().setVerdict(TextVerdict.TEXT_VERDICT_REJECT).build();
        });
        RenamePetResponse sens = sendRenamePet(renamePetId, petId, "敏感宠名");
        assertThat(sens.getErrorMessage().getId()).isEqualTo(PetErrorTip.pet_error.kPetNameSensitive_VALUE);
        assertThat(player.wallet().balance(Wallet.GOLD)).isEqualTo(goldBefore);

        // 2. 不可用（抛异常）-> 1003，不扣金币
        guardRef.set((scene, pid, text) -> { throw new RuntimeException("down"); });
        RenamePetResponse down = sendRenamePet(renamePetId, petId, "小黑");
        assertThat(down.getErrorMessage().getId()).isEqualTo(CommonErrorTip.common_error.kServiceUnavailable_VALUE);
        assertThat(player.wallet().balance(Wallet.GOLD)).isEqualTo(goldBefore);

        // 3. PASS -> 成功并更新宠物名
        guardRef.set((scene, pid, text) -> CheckTextResponse.newBuilder().setVerdict(TextVerdict.TEXT_VERDICT_PASS).build());
        RenamePetResponse ok = sendRenamePet(renamePetId, petId, "小青龙");
        assertThat(ok.getErrorMessage().getId()).isZero();
        assertThat(player.pets().find(petId).name()).isEqualTo("小青龙");
    }

    private RenamePlayerResponse sendRename(int msgId, String name) throws Exception {
        sink.clear();
        handler.onClientForward(LINK, ClientForward.newBuilder()
                .setSessionId(SESSION).setPlayerId(PLAYER).setMessageId(msgId).setRequestId(++reqSeq)
                .setBody(RenamePlayerRequest.newBuilder().setName(name).build().toByteString()).build());
        MessageContent reply = sink.to(LINK, SESSION).stream()
                .filter(m -> m.getMessageId() == msgId).findFirst().orElseThrow();
        return RenamePlayerResponse.parseFrom(reply.getSerializedMessage());
    }

    private PlayerRenamedNotify lastNotify(int notifyId) throws Exception {
        MessageContent push = sink.to(LINK, SESSION).stream()
                .filter(m -> m.getMessageId() == notifyId).reduce((a, b) -> b).orElseThrow();
        return PlayerRenamedNotify.parseFrom(push.getSerializedMessage());
    }

    private RenamePetResponse sendRenamePet(int msgId, long petId, String name) throws Exception {
        sink.clear();
        handler.onClientForward(LINK, ClientForward.newBuilder()
                .setSessionId(SESSION).setPlayerId(PLAYER).setMessageId(msgId).setRequestId(++reqSeq)
                .setBody(RenamePetRequest.newBuilder().setPetId(petId).setName(name).build().toByteString()).build());
        MessageContent reply = sink.to(LINK, SESSION).stream()
                .filter(m -> m.getMessageId() == msgId).findFirst().orElseThrow();
        return RenamePetResponse.parseFrom(reply.getSerializedMessage());
    }
}
