package com.game.scene.bag;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.common.RunMode;
import com.game.player.store.state.BagItemState;
import com.game.player.store.state.BagState;
import com.game.player.store.state.PlayerState;
import com.game.proto.BagInfo;
import com.game.proto.BagItemInfo;
import com.game.proto.BagSlotInfo;
import com.game.proto.GetBagRequest;
import com.game.proto.GetBagResponse;
import com.game.proto.MessageContent;
import com.game.proto.SortBagRequest;
import com.game.proto.SortBagResponse;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.BagType;
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
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.Vec3;
import com.game.scene.world.WorldTestAccess;
import com.game.table.ConfigTables;
import com.google.protobuf.Message;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 191 GetBag / 192 SortBag 走真实分发（ClientRequestHandler），配表用正式表；存盘往返走 SceneWorld 写回。 */
class BagFeatureTest {

    private static final long LINK = 1;
    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static BagTables tables;

    private final RecordingSink sink = new RecordingSink();
    private final FakePlayerRepository repo = new FakePlayerRepository();
    private final RecordingAssetAudit audit = new RecordingAssetAudit();
    private final AtomicLong guidSeq = new AtomicLong(1L << 60);
    private BagService service;
    private SceneWorld world;
    private ClientRequestHandler handler;

    @BeforeAll
    static void loadTables() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
        tables = BagTables.from(ConfigTables.load(dir));
    }

    private boolean start(PlayerState state) {
        service = new BagService(tables, count -> {
            long[] out = new long[count];
            for (int i = 0; i < count; i++) {
                out[i] = guidSeq.incrementAndGet();
            }
            return out;
        }, audit, GainAnomalyDetector.off(), SceneMetrics.noop());
        FakeSceneTables sceneTables = new FakeSceneTables();
        world = new SceneWorld(sceneTables, Contracts.IDS, sink, repo, new AtomicLong(5000)::incrementAndGet,
                new ManualClock(), SceneMetrics.noop(), service::initializeOnLoad, PlayerSnapshots.NONE);
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV,
                List.of(new BagFeature(service)));
        Scene scene = world.createScene(1);
        repo.put(new PlayerData(PLAYER, 1, 3, 1, "", 1, 0, Vec3.ORIGIN, state));
        world.onPlayerEnter(LINK, PlayerEnter.newBuilder()
                .setSessionId(SESSION).setPlayerId(PLAYER).setSceneId(scene.sceneId()).setOwnerEpoch(1).build());
        repo.completeAll();
        boolean entered = player() != null;
        sink.clear();
        return entered;
    }

    private ScenePlayer player() {
        return WorldTestAccess.player(world, LINK, SESSION);
    }

    private void give(BagType type, Map<Integer, Long> counts) {
        assertThat(service.addItems(player(), type, counts, Reason.SYSTEM_GRANT, 0, "").ok()).isTrue();
    }

    private GetBagResponse getBag(int bagType) throws Exception {
        return GetBagResponse.parseFrom(call("GetBag", GetBagRequest.newBuilder().setBagType(bagType).build())
                .getSerializedMessage());
    }

    private SortBagResponse sortBag(int bagType) throws Exception {
        return SortBagResponse.parseFrom(call("SortBag", SortBagRequest.newBuilder().setBagType(bagType).build())
                .getSerializedMessage());
    }

    @Test
    void 新号四个包都是空的_布局总在_容量与可整理同基线_货币同54() throws Exception {
        start(null);
        int[] capacities = {100, 200, 10, 200};
        for (int type = 0; type < 4; type++) {
            GetBagResponse response = getBag(type);
            assertThat(response.hasErrorMessage()).isTrue();
            assertThat(response.getErrorMessage().getId()).isZero();
            BagInfo bag = response.getBag();
            assertThat(bag.hasLayout()).isTrue();
            assertThat(bag.getLayout().getBagType()).isEqualTo(type);
            assertThat(bag.getLayout().getCapacity()).isEqualTo(capacities[type]);
            assertThat(bag.getLayout().getCanSort()).isEqualTo(type <= 1);
            assertThat(bag.getItemsList()).isEmpty();
            assertThat(bag.getCurrency()).isEqualTo(player().wallet().toClient());
        }
    }

    @Test
    void bag_type按无符号看_4与全1都回1005_不带bag() throws Exception {
        start(null);
        for (int type : new int[] {4, -1, Integer.MIN_VALUE}) {
            GetBagResponse response = getBag(type);
            assertThat(response.getErrorMessage().getId()).isEqualTo(1005);
            assertThat(response.hasBag()).isFalse();
        }
    }

    @Test
    void 物品按item_id无符号升序_格子按格子号升序_每格1x1_表里查不到的不填上限与部位() throws Exception {
        BagState state = BagState.newBuilder()
                .addItems(BagItemState.newBuilder().setItemUuid(-5L).setConfigId(10).setStackSize(7).setPos(9)
                        .setBagType(0).setAcquireSeq(1))
                .addItems(BagItemState.newBuilder().setItemUuid(3).setConfigId(1).setStackSize(1).setPos(17)
                        .setBagType(0).setAcquireSeq(2))
                .addItems(BagItemState.newBuilder().setItemUuid(1L << 60 | 77).setConfigId(987654).setStackSize(2)
                        .setPos(0).setBagType(0).setAcquireSeq(3))
                .build();
        start(PlayerState.newBuilder().setBag(state).build());

        BagInfo bag = getBag(0).getBag();

        assertThat(bag.getItemsList()).extracting(BagItemInfo::getItemId)
                .containsExactly(3L, 1L << 60 | 77, -5L);
        assertThat(bag.getItems(0)).isEqualTo(BagItemInfo.newBuilder().setItemId(3).setConfigId(1).setCount(1)
                .setMaxStack(1).setEquipKind(1).build());
        assertThat(bag.getItems(1).getMaxStack()).isZero();
        assertThat(bag.getItems(1).getEquipKind()).isZero();
        assertThat(bag.getItems(2).getMaxStack()).isEqualTo(999);
        assertThat(bag.getLayout().getSlotsList()).extracting(BagSlotInfo::getSlot).containsExactly(0, 9, 17);
        assertThat(bag.getLayout().getSlotsList()).allSatisfy(slot -> {
            assertThat(slot.getWidth()).isEqualTo(1);
            assertThat(slot.getHeight()).isEqualTo(1);
        });
        assertThat(getBag(0).getBag()).as("读取不改动背包").isEqualTo(bag);
    }

    @Test
    void 整理合并重排_回changed与整包_再整理一次不变_退役实例记数量0的销毁流水() throws Exception {
        start(null);
        give(BagType.INVENTORY, Map.of(1, 1L));
        give(BagType.INVENTORY, Map.of(10, 998L));
        give(BagType.INVENTORY, Map.of(10, 2L));
        give(BagType.INVENTORY, Map.of(9, 1L));
        audit.items.clear();

        SortBagResponse first = sortBag(0);
        SortBagResponse second = sortBag(0);

        assertThat(first.getErrorMessage().getId()).isZero();
        assertThat(first.getChanged()).isTrue();
        assertThat(first.getBag().getLayout().getSlotsList()).hasSize(4);
        assertThat(first.getBag().getItemsList()).extracting(BagItemInfo::getConfigId).containsOnly(1, 9, 10);
        assertThat(second.getChanged()).isFalse();
        assertThat(second.getBag()).isEqualTo(first.getBag());
        assertThat(first.getBag().getCurrency()).isEqualTo(player().wallet().toClient());
        assertThat(audit.items).as("没有合并掉的实例就没有流水").isEmpty();
    }

    @Test
    void 存档里同配置的两个零头整理时合并_退役实例记数量0的销毁流水() throws Exception {
        BagState state = BagState.newBuilder()
                .addItems(BagItemState.newBuilder().setItemUuid(41).setConfigId(10).setStackSize(5).setPos(0)
                        .setBagType(0).setAcquireSeq(1))
                .addItems(BagItemState.newBuilder().setItemUuid(42).setConfigId(10).setStackSize(6).setPos(1)
                        .setBagType(0).setAcquireSeq(2))
                .build();
        start(PlayerState.newBuilder().setBag(state).build());

        SortBagResponse response = sortBag(0);

        assertThat(response.getChanged()).isTrue();
        assertThat(response.getBag().getItemsList()).containsExactly(BagItemInfo.newBuilder().setItemId(41)
                .setConfigId(10).setCount(11).setMaxStack(999).build());
        assertThat(audit.items).containsExactly(
                new RecordingAssetAudit.Item(false, PLAYER, 42, 10, 0, Reason.ITEM_DESTROY, 0, ""));
    }

    @Test
    void 只能整理人物背包与仓库_其他回1005不带bag与changed() throws Exception {
        start(null);
        for (int type : new int[] {2, 3, 4, -1}) {
            SortBagResponse response = sortBag(type);
            assertThat(response.getErrorMessage().getId()).isEqualTo(1005);
            assertThat(response.hasBag()).isFalse();
            assertThat(response.getChanged()).isFalse();
        }
        assertThat(sortBag(1).getErrorMessage().getId()).isZero();
    }

    @Test
    void 背包随离场写回_重新进场guid与格子原样() throws Exception {
        start(null);
        give(BagType.INVENTORY, Map.of(1, 2L, 10, 1500L));
        give(BagType.EQUIPMENT, Map.of(2, 1L));
        BagInfo inventory = getBag(0).getBag();
        BagInfo equipment = getBag(2).getBag();

        world.onPlayerLeave(LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER).setVoluntary(true)
                .build());
        PlayerSave save = repo.saves().getLast();
        assertThat(save.state().getBag().getItemsCount()).isEqualTo(5);

        sink.clear();
        start(save.state());
        assertThat(getBag(0).getBag()).isEqualTo(inventory);
        assertThat(getBag(2).getBag()).isEqualTo(equipment);
    }

    @Test
    void 没动过背包_存档不带背包数据() throws Exception {
        start(null);
        getBag(0);
        sortBag(0);
        world.onPlayerLeave(LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER).setVoluntary(true)
                .build());
        assertThat(repo.saves().getLast().state().hasBag()).isFalse();
    }

    @Test
    void 坏档拒绝进场_不当成空包() {
        BagState corrupt = BagState.newBuilder()
                .addItems(BagItemState.newBuilder().setItemUuid(5).setConfigId(1).setStackSize(1).setBagType(0))
                .addItems(BagItemState.newBuilder().setItemUuid(5).setConfigId(1).setStackSize(1).setPos(1).setBagType(0))
                .build();

        assertThat(start(PlayerState.newBuilder().setBag(corrupt).build())).isFalse();
        assertThat(repo.releases()).as("进场失败、释放这次夺得的归属").isNotEmpty();
        assertThat(repo.saves()).as("坏档不会被当成空包存回去").isEmpty();
    }

    /** 发一条请求，返回它的应答（每条请求恰好一个应答）。 */
    private MessageContent call(String method, Message request) {
        int messageId = Contracts.REGISTRY.requireId("SceneBagClientPlayer", method);
        int before = sink.to(LINK, SESSION).size();
        handler.onClientForward(LINK, ClientForward.newBuilder()
                .setSessionId(SESSION)
                .setPlayerId(PLAYER)
                .setMessageId(messageId)
                .setBody(request.toByteString())
                .setRequestId(7)
                .build());
        List<MessageContent> sent = sink.to(LINK, SESSION);
        assertThat(sent).hasSize(before + 1);
        MessageContent reply = sent.getLast();
        assertThat(reply.getMessageId()).isEqualTo(messageId);
        return reply;
    }
}
