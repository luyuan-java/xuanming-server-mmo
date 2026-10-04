package com.game.guild.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.contract.GuildContractFixtures;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.guild.rules.GuildTip;
import com.game.guild.rules.GuildTips;
import com.game.guild.service.GuildServiceFixture;
import com.game.proto.TipInfoMessage;
import com.game.proto.guild.CreateGuildRequest;
import com.game.proto.guild.CreateGuildResponse;
import com.game.proto.guild.DonateToGuildResponse;
import com.game.proto.guild.GetGuildRankRequest;
import com.game.proto.guild.GetGuildRankResponse;
import com.game.proto.guild.GetPlayerGuildResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 派发器的准入与错误映射（guild-spec §7.3、§6.5、§11.2；基线 session_test.go:59-147 的准入用例、guild_server.go:128-133）。
 * 已拍板的两处覆盖：过载 → in-band 14021「guild service overloaded」（不是信封）；4.5 / 4.6 的 10 个号 → in-band 1006。
 */
class GuildDispatcherTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final long ME = 42;

    private final GuildServiceFixture f = new GuildServiceFixture();

    @AfterEach
    void close() {
        f.close();
    }

    private static int id(String method) {
        return REGISTRY.requireId(GuildMethods.SERVICE, method);
    }

    private GuildDispatcher dispatcher(Executor executor, long budgetMillis) {
        return new GuildDispatcher(REGISTRY, f.guilds, f.manage, f.rankService, executor, f.metrics, budgetMillis);
    }

    private GuildDispatcher dispatcher() {
        return dispatcher(Runnable::run, 3500);
    }

    private static ClientCall call(int messageId, long playerId, ByteString body) {
        return ClientCall.newBuilder().setMessageId(messageId).setBody(body).setRequestId(77)
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setSessionId(9).setPlayerId(playerId)).build();
    }

    private double requests(String method, String result) {
        return f.meters.get("xm.guild.requests").tag("method", method).tag("result", result).timer().count();
    }

    private static final ClientReply ENVELOPE_1003 = ClientReply.newBuilder().setTipId(1003).build();
    private static final ByteString BAD_BODY = ByteString.copyFrom(new byte[] {(byte) 0xFF, 0x01});

    // ================================================================ 路由与启动校验

    @Test
    void 接管GuildService的全部28个号() {
        Set<Integer> expected = new HashSet<>();
        for (String method : GuildMethods.all()) {
            expected.add(id(method));
        }
        assertThat(expected).hasSize(28);
        GuildDispatcher d = dispatcher();
        assertThat(d.routedMessageIds()).isEqualTo(expected);
        assertThat(d.messageIdsOf(GuildMethods.CLIENT_REQUESTS)).hasSize(16);
        assertThat(d.messageIdsOf(GuildMethods.FORBIDDEN)).containsExactlyInAnyOrder(8, 220);
        assertThat(d.messageIdsOf(GuildMethods.PLACEHOLDERS)).containsExactlyInAnyOrder(53, 76, 120, 228, 233, 239, 240, 241,
                242, 243);
        // 契约里的 GuildService 恰好就是这 28 个
        Set<String> contract = new HashSet<>();
        for (MessageMethod m : REGISTRY.all()) {
            if (GuildMethods.SERVICE.equals(m.serviceName())) {
                contract.add(m.methodName());
            }
        }
        assertThat(contract).containsExactlyInAnyOrderElementsOf(GuildMethods.all());
    }

    @Test
    void 启动校验_契约缺号即失败() {
        for (String method : List.of(GuildMethods.CREATE_GUILD, GuildMethods.NOTIFY_GUILD_CHANGED,
                GuildMethods.DONATE_TO_GUILD, GuildMethods.UPDATE_GUILD_SCORE)) {
            MessageIdRegistry broken = GuildContractFixtures.registryWithout(GuildMethods.SERVICE + method);
            assertThatThrownBy(() -> new GuildDispatcher(broken, f.guilds, f.manage, f.rankService, Runnable::run, f.metrics,
                    3500)).as(method).isInstanceOf(IllegalStateException.class).hasMessageContaining(method);
        }
    }

    @Test
    void C2S与占位的应答都有error_message字段_220的应答是Empty() {
        for (String method : GuildMethods.all()) {
            MessageMethod m = REGISTRY.byId(id(method)).orElseThrow();
            FieldDescriptor field = m.responsePrototype().getDescriptorForType().findFieldByName("error_message");
            if (method.equals(GuildMethods.NOTIFY_GUILD_CHANGED)) {
                assertThat(m.responsePrototype()).isInstanceOf(com.game.proto.Empty.class);
            } else {
                assertThat(field).as(method).isNotNull();
                assertThat(field.getNumber()).as(method).isEqualTo(1);
            }
        }
    }

    // ================================================================ 准入

    @Test
    void 上行8与220回信封1003_计forbidden_不碰业务() {
        GuildDispatcher d = dispatcher();
        assertThat(d.dispatch(call(8, ME, ByteString.EMPTY)).join()).isEqualTo(ENVELOPE_1003);
        assertThat(d.dispatch(call(220, ME, ByteString.EMPTY)).join()).isEqualTo(ENVELOPE_1003);
        assertThat(requests(GuildMethods.UPDATE_GUILD_SCORE, "forbidden")).isEqualTo(1);
        assertThat(requests(GuildMethods.NOTIFY_GUILD_CHANGED, "forbidden")).isEqualTo(1);
        assertThat(f.zones).isEmpty();
    }

    @Test
    void 会话没有绑定玩家回信封1003_计unauthenticated() {
        GuildDispatcher d = dispatcher();
        int create = id(GuildMethods.CREATE_GUILD);
        assertThat(d.dispatch(call(create, 0, ByteString.EMPTY)).join()).isEqualTo(ENVELOPE_1003);
        assertThat(d.dispatch(ClientCall.newBuilder().setMessageId(create).build()).join()).isEqualTo(ENVELOPE_1003);
        assertThat(d.dispatch(call(8, 0, ByteString.EMPTY)).join()).isEqualTo(ENVELOPE_1003);
        assertThat(d.dispatch(call(id(GuildMethods.DONATE_TO_GUILD), 0, ByteString.EMPTY)).join()).isEqualTo(ENVELOPE_1003);
        assertThat(requests(GuildMethods.CREATE_GUILD, "unauthenticated")).isEqualTo(2);
        assertThat(requests(GuildMethods.UPDATE_GUILD_SCORE, "unauthenticated")).isEqualTo(1);
        assertThat(requests(GuildMethods.DONATE_TO_GUILD, "unauthenticated")).isEqualTo(1);
    }

    @Test
    void 请求体解析失败回信封1003_解析先于身份检查() {
        GuildDispatcher d = dispatcher();
        int create = id(GuildMethods.CREATE_GUILD);
        ClientReply reply = d.dispatch(call(create, ME, BAD_BODY)).join();
        assertThat(reply).isEqualTo(ENVELOPE_1003);
        assertThat(reply.getBody()).isEmpty();
        // player_id = 0 且请求体坏：记 bad_request，不是 unauthenticated
        d.dispatch(call(create, 0, BAD_BODY)).join();
        d.dispatch(call(220, 0, BAD_BODY)).join();
        assertThat(requests(GuildMethods.CREATE_GUILD, "bad_request")).isEqualTo(2);
        assertThat(requests(GuildMethods.CREATE_GUILD, "unauthenticated")).isZero();
        assertThat(requests(GuildMethods.NOTIFY_GUILD_CHANGED, "bad_request")).isEqualTo(1);
        // proto3 string 非法 UTF-8 也是解析失败
        ByteString badUtf8 = ByteString.copyFrom(new byte[] {0x12, 0x02, (byte) 0xC3, 0x28});
        assertThat(d.dispatch(call(create, ME, badUtf8)).join()).isEqualTo(ENVELOPE_1003);
    }

    @Test
    void 经济与活动的10个号回in_band_1006_计unsupported() throws Exception {
        GuildDispatcher d = dispatcher();
        for (String method : GuildMethods.PLACEHOLDERS) {
            ClientReply reply = d.dispatch(call(id(method), ME, ByteString.EMPTY)).join();
            assertThat(reply.getTipId()).as(method).isZero();
            Message response = REGISTRY.byId(id(method)).orElseThrow().responsePrototype().getParserForType()
                    .parseFrom(reply.getBody());
            FieldDescriptor field = response.getDescriptorForType().findFieldByName("error_message");
            TipInfoMessage tip = (TipInfoMessage) response.getField(field);
            assertThat(tip.getId()).as(method).isEqualTo(GuildTips.FEATURE_UNAVAILABLE);
            assertThat(requests(method, "unsupported")).as(method).isEqualTo(1);
        }
        DonateToGuildResponse donate = DonateToGuildResponse.parseFrom(
                d.dispatch(call(id(GuildMethods.DONATE_TO_GUILD), ME, ByteString.EMPTY)).join().getBody());
        assertThat(donate.getErrorMessage()).isEqualTo(GuildTip.FEATURE_UNAVAILABLE.proto());
        assertThat(donate.hasGuild()).isFalse();
    }

    @Test
    void 不认识的号_契约里没有回1013_有但不归guild回1006() {
        GuildDispatcher d = dispatcher();
        assertThat(d.dispatch(call(65_000, ME, ByteString.EMPTY)).join().getTipId()).isEqualTo(1013);
        int login = REGISTRY.requireId("ClientPlayerLogin", "Login");
        assertThat(d.dispatch(call(login, ME, ByteString.EMPTY)).join().getTipId()).isEqualTo(1006);
        assertThat(requests(com.game.guild.metrics.GuildMetrics.UNROUTED, "unsupported")).isEqualTo(2);
    }

    // ================================================================ 结果分类

    @Test
    void 正常应答_业务拒绝_in_band故障14008_结果分类() throws InvalidProtocolBufferException {
        GuildDispatcher d = dispatcher();
        f.zones.put(ME, GuildServiceFixture.ZONE);
        ClientReply ok = d.dispatch(call(id(GuildMethods.GET_GUILD_RANK), ME,
                GetGuildRankRequest.newBuilder().setPageSize(60).build().toByteString())).join();
        assertThat(ok.getTipId()).isZero();
        GetGuildRankResponse rank = GetGuildRankResponse.parseFrom(ok.getBody());
        assertThat(rank.hasErrorMessage()).isFalse();
        assertThat(rank.getPageSize()).isEqualTo(50);
        assertThat(requests(GuildMethods.GET_GUILD_RANK, "ok")).isEqualTo(1);

        ClientReply rejected = d.dispatch(call(id(GuildMethods.GET_PLAYER_GUILD), ME, ByteString.EMPTY)).join();
        assertThat(GetPlayerGuildResponse.parseFrom(rejected.getBody()).getErrorMessage())
                .isEqualTo(GuildTip.NOT_IN_ANY_GUILD.proto());
        assertThat(requests(GuildMethods.GET_PLAYER_GUILD, "business_error")).isEqualTo(1);

        f.guildIds = () -> {
            throw new IllegalStateException("lease lost");
        };
        ClientReply fault = d.dispatch(call(id(GuildMethods.CREATE_GUILD), ME,
                CreateGuildRequest.newBuilder().setName("青云门").build().toByteString())).join();
        assertThat(fault.getTipId()).isZero();
        assertThat(CreateGuildResponse.parseFrom(fault.getBody()).getErrorMessage().getId())
                .isEqualTo(GuildTips.ID_GEN_UNAVAILABLE);
        assertThat(requests(GuildMethods.CREATE_GUILD, "fault")).isEqualTo(1);
    }

    @Test
    void 依赖故障与处理器异常回信封1003_不带body() {
        GuildDispatcher d = dispatcher();
        f.store.readFailure = new DependencyException("mysql down");
        ClientReply reply = d.dispatch(call(id(GuildMethods.GET_PLAYER_GUILD), ME, ByteString.EMPTY)).join();
        assertThat(reply).isEqualTo(ENVELOPE_1003);
        assertThat(reply.getTipParametersList()).isEmpty();
        assertThat(requests(GuildMethods.GET_PLAYER_GUILD, "internal_error")).isEqualTo(1);
    }

    @Test
    void 工作队列满回in_band_14021_guild_service_overloaded() throws InvalidProtocolBufferException {
        GuildDispatcher d = dispatcher(task -> {
            throw new RejectedExecutionException("full");
        }, 3500);
        ClientReply reply = d.dispatch(call(id(GuildMethods.CREATE_GUILD), ME, ByteString.EMPTY)).join();
        assertThat(reply.getTipId()).isZero();
        TipInfoMessage tip = CreateGuildResponse.parseFrom(reply.getBody()).getErrorMessage();
        assertThat(tip.getId()).isEqualTo(GuildTips.BUSY_RETRY);
        assertThat(tip.getParametersList()).containsExactly("guild service overloaded");
        assertThat(requests(GuildMethods.CREATE_GUILD, "overloaded")).isEqualTo(1);
    }

    @Test
    void 排队超过预算回in_band_14021() throws Exception {
        GuildDispatcher d = dispatcher(task -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            task.run();
        }, 1);
        ClientReply reply = d.dispatch(call(id(GuildMethods.GET_PLAYER_GUILD), ME, ByteString.EMPTY)).join();
        assertThat(GetPlayerGuildResponse.parseFrom(reply.getBody()).getErrorMessage())
                .isEqualTo(GuildTip.OVERLOADED.proto());
        assertThat(requests(GuildMethods.GET_PLAYER_GUILD, "overloaded")).isEqualTo(1);
    }

    @Test
    void 可能出现的指标组合启动即预建() {
        for (String method : GuildMethods.CLIENT_REQUESTS) {
            for (String result : List.of("ok", "business_error", "internal_error", "overloaded", "bad_request",
                    "unauthenticated")) {
                assertThat(requests(method, result)).isZero();
            }
        }
        assertThat(requests(GuildMethods.CREATE_GUILD, "fault")).isZero();
        for (String method : GuildMethods.FORBIDDEN) {
            assertThat(requests(method, "forbidden")).isZero();
        }
        for (String method : GuildMethods.PLACEHOLDERS) {
            assertThat(requests(method, "unsupported")).isZero();
        }
        assertThat(requests("unrouted", "unsupported")).isZero();
        for (String op : List.of("create", "set_role", "kick", "transfer", "leave", "apply", "cancel", "review", "disband",
                "announcement", "verify_mapping", "score", "upgrade", "asset_finalize", "activity", "trial_settle", "donate",
                "shop", "insert_guard")) {
            assertThat(f.meters.get("xm.guild.tx.deadlocks").tag("op", op).counter().count()).isZero();
            assertThat(f.meters.get("xm.guild.tx.budget.exceeded").tag("op", op).counter().count()).isZero();
            assertThat(f.meters.get("xm.guild.tx.lock.wait.timeouts").tag("op", op).counter().count()).isZero();
            assertThat(f.meters.get("xm.guild.cache.invalidation.failures").tag("op", op).counter().count()).isZero();
        }
        for (String cache : List.of("snapshot", "mapping")) {
            for (String result : List.of("hit", "miss", "fill_skipped", "fill_failed", "error")) {
                assertThat(f.meters.get("xm.guild.cache").tag("cache", cache).tag("result", result).counter()).isNotNull();
            }
        }
        for (String op : List.of("add", "remove", "rebuild")) {
            for (String outcome : List.of("ok", "lock_timeout", "error")) {
                assertThat(f.meters.get("xm.guild.rank.ops").tag("op", op).tag("outcome", outcome).counter().count()).isZero();
            }
        }
        for (String outcome : List.of("ok", "timeout", "error")) {
            assertThat(f.meters.get("xm.guild.online.lookups").tag("outcome", outcome).counter().count()).isZero();
        }
        assertThat(f.meters.get("xm.guild.profile.lookup.failures").counter().count()).isZero();
    }
}
