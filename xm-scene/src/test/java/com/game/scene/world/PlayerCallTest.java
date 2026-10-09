package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.enterFrame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.contract.MessageMethod;
import com.game.proto.GetCurrencyListResponse;
import com.game.proto.ListSkillsResponse;
import com.game.proto.MessageContent;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link PlayerCall#defer} 与 {@link DeferredReply}（批次 5.4 先行件）：一条请求至多一个应答、延迟应答回显原请求号。
 * 分发处对已延迟调用的处理（不补 1006、延迟之后抛异常才补）在 {@code ClientRequestHandlerTest}。
 */
class PlayerCallTest {

    private static final long LINK = 1;
    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final long REQUEST_ID = 4_000_000_123L;

    private static final GetCurrencyListResponse OK =
            GetCurrencyListResponse.newBuilder().setErrorMessage(SceneMessageIds.tip(0)).build();
    private static final GetCurrencyListResponse REFUSED =
            GetCurrencyListResponse.newBuilder().setErrorMessage(SceneMessageIds.tip(3026)).build();

    private RecordingSink sink;
    private SceneWorld world;
    private ScenePlayer player;
    /** 有应答的方法（应答类型 {@link GetCurrencyListResponse}）。 */
    private MessageMethod withResponse;
    /** 应答是 Empty 的方法（移动上行）。 */
    private MessageMethod withoutResponse;

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        FakePlayerRepository repo = new FakePlayerRepository();
        world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo, new AtomicLong(5000)::incrementAndGet,
                new ManualClock(), SceneMetrics.noop());
        Scene scene = world.createScene(1);
        repo.putNewPlayer(PLAYER, 1);
        world.onPlayerEnter(LINK, enterFrame(SESSION, PLAYER, scene.sceneId(), 1));
        repo.completeAll();
        sink.clear();
        player = world.playerBySession(new SessionKey(LINK, SESSION));
        withResponse = Contracts.REGISTRY.byId(Contracts.REGISTRY.requireId("SceneCurrencyClientPlayer", "GetCurrencyList"))
                .orElseThrow();
        withoutResponse = Contracts.REGISTRY.byId(Contracts.IDS.moveSync()).orElseThrow();
    }

    private PlayerCall call() {
        return new PlayerCall(world, player, withResponse, REQUEST_ID);
    }

    private List<MessageContent> sent() {
        return sink.to(LINK, SESSION);
    }

    @Test
    void 延迟应答_回的那一条message_id同请求_id是原请求号_内容是给的应答() throws Exception {
        PlayerCall call = call();

        DeferredReply deferred = call.defer();

        assertThat(call.deferred()).isTrue();
        assertThat(call.replied()).as("延迟不等于已回").isFalse();
        assertThat(deferred.pending()).isTrue();
        assertThat(deferred.requestId()).isEqualTo(REQUEST_ID);
        assertThat(sent()).as("延迟本身不发任何东西").isEmpty();

        assertThat(deferred.reply(REFUSED)).isTrue();

        assertThat(deferred.pending()).isFalse();
        assertThat(sent()).hasSize(1);
        MessageContent reply = sent().get(0);
        assertThat(reply.getMessageId()).isEqualTo(withResponse.messageId());
        assertThat(reply.getId()).as("信封 id 回显原请求号（客户端按它配对）").isEqualTo(REQUEST_ID);
        assertThat(reply.hasErrorMessage()).as("业务码在应答体里，不是信封错误").isFalse();
        assertThat(GetCurrencyListResponse.parseFrom(reply.getSerializedMessage())).isEqualTo(REFUSED);
    }

    @Test
    void 延迟应答回两次_第二次返回false_只发出第一条() throws Exception {
        DeferredReply deferred = call().defer();

        assertThat(deferred.reply(OK)).isTrue();
        assertThat(deferred.reply(REFUSED)).as("已回过").isFalse();

        assertThat(sent()).hasSize(1);
        assertThat(GetCurrencyListResponse.parseFrom(sent().get(0).getSerializedMessage()).getErrorMessage().getId())
                .as("发出去的是第一次的内容").isZero();
    }

    @Test
    void 作废之后再回_返回false_什么也不发_重复作废无害() {
        DeferredReply deferred = call().defer();

        deferred.cancel();
        deferred.cancel();

        assertThat(deferred.pending()).isFalse();
        assertThat(deferred.reply(OK)).isFalse();
        assertThat(sent()).isEmpty();
    }

    @Test
    void 已回过之后作废_不撤回已发的应答() {
        DeferredReply deferred = call().defer();
        deferred.reply(OK);

        deferred.cancel();

        assertThat(sent()).hasSize(1);
        assertThat(deferred.pending()).isFalse();
    }

    @Test
    void defer之后再经PlayerCall回应答_抛异常且什么也不发_延迟应答仍待回() {
        PlayerCall call = call();
        DeferredReply deferred = call.defer();

        assertThatThrownBy(() -> call.reply(OK)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("延迟应答");

        assertThat(sent()).isEmpty();
        assertThat(call.replied()).isFalse();
        assertThat(deferred.pending()).as("那次错误的 reply 没有消耗掉延迟应答").isTrue();
        assertThat(deferred.reply(OK)).isTrue();
        assertThat(sent()).hasSize(1);
    }

    @Test
    void 同一请求defer两次_第二次抛异常_第一次拿到的仍可用() {
        PlayerCall call = call();
        DeferredReply first = call.defer();

        assertThatThrownBy(call::defer).isInstanceOf(IllegalStateException.class).hasMessageContaining("两次");

        assertThat(call.deferredReply()).isSameAs(first);
        assertThat(first.reply(OK)).isTrue();
    }

    @Test
    void 已经回过应答再defer_抛异常_不产生第二个应答() {
        PlayerCall call = call();
        call.reply(OK);

        assertThatThrownBy(call::defer).isInstanceOf(IllegalStateException.class).hasMessageContaining("已经回过");

        assertThat(call.deferred()).isFalse();
        assertThat(call.deferredReply()).isNull();
        assertThat(sent()).hasSize(1);
    }

    @Test
    void 没有应答的方法不能延迟() {
        PlayerCall noReply = new PlayerCall(world, player, withoutResponse, 0);

        assertThatThrownBy(noReply::defer).isInstanceOf(IllegalStateException.class).hasMessageContaining("没有应答");

        assertThat(noReply.deferred()).isFalse();
    }

    @Test
    void 延迟应答的类型与契约不符_抛异常_什么也不发_仍可用正确的类型回() {
        DeferredReply deferred = call().defer();

        assertThatThrownBy(() -> deferred.reply(ListSkillsResponse.getDefaultInstance()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("应答类型");

        assertThat(sent()).isEmpty();
        assertThat(deferred.pending()).isTrue();
        assertThat(deferred.reply(OK)).isTrue();
        assertThat(sent()).hasSize(1);
    }

    @Test
    void 实例被移出世界之后延迟应答照发给原会话() {
        DeferredReply deferred = call().defer();
        world.onPlayerLeave(LINK, com.game.api.proto.PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER).build());
        assertThat(world.playerBySession(new SessionKey(LINK, SESSION))).as("实例已离开").isNull();
        sink.clear();

        assertThat(deferred.reply(REFUSED)).isTrue();

        assertThat(sent()).as("发给发起请求的那个会话（会话已走时由 gate 丢弃）").hasSize(1);
        assertThat(sent().get(0).getId()).isEqualTo(REQUEST_ID);
    }
}
