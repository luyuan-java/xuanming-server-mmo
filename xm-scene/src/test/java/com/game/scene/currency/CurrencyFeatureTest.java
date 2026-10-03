package com.game.scene.currency;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.common.RunMode;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerState;
import com.game.proto.GetCurrencyListRequest;
import com.game.proto.GetCurrencyListResponse;
import com.game.proto.GmAddCurrencyRequest;
import com.game.proto.GmAddCurrencyResponse;
import com.game.proto.GmBlockCurrencyRequest;
import com.game.proto.GmBlockCurrencyResponse;
import com.game.proto.GmDeductCurrencyRequest;
import com.game.proto.GmDeductCurrencyResponse;
import com.game.proto.GmUnblockCurrencyRequest;
import com.game.proto.MessageContent;
import com.game.scene.audit.AssetAudit;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.Wallet;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.ClientRequestHandler;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerSave;
import com.game.scene.world.Scene;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.Vec3;
import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CurrencyFeatureTest {

    private static final long LINK = 1;
    private static final int SESSION = 11;
    private static final long PLAYER = 1001;

    private record Audited(long playerId, int type, long delta, long before, long after, Reason reason) {
    }

    private final RecordingSink sink = new RecordingSink();
    private final FakePlayerRepository repo = new FakePlayerRepository();
    private final List<Audited> audits = new ArrayList<>();
    private final AssetAudit audit = (playerId, type, delta, before, after, reason) ->
            audits.add(new Audited(playerId, type, delta, before, after, reason));
    private SceneWorld world;
    private ClientRequestHandler handler;
    private Scene scene;

    private void start(RunMode mode, PlayerState state) {
        FakeSceneTables tables = new FakeSceneTables();
        AtomicLong ids = new AtomicLong(5000);
        world = new SceneWorld(tables, Contracts.IDS, sink, repo, ids::incrementAndGet, new ManualClock(), SceneMetrics.noop());
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS, tables, mode,
                List.of(new CurrencyFeature(new CurrencyService(audit))));
        scene = world.createScene(1);
        repo.put(new PlayerData(PLAYER, 1, 3, 1, "", 1, 0, Vec3.ORIGIN, state));
        world.onPlayerEnter(LINK, PlayerEnter.newBuilder()
                .setSessionId(SESSION).setPlayerId(PLAYER).setSceneId(scene.sceneId()).setOwnerEpoch(1).build());
        repo.completeAll();
        sink.clear();
    }

    @Test
    void 新号查余额_三个币种槽全0_tip为0() throws Exception {
        start(RunMode.DEV, null);

        GetCurrencyListResponse response = GetCurrencyListResponse.parseFrom(call("GetCurrencyList",
                GetCurrencyListRequest.getDefaultInstance(), 54).getSerializedMessage());

        assertThat(response.hasErrorMessage()).isTrue();
        assertThat(response.getErrorMessage().getId()).isZero();
        assertThat(response.getCurrency().getValuesList()).containsExactly(0L, 0L, 0L);
    }

    @Test
    void GM加扣_回余额并记流水_失败不回余额不记流水() throws Exception {
        start(RunMode.DEV, null);

        GmAddCurrencyResponse added = GmAddCurrencyResponse.parseFrom(call("GmAddCurrency",
                GmAddCurrencyRequest.newBuilder().setCurrencyType(Wallet.DIAMOND).setAmount(500).build(), 1)
                .getSerializedMessage());
        GmDeductCurrencyResponse deducted = GmDeductCurrencyResponse.parseFrom(call("GmDeductCurrency",
                GmDeductCurrencyRequest.newBuilder().setCurrencyType(Wallet.DIAMOND).setAmount(120).build(), 2)
                .getSerializedMessage());
        GmDeductCurrencyResponse tooMuch = GmDeductCurrencyResponse.parseFrom(call("GmDeductCurrency",
                GmDeductCurrencyRequest.newBuilder().setCurrencyType(Wallet.DIAMOND).setAmount(381).build(), 3)
                .getSerializedMessage());

        assertThat(added.getErrorMessage().getId()).isZero();
        assertThat(added.getBalanceAfter()).isEqualTo(500);
        assertThat(deducted.getErrorMessage().getId()).isZero();
        assertThat(deducted.getBalanceAfter()).isEqualTo(380);
        assertThat(tooMuch.getErrorMessage().getId()).isEqualTo(27000);
        assertThat(tooMuch.getBalanceAfter()).isZero();
        assertThat(audits).containsExactly(
                new Audited(PLAYER, Wallet.DIAMOND, 500, 0, 500, Reason.GM_GRANT),
                new Audited(PLAYER, Wallet.DIAMOND, -120, 500, 380, Reason.GM_DEDUCT));
        assertThat(balances()).containsExactly(0L, 380L, 0L);
    }

    @Test
    void 封禁后加币回27005_解封后恢复_封禁列表随余额下发() throws Exception {
        start(RunMode.TEST, null);

        GmBlockCurrencyResponse blocked = GmBlockCurrencyResponse.parseFrom(call("GmBlockCurrency",
                GmBlockCurrencyRequest.newBuilder().setCurrencyType(Wallet.GOLD).build(), 1).getSerializedMessage());
        GmAddCurrencyResponse rejected = GmAddCurrencyResponse.parseFrom(call("GmAddCurrency",
                GmAddCurrencyRequest.newBuilder().setCurrencyType(Wallet.GOLD).setAmount(1).build(), 2)
                .getSerializedMessage());
        GetCurrencyListResponse list = GetCurrencyListResponse.parseFrom(call("GetCurrencyList",
                GetCurrencyListRequest.getDefaultInstance(), 3).getSerializedMessage());
        call("GmUnblockCurrency", GmUnblockCurrencyRequest.newBuilder().setCurrencyType(Wallet.GOLD).build(), 4);
        call("GmAddCurrency", GmAddCurrencyRequest.newBuilder().setCurrencyType(Wallet.GOLD).setAmount(1).build(), 5);

        assertThat(blocked.getErrorMessage().getId()).isZero();
        assertThat(rejected.getErrorMessage().getId()).isEqualTo(27005);
        assertThat(list.getCurrency().getBlockedTypesList()).containsExactly(Wallet.GOLD);
        assertThat(balances()).containsExactly(1L, 0L, 0L);
        assertThat(audits).hasSize(1);
    }

    @Test
    void 生产模式_scene第二道锁回1006_钱包不动不记流水_查询不受影响() throws Exception {
        start(RunMode.PROD, null);

        MessageContent reply = call("GmAddCurrency",
                GmAddCurrencyRequest.newBuilder().setCurrencyType(Wallet.GOLD).setAmount(100).build(), 9);

        assertThat(reply.getId()).isEqualTo(9L);
        GmAddCurrencyResponse response = GmAddCurrencyResponse.parseFrom(reply.getSerializedMessage());
        assertThat(response.getErrorMessage().getId()).isEqualTo(1006);
        assertThat(audits).isEmpty();
        assertThat(balances()).containsExactly(0L, 0L, 0L);
    }

    @Test
    void 负数金额_分发入口静默丢弃不回包_同基线ProtoFieldChecker() throws Exception {
        start(RunMode.DEV, null);

        handler.onClientForward(LINK, ClientForward.newBuilder()
                .setSessionId(SESSION)
                .setPlayerId(PLAYER)
                .setMessageId(Contracts.REGISTRY.requireId("SceneCurrencyClientPlayer", "GmAddCurrency"))
                .setBody(GmAddCurrencyRequest.newBuilder().setCurrencyType(Wallet.GOLD).setAmount(-5).build().toByteString())
                .setRequestId(1)
                .build());

        assertThat(sink.to(LINK, SESSION)).isEmpty();
        assertThat(audits).isEmpty();
        assertThat(balances()).containsExactly(0L, 0L, 0L);
    }

    @Test
    void 余额随离场写回_重新进场恢复() throws Exception {
        start(RunMode.DEV, null);
        call("GmAddCurrency", GmAddCurrencyRequest.newBuilder().setCurrencyType(Wallet.BOUND_DIAMOND).setAmount(7).build(), 1);
        call("GmBlockCurrency", GmBlockCurrencyRequest.newBuilder().setCurrencyType(Wallet.GOLD).build(), 2);

        world.onPlayerLeave(LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER).setVoluntary(true).build());

        PlayerSave save = repo.saves().getLast();
        CurrencyState saved = save.state().getCurrency();
        assertThat(saved.getBalancesList()).containsExactly(0L, 0L, 7L);
        assertThat(saved.getBlockedTypesList()).containsExactly(Wallet.GOLD);

        sink.clear();
        start(RunMode.DEV, save.state());
        assertThat(balances()).containsExactly(0L, 0L, 7L);
    }

    @Test
    void 没动过钱包_存档不带货币数据() {
        start(RunMode.DEV, null);
        call("GetCurrencyList", GetCurrencyListRequest.getDefaultInstance(), 1);

        world.onPlayerLeave(LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER).setVoluntary(true).build());

        assertThat(repo.saves().getLast().state().hasCurrency()).isFalse();
    }

    private List<Long> balances() throws Exception {
        return GetCurrencyListResponse.parseFrom(call("GetCurrencyList", GetCurrencyListRequest.getDefaultInstance(), 0)
                .getSerializedMessage()).getCurrency().getValuesList();
    }

    /** 发一条请求，返回它的应答（每条请求恰好一个应答）。 */
    private MessageContent call(String method, Message request, long requestId) {
        int messageId = Contracts.REGISTRY.requireId("SceneCurrencyClientPlayer", method);
        int before = sink.to(LINK, SESSION).size();
        handler.onClientForward(LINK, ClientForward.newBuilder()
                .setSessionId(SESSION)
                .setPlayerId(PLAYER)
                .setMessageId(messageId)
                .setBody(request.toByteString())
                .setRequestId(requestId)
                .build());
        List<MessageContent> sent = sink.to(LINK, SESSION);
        assertThat(sent).hasSize(before + 1);
        MessageContent reply = sent.getLast();
        assertThat(reply.getMessageId()).isEqualTo(messageId);
        assertThat(reply.getId()).isEqualTo(requestId);
        return reply;
    }
}
