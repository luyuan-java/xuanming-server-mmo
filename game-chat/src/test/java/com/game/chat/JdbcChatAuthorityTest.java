package com.game.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.game.api.proto.ClientCall;
import com.game.api.proto.SessionContext;
import com.game.chat.metrics.ChatMetrics;
import com.game.chat.service.ChatService;
import com.game.chat.store.JdbcChatStore;
import com.game.common.GameNamespace;
import com.game.common.deadline.Deadline;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.RedisKeys;
import com.game.proto.chat.ChatChannelType;
import com.game.proto.chat.ChatMessage;
import com.game.proto.chat.PullChatHistoryRequest;
import com.game.proto.chat.PullChatHistoryResponse;
import com.game.proto.chat.SendChatRequest;
import com.game.proto.chat.SendChatResponse;
import com.game.table.CommonErrorTip;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 正式SQL/原Session接口回归。H2时钟为固定DB标量接缝，不以它声称TiDB物理锁已验。 */
class JdbcChatAuthorityTest {
    static ChatProperties rules(int rate, int max, long ttl) {
        return new ChatProperties(512, rate, max, Duration.ofSeconds(ttl), 20, 50,
                Duration.ofSeconds(60), Duration.ofMillis(3500), List.of("坏词"));
    }
    static ChatProperties rules() { return rules(5, 200, 604800); }
    static SessionContext actor(String account, long revision) {
        return SessionContext.newBuilder().setPlayerId(7).setAccount(account).setOwnerRevision(revision).build();
    }
    static SendChatRequest request(String id, String text, long peer) {
        return SendChatRequest.newBuilder().setRequestId(id).setMessage(ChatMessage.newBuilder()
                .setSenderPlayerId(7).setContent(text).setTargetPlayerId(peer)
                .setChannel(peer == 0 ? ChatChannelType.CHAT_CHANNEL_TYPE_WORLD : ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE)).build();
    }
    static <T> T result(CompletableFuture<T> future) throws Exception { return future.get(8, TimeUnit.SECONDS); }
    static JdbcChatStore.Sent send(ChatSqlFixture f, SessionContext original, SendChatRequest request) throws Exception {
        return result(f.store.send(original, request, Deadline.after(3500)));
    }
    static long count(ChatSqlFixture f) { return f.sql.queryForObject("SELECT COUNT(*) FROM chat_send_authority", Long.class); }
    static ChatService service(ChatSqlFixture f, ChatProperties rules) {
        return new ChatService(f.store, new ChatMetrics(new SimpleMeterRegistry()), rules,
                new RedisKeys(GameNamespace.of("game")));
    }

    @Test void 原账号精确值缺失修订与冻结在任何聊天写前拒绝() throws Exception {
        try (var f = new ChatSqlFixture(rules())) {
            f.player(7, " A ", 7);
            var original = actor(" A ", 7);
            for (var bad : List.of(actor("A", 7), actor("B", 7), actor(" A ", 6), actor(" A ", 0),
                    actor("", 7), actor(" A ", Long.MIN_VALUE), SessionContext.getDefaultInstance())) {
                assertThrows(Exception.class, () -> send(f, bad, request("same", "hello", 0)));
                assertThrows(Exception.class, () -> result(f.store.pull(bad, 0, 20, Deadline.after(3500))));
            }
            assertEquals(0, count(f));
            f.sql.update("UPDATE player SET trade_listing_id=9 WHERE player_id=7");
            assertThrows(Exception.class, () -> send(f, original, request("same", "hello", 0)));
            assertEquals(0, count(f));
            f.sql.update("UPDATE player SET trade_listing_id=0 WHERE player_id=7");
            assertEquals(new JdbcChatStore.Sent(0, false), send(f, original, request("same", "hello", 0)));
        }
    }

    @Test void 转让及ABA拒旧会话与旧UUID_合法新主保留原角色私聊历史() throws Exception {
        try (var f = new ChatSqlFixture(rules())) {
            f.player(7, " A ", 7);
            var old = actor(" A ", 7);
            var request = request("permanent", "accepted", 99); // peer不存在也符合原私聊角色号合同
            assertEquals(0, send(f, old, request).tip());
            f.sql.update("UPDATE player SET account='B',owner_revision=8 WHERE player_id=7");
            assertThrows(Exception.class, () -> send(f, old, request));
            assertThrows(Exception.class, () -> result(f.store.pull(old, 99, 20, Deadline.after(3500))));
            assertEquals("accepted", result(f.store.pull(actor("B", 8), 99, 20, Deadline.after(3500))).getFirst().getContent());
            assertThrows(Exception.class, () -> send(f, actor("B", 8), request));
            f.sql.update("UPDATE player SET account=' A ',owner_revision=9 WHERE player_id=7");
            assertThrows(Exception.class, () -> send(f, old, request));
            assertThrows(Exception.class, () -> send(f, actor(" A ", 9), request));
            assertEquals(1, result(f.store.pull(actor(" A ", 9), 99, 20, Deadline.after(3500))).size());
            assertEquals(0, send(f, actor(" A ", 9), request("new", "new owner", 99)).tip());
            assertEquals(2, count(f));
        }
    }

    @Test void 成功UUID永久原件重放不重复_改正文目标或账号均拒绝() throws Exception {
        try (var f = new ChatSqlFixture(rules())) {
            f.player(7, "A", 7);
            var original = actor("A", 7); var request = request("id", "hello", 99);
            assertFalse(send(f, original, request).duplicate());
            f.clock.addAndGet(120_000); // 超过v1幂等60s，SQL成功原件仍永久
            assertTrue(send(f, original, request).duplicate());
            assertThrows(Exception.class, () -> send(f, original, request("id", "changed", 99)));
            assertThrows(Exception.class, () -> send(f, original, request("id", "hello", 100)));
            assertEquals(1, count(f));
            assertEquals(0, f.sql.queryForObject("SELECT result_tip FROM chat_send_authority", Integer.class));
        }
    }

    @Test void 限速拒绝不占UUID_下一固定秒原号可重试_已接受重放不耗额度() throws Exception {
        try (var f = new ChatSqlFixture(rules(1, 200, 604800))) {
            f.player(7, "A", 7); var original = actor("A", 7);
            var accepted = request("a", "first", 0); var retry = request("b", "second", 0);
            assertEquals(0, send(f, original, accepted).tip());
            assertTrue(send(f, original, accepted).duplicate());
            assertEquals(CommonErrorTip.common_error.kRateLimitExceeded_VALUE, send(f, original, retry).tip());
            assertEquals(1, count(f));
            f.clock.addAndGet(1000);
            assertEquals(new JdbcChatStore.Sent(0, false), send(f, original, retry));
            assertEquals(2, count(f));
        }
    }

    @Test void 双副本同UUID并发只提交一次_无请求号仍是两次独立发言() throws Exception {
        try (var f = new ChatSqlFixture(rules())) {
            f.player(7, "A", 7); var original = actor("A", 7); var request = request("id", "hello", 0);
            var first = f.store.send(original, request, Deadline.after(3500));
            var second = new JdbcChatStore(f.source, f.authority, rules()).send(original, request, Deadline.after(3500));
            assertNotEquals(result(first).duplicate(), result(second).duplicate());
            assertEquals(1, count(f));
            assertEquals(0, send(f, original, request("", "hello", 0)).tip());
            assertEquals(0, send(f, original, request("", "hello", 0)).tip());
            assertEquals(3, count(f));
        }
    }

    @Test void SQL窗口按最大条数及连续TTL截断_旧窗不会被新消息复活() throws Exception {
        try (var f = new ChatSqlFixture(rules(100, 3, 10))) {
            f.player(7, "A", 7); var original = actor("A", 7);
            for (int i = 0; i < 4; i++) { send(f, original, request("m" + i, "m" + i, 0)); f.clock.addAndGet(1000); }
            assertEquals(List.of("m3", "m2"), result(f.store.pull(original, 0, 2, Deadline.after(3500)))
                    .stream().map(ChatMessage::getContent).toList());
            assertEquals(3, result(f.store.pull(original, 0, 50, Deadline.after(3500))).size());
            f.clock.addAndGet(10_000);
            assertTrue(result(f.store.pull(original, 0, 50, Deadline.after(3500))).isEmpty());
            send(f, original, request("new", "new", 0));
            assertEquals(List.of("new"), result(f.store.pull(original, 0, 50, Deadline.after(3500)))
                    .stream().map(ChatMessage::getContent).toList());
            assertEquals(5, count(f)); // 只裁显示窗口，永久成功原件不删除
        }
    }

    @Test void 对端删除不丢己方历史_第三者不能读另一对私聊() throws Exception {
        try (var f = new ChatSqlFixture(rules())) {
            f.player(7, "A", 7); f.player(99, "P", 1); f.player(100, "Q", 1);
            send(f, actor("A", 7), request("p", "private", 99));
            var peer = SessionContext.newBuilder().setPlayerId(99).setAccount("P").setOwnerRevision(1).build();
            assertEquals(1, result(f.store.pull(peer, 7, 20, Deadline.after(3500))).size());
            var third = SessionContext.newBuilder().setPlayerId(100).setAccount("Q").setOwnerRevision(1).build();
            assertTrue(result(f.store.pull(third, 99, 20, Deadline.after(3500))).isEmpty());
            f.sql.update("DELETE FROM player WHERE player_id=99");
            assertEquals(1, result(f.store.pull(actor("A", 7), 99, 20, Deadline.after(3500))).size());
        }
    }

    @Test void 单轮Pull硬界200_历史容量可更大_配置201拒绝且200合法() throws Exception {
        var base = rules();
        assertThrows(IllegalArgumentException.class, () -> new ChatProperties(512, 1000, 1000, base.historyTtl(),
                20, 201, base.requestIdTtl(), base.requestBudget(), List.of()));
        var rules = new ChatProperties(512, 1000, 1000, base.historyTtl(), 20, 200,
                base.requestIdTtl(), base.requestBudget(), List.of());
        assertEquals(1000, rules.historyMaxEntries()); assertEquals(200, rules.historyMaxLimit());
        try (var f = new ChatSqlFixture(rules)) {
            f.player(7, "A", 7);
            for (int i = 0; i < 201; i++) {
                var request = request("row" + i, "message" + i, 0);
                long time = f.clock.get() - i;
                f.sql.update("INSERT INTO chat_send_authority VALUES(?,?,?,?,?,?,?,?,1,?,0)", 7,
                        request.getRequestId().getBytes(StandardCharsets.UTF_8), "A".getBytes(StandardCharsets.UTF_8), 7,
                        java.security.MessageDigest.getInstance("SHA-256").digest(request.toByteArray()), request.toByteArray(),
                        request.getMessage().toBuilder().setSendTimeMs(time).build().toByteArray(),
                        "world:v2".getBytes(StandardCharsets.UTF_8), time);
            }
            assertEquals(201, count(f));
            var direct = result(f.store.pull(actor("A", 7), 0, Integer.MAX_VALUE, Deadline.after(3500)));
            assertEquals(200, direct.size()); assertEquals("message0", direct.getFirst().getContent());
            assertEquals("message199", direct.getLast().getContent());
            var response = service(f, rules).pull(actor("A", 7), PullChatHistoryRequest.newBuilder()
                    .setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_WORLD).setLimit(-1).build(), 3500).toCompletableFuture().get();
            assertEquals(200, response.getMessagesCount());
        }
    }

    @Test void 同P等待期间换主_迟到Send与Pull均拒绝_不占UUID或限速() throws Exception {
        try (var f = new ChatSqlFixture(rules()); var connection = f.source.getConnection()) {
            f.player(7, "A", 7);
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT player_id FROM player WHERE player_id=7 FOR UPDATE")) {
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            var pending = f.store.send(actor("A", 7), request("id", "old", 0), Deadline.after(3500));
            var pulling = f.store.pull(actor("A", 7), 0, 20, Deadline.after(3500));
            try (var statement = connection.prepareStatement("UPDATE player SET account='B',owner_revision=8 WHERE player_id=7")) {
                assertEquals(1, statement.executeUpdate());
            }
            connection.commit();
            assertThrows(Exception.class, () -> result(pending));
            assertThrows(Exception.class, () -> result(pulling));
            assertEquals(0, count(f));
            assertEquals(0, send(f, actor("B", 8), request("id", "new", 0)).tip());
        }
    }

    @Test void 缺策略分叉及合区meta冻结都拒绝_不从缓存补真相() throws Exception {
        try (var f = new ChatSqlFixture(rules())) {
            f.player(7, "A", 7);
            var different = new JdbcChatStore(f.source, f.authority, rules(6, 200, 604800));
            assertThrows(SQLException.class, different::initializePolicy);
            assertThrows(Exception.class, () -> result(different.send(actor("A", 7), request("id", "bad", 0), Deadline.after(3500))));
            f.sql.update("UPDATE route_meta SET writable=0 WHERE id=1");
            assertThrows(Exception.class, () -> send(f, actor("A", 7), request("id", "bad", 0)));
            assertEquals(0, count(f));
            f.sql.update("UPDATE route_meta SET writable=1 WHERE id=1");
            send(f, actor("A", 7), request("id", "ok", 0));
            f.sql.update("DELETE FROM chat_policy");
            assertThrows(SQLException.class, f.store::initializePolicy);
            assertEquals(0, f.sql.queryForObject("SELECT COUNT(*) FROM chat_policy", Integer.class));
        }
    }

    @Test void 正式Dispatcher传完整原Session_覆盖sender时间并统一纯校验屏蔽() throws Exception {
        try (var f = new ChatSqlFixture(rules())) {
            f.player(7, " A ", 7); var original = actor(" A ", 7);
            var service = service(f, rules()); var registry = MessageIdRegistry.loadFromClasspath();
            var dispatcher = new ChatDispatcher(registry, service, new ChatMetrics(new SimpleMeterRegistry()), 3500);
            var raw = request("id", "  坏词 hello  ", 0).toBuilder().setMessage(request("id", "  坏词 hello  ", 0)
                    .getMessage().toBuilder().setSenderPlayerId(999).setSendTimeMs(123)).build();
            var call = ClientCall.newBuilder().setSession(original).setMessageId(registry.requireId("ClientPlayerChat", "SendChat"))
                    .setBody(raw.toByteString()).build();
            assertEquals(0, SendChatResponse.parseFrom(result(dispatcher.dispatch(call)).getBody()).getErrorMessage().getId());
            var pull = PullChatHistoryRequest.newBuilder().setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_WORLD).build();
            var reply = result(dispatcher.dispatch(ClientCall.newBuilder().setSession(original)
                    .setMessageId(registry.requireId("ClientPlayerChat", "PullChatHistory")).setBody(pull.toByteString()).build()));
            var message = PullChatHistoryResponse.parseFrom(reply.getBody()).getMessages(0);
            assertEquals(7, message.getSenderPlayerId()); assertEquals(f.clock.get(), message.getSendTimeMs());
            assertEquals("** hello", message.getContent());
            assertEquals(CommonErrorTip.common_error.kInvalidParameter_VALUE,
                    service.send(7, raw, 3500).toCompletableFuture().get().getErrorMessage().getId());
            assertEquals(CommonErrorTip.common_error.kInvalidParameter_VALUE,
                    service.pull(7, pull, 3500).toCompletableFuture().get().getErrorMessage().getId());
            assertEquals(CommonErrorTip.common_error.kInvalidParameter_VALUE, SendChatResponse.parseFrom(result(dispatcher.dispatch(
                    call.toBuilder().setSession(original.toBuilder().clearOwnerRevision()).build())).getBody()).getErrorMessage().getId());
            assertEquals(1, count(f));
            assertArrayEquals(" A ".getBytes(StandardCharsets.UTF_8), f.sql.queryForObject("SELECT owner_account FROM chat_send_authority", byte[].class));
        }
    }

    @Test void 正式索引缺失及原件损坏明确失败_不跳条不降级缓存() throws Exception {
        try (var f = new ChatSqlFixture(rules())) {
            f.player(7, "A", 7); send(f, actor("A", 7), request("id", "hello", 0));
            f.sql.update("UPDATE chat_send_authority SET message_payload=?", new byte[]{-1});
            assertThrows(Exception.class, () -> result(f.store.pull(actor("A", 7), 0, 20, Deadline.after(3500))));
            f.sql.execute("DROP INDEX idx_chat_stream");
            try (var connection = f.source.getConnection()) { assertThrows(SQLException.class, () -> ChatSqlConfiguration.verifySchema(connection)); }
        }
    }

    @Test void 过期预算和服务关闭不提交聊天_无后台双写() throws Exception {
        try (var f = new ChatSqlFixture(rules())) {
            f.player(7, "A", 7);
            assertThrows(Exception.class, () -> result(f.store.send(actor("A", 7), request("id", "hello", 0), Deadline.after(-1))));
            f.authority.close();
            assertThrows(Exception.class, () -> send(f, actor("A", 7), request("id", "hello", 0)));
            assertEquals(0, count(f));
        }
    }
}
