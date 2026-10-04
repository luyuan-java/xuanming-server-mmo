package com.game.scene.pet;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.common.RunMode;
import com.game.player.store.state.PlayerState;
import com.game.proto.AllocatePetPointsRequest;
import com.game.proto.AllocatePetPointsResponse;
import com.game.proto.GetPetListRequest;
import com.game.proto.GetPetListResponse;
import com.game.proto.GmGrantPetRequest;
import com.game.proto.GmGrantPetResponse;
import com.game.proto.GmSetPlayerLevelRequest;
import com.game.proto.MessageContent;
import com.game.proto.PetListChangedS2C;
import com.game.proto.PetListInfo;
import com.game.proto.SummonPetRequest;
import com.game.proto.SummonPetResponse;
import com.game.scene.attribute.AttributeFeature;
import com.game.scene.attribute.AttributeService;
import com.game.scene.attribute.AttributeTables;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingAssetAudit;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.ClientRequestHandler;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerSave;
import com.game.scene.world.PlayerSnapshots;
import com.game.scene.world.Scene;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.Vec3;
import com.game.table.ConfigTables;
import com.google.protobuf.Message;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 宝宝协议走真实分发（ClientRequestHandler），配表用正式表；主人升级的连带（170 → 184 → 175 应答）与离场写回往返。 */
class PetFeatureTest {

    private static final long LINK = 1;
    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final String SERVICE = "ScenePetClientPlayer";
    private static final int NOTIFY_PETS = Contracts.REGISTRY.requireId(SERVICE, "NotifyPetListChanged");
    private static final int NOTIFY_PANEL = Contracts.REGISTRY.requireId("SceneAttributeClientPlayer",
            "NotifyAttributePanelChanged");
    private static ConfigTables config;

    private final RecordingSink sink = new RecordingSink();
    private final FakePlayerRepository repo = new FakePlayerRepository();
    private final ManualClock clock = new ManualClock();
    private final AtomicLong guidSeq = new AtomicLong(1L << 60);
    private SceneWorld world;
    private ClientRequestHandler handler;

    @BeforeAll
    static void loadTables() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
        config = ConfigTables.load(dir);
    }

    private void start(RunMode mode, PlayerState state) {
        CurrencyService currency = new CurrencyService(new RecordingAssetAudit());
        AttributeService attributes = new AttributeService(AttributeTables.from(config), clock, currency);
        PetService service = new PetService(PetTables.from(config), currency, count -> {
            long[] out = new long[count];
            for (int i = 0; i < count; i++) {
                out[i] = guidSeq.incrementAndGet();
            }
            return out;
        }, clock, new SplittableRandom(7));
        PetFeature pets = new PetFeature(service, Contracts.REGISTRY);
        FakeSceneTables sceneTables = new FakeSceneTables();
        world = new SceneWorld(sceneTables, Contracts.IDS, sink, repo, new AtomicLong(5000)::incrementAndGet, clock,
                SceneMetrics.noop(), player -> {
                    attributes.initializeOnLoad(player);
                    service.initializeOnLoad(player);
                }, PlayerSnapshots.NONE);
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS, mode,
                List.of(new AttributeFeature(attributes, Contracts.REGISTRY, pets::onOwnerLevelChanged), pets));
        Scene scene = world.createScene(1);
        repo.put(new PlayerData(PLAYER, 1, 1, 1, "", 1, 0, Vec3.ORIGIN, state));
        world.onPlayerEnter(LINK, PlayerEnter.newBuilder()
                .setSessionId(SESSION).setPlayerId(PLAYER).setSceneId(scene.sceneId()).setOwnerEpoch(1).build());
        repo.completeAll();
        sink.clear();
    }

    private PetListInfo list() throws Exception {
        GetPetListResponse response = GetPetListResponse.parseFrom(
                call(SERVICE, "GetPetList", GetPetListRequest.getDefaultInstance()).getSerializedMessage());
        assertThat(response.getErrorMessage().getId()).isZero();
        return response.getPets();
    }

    private GmGrantPetResponse grant(int petTableId) throws Exception {
        return GmGrantPetResponse.parseFrom(call(SERVICE, "GmGrantPet",
                GmGrantPetRequest.newBuilder().setPetTableId(petTableId).build()).getSerializedMessage());
    }

    @Test
    void 新号列表为空_GM发放后回新号与全量列表() throws Exception {
        start(RunMode.DEV, null);
        assertThat(list().getPetsList()).isEmpty();

        GmGrantPetResponse granted = grant(1);

        assertThat(granted.getErrorMessage().getId()).isZero();
        assertThat(granted.getPetId()).isNotZero();
        assertThat(granted.getPets().getPetsList()).singleElement()
                .satisfies(pet -> {
                    assertThat(pet.getPetId()).isEqualTo(granted.getPetId());
                    assertThat(pet.getDimensionsCount()).isEqualTo(4);
                    assertThat(pet.getDerived().getHealth()).isEqualTo(pet.getDerived().getMaxHealth());
                });
        GmGrantPetResponse unknown = grant(99);
        assertThat(unknown.getErrorMessage().getId()).isEqualTo(26001);
        assertThat(unknown.hasPets()).as("失败只回 tip").isFalse();
    }

    @Test
    void 生产模式_GM发放在分发入口回1006() throws Exception {
        start(RunMode.PROD, null);
        assertThat(grant(1).getErrorMessage().getId()).isEqualTo(1006);
        assertThat(list().getPetsList()).isEmpty();
    }

    @Test
    void 加点请求宝宝号0或没带维度回1005() throws Exception {
        start(RunMode.DEV, null);
        long petId = grant(1).getPetId();
        for (AllocatePetPointsRequest request : List.of(
                AllocatePetPointsRequest.newBuilder().putAllocated(403, 1).build(),
                AllocatePetPointsRequest.newBuilder().setPetId(petId).build())) {
            AllocatePetPointsResponse response = AllocatePetPointsResponse.parseFrom(
                    call(SERVICE, "AllocatePetPoints", request).getSerializedMessage());
            assertThat(response.getErrorMessage().getId()).isEqualTo(1005);
        }
    }

    @Test
    void 主人升级_先推170再推184再回175应答_宝宝跟着升级() throws Exception {
        start(RunMode.DEV, null);
        long petId = grant(1).getPetId();

        handler.onClientForward(LINK, forward("SceneAttributeClientPlayer", "GmSetPlayerLevel",
                GmSetPlayerLevelRequest.newBuilder().setLevel(30).build()));

        List<MessageContent> sent = sink.to(LINK, SESSION);
        assertThat(sent).extracting(MessageContent::getMessageId).endsWith(NOTIFY_PANEL, NOTIFY_PETS,
                Contracts.REGISTRY.requireId("SceneAttributeClientPlayer", "GmSetPlayerLevel"));
        PetListInfo pushed = PetListChangedS2C.parseFrom(sent.get(sent.size() - 2).getSerializedMessage()).getPets();
        assertThat(pushed.getPets(0).getPetId()).isEqualTo(petId);
        assertThat(pushed.getPets(0).getLevel()).isEqualTo(30);
        assertThat(pushed.getPets(0).getTotalPoints()).isEqualTo(150);
    }

    @Test
    void 没有宝宝时主人升级也推空列表_同基线() throws Exception {
        start(RunMode.DEV, null);
        handler.onClientForward(LINK, forward("SceneAttributeClientPlayer", "GmSetPlayerLevel",
                GmSetPlayerLevelRequest.newBuilder().setLevel(5).build()));
        MessageContent push = sink.to(LINK, SESSION).stream().filter(m -> m.getMessageId() == NOTIFY_PETS)
                .findFirst().orElseThrow();
        PetListInfo pets = PetListChangedS2C.parseFrom(push.getSerializedMessage()).getPets();
        assertThat(pets.getPetsList()).isEmpty();
        assertThat(pets.getMaxPets()).isEqualTo(10);
        assertThat(pets.getRenameCostGold()).isEqualTo(200);
    }

    @Test
    void 宝宝随离场写回_重新进场列表原样() throws Exception {
        start(RunMode.DEV, null);
        long petId = grant(1).getPetId();
        SummonPetResponse summoned = SummonPetResponse.parseFrom(call(SERVICE, "SummonPet",
                SummonPetRequest.newBuilder().setPetId(petId).build()).getSerializedMessage());
        assertThat(summoned.getErrorMessage().getId()).isZero();
        PetListInfo before = list();

        world.onPlayerLeave(LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER).setVoluntary(true)
                .build());
        PlayerSave save = repo.saves().getLast();
        assertThat(save.state().getPets().getActivePetId()).isEqualTo(petId);

        start(RunMode.DEV, save.state());
        assertThat(list()).isEqualTo(before);
    }

    @Test
    void 没有宝宝时存档不带宝宝段() throws Exception {
        start(RunMode.DEV, null);
        list();
        world.onPlayerLeave(LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER).setVoluntary(true)
                .build());
        assertThat(repo.saves().getLast().state().hasPets()).isFalse();
    }

    private ClientForward forward(String service, String method, Message request) {
        return ClientForward.newBuilder()
                .setSessionId(SESSION)
                .setPlayerId(PLAYER)
                .setMessageId(Contracts.REGISTRY.requireId(service, method))
                .setBody(request.toByteString())
                .setRequestId(7)
                .build();
    }

    private MessageContent call(String service, String method, Message request) {
        int messageId = Contracts.REGISTRY.requireId(service, method);
        int before = sink.to(LINK, SESSION).size();
        handler.onClientForward(LINK, forward(service, method, request));
        List<MessageContent> sent = sink.to(LINK, SESSION);
        assertThat(sent).hasSize(before + 1);
        MessageContent reply = sent.getLast();
        assertThat(reply.getMessageId()).isEqualTo(messageId);
        return reply;
    }
}
