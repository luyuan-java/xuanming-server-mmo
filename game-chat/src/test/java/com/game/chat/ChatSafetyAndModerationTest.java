package com.game.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.game.api.proto.ClientCall;
import com.game.api.proto.SessionContext;
import com.game.chat.metrics.ChatMetrics;
import com.game.chat.service.ChatService;
import com.game.chat.store.JdbcChatStore;
import com.game.common.GameNamespace;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.RedisKeys;
import com.game.proto.chat.ChatChannelType;
import com.game.proto.chat.ChatMessage;
import com.game.proto.chat.ChatRetractedNotify;
import com.game.proto.chat.PullChatHistoryRequest;
import com.game.proto.chat.PullChatHistoryResponse;
import com.game.proto.chat.ReportChatRequest;
import com.game.proto.chat.ReportChatResponse;
import com.game.proto.chat.RetractWorldChatRequest;
import com.game.proto.chat.RetractWorldChatResponse;
import com.game.proto.chat.SendChatRequest;
import com.game.proto.chat.SendChatResponse;
import com.game.proto.content_guard.CheckTextResponse;
import com.game.proto.content_guard.TextCategory;
import com.game.proto.content_guard.TextScene;
import com.game.proto.content_guard.TextVerdict;
import com.game.table.ChatErrorTip;
import com.game.table.CommonErrorTip;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 聊天举报(433)、世界撤回(434/435)、内容安全接入、异步复检撤回与反刷屏门槛回归。 */
class ChatSafetyAndModerationTest {

    private static final RedisKeys KEYS = new RedisKeys(GameNamespace.of("game"));
    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();

    private static SessionContext session(long playerId, String account) {
        return SessionContext.newBuilder().setPlayerId(playerId).setAccount(account).setOwnerRevision(1).build();
    }

    private static SendChatRequest worldReq(String reqId, String content) {
        return SendChatRequest.newBuilder().setRequestId(reqId)
                .setMessage(ChatMessage.newBuilder()
                        .setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_WORLD).setContent(content)).build();
    }

    private static SendChatRequest privateReq(String reqId, long target, String content) {
        return SendChatRequest.newBuilder().setRequestId(reqId)
                .setMessage(ChatMessage.newBuilder()
                        .setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE)
                        .setTargetPlayerId(target).setContent(content)).build();
    }

    private static ChatProperties safetyProps(int worldCooldownMs, int worldMinLevel,
            int spamWindowMs, int spamMaxSenders, int spamMaxPerSender,
            int newAgeHours, int newDailyPrivateLimit) {
        return new ChatProperties(512, 20, 200, Duration.ofDays(7), 20, 50,
                Duration.ofSeconds(60), Duration.ofMillis(3500), List.of(),
                worldCooldownMs, worldMinLevel, spamWindowMs, spamMaxSenders, spamMaxPerSender,
                newAgeHours, newDailyPrivateLimit, false);
    }

    @Test
    void 举报与世界撤回走正式Dispatcher并保持追加只读与幂等() throws Exception {
        var props = safetyProps(0, 0, 0, 0, 0, 0, 0);
        try (var f = new ChatSqlFixture(props)) {
            f.player(10, "A", 1);
            f.player(20, "B", 1);
            f.player(30, "C", 1);
            var s10 = session(10, "A");
            var s20 = session(20, "B");
            var s30 = session(30, "C");

            List<ChatMessage> published = new ArrayList<>();
            List<ChatRetractedNotify> retracted = new ArrayList<>();
            ChatService.WorldPublisher publisher = new ChatService.WorldPublisher() {
                @Override public void publishMessage(ChatMessage m) { published.add(m); }
                @Override public void publishRetracted(ChatRetractedNotify n) { retracted.add(n); }
            };
            var metrics = new ChatMetrics(new SimpleMeterRegistry());
            try (var service = new ChatService(f.store, metrics, props, KEYS, null, publisher)) {
                var dispatcher = new ChatDispatcher(REGISTRY, service, metrics, 3500);
                assertEquals(4, dispatcher.routedMessageIds().size());

                // 发送一条世界消息与一条 10->20 私聊消息
                long worldTs = f.clock.get();
                var sendWorld = SendChatResponse.parseFrom(dispatcher.dispatch(ClientCall.newBuilder()
                        .setSession(s10).setMessageId(REGISTRY.requireId("ClientPlayerChat", "SendChat"))
                        .setBody(worldReq("w1", "world hello").toByteString()).build()).get(5, TimeUnit.SECONDS).getBody());
                assertEquals(0, sendWorld.getErrorMessage().getId());
                assertEquals(1, published.size());
                assertEquals(worldTs, published.getFirst().getSendTimeMs());

                f.clock.addAndGet(500);
                long privTs = f.clock.get();
                var sendPriv = SendChatResponse.parseFrom(dispatcher.dispatch(ClientCall.newBuilder()
                        .setSession(s10).setMessageId(REGISTRY.requireId("ClientPlayerChat", "SendChat"))
                        .setBody(privateReq("p1", 20, "secret msg").toByteString()).build()).get(5, TimeUnit.SECONDS).getBody());
                assertEquals(0, sendPriv.getErrorMessage().getId());

                // 20 举报世界消息：成功且重复举报幂等
                int reportId = REGISTRY.requireId("ClientPlayerChat", "ReportChat");
                var repWorld = ReportChatRequest.newBuilder()
                        .setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_WORLD)
                        .setTargetSenderPlayerId(10).setSendTimeMs(worldTs).setReason(1).setDetail("  ad  ").build();
                assertEquals(0, ReportChatResponse.parseFrom(dispatcher.dispatch(ClientCall.newBuilder()
                        .setSession(s20).setMessageId(reportId).setBody(repWorld.toByteString()).build())
                        .get(5, TimeUnit.SECONDS).getBody()).getErrorMessage().getId());
                assertEquals(0, ReportChatResponse.parseFrom(dispatcher.dispatch(ClientCall.newBuilder()
                        .setSession(s20).setMessageId(reportId).setBody(repWorld.toByteString()).build())
                        .get(5, TimeUnit.SECONDS).getBody()).getErrorMessage().getId());
                assertEquals(1, f.sql.queryForObject("SELECT COUNT(*) FROM chat_report", Integer.class));

                // 20 举报 10->20 私聊成功；30（第三者）举报 10->20 私聊回消息不存在(18006)
                var repPriv = ReportChatRequest.newBuilder()
                        .setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE)
                        .setTargetSenderPlayerId(10).setSendTimeMs(privTs).setReason(2).build();
                assertEquals(0, ReportChatResponse.parseFrom(dispatcher.dispatch(ClientCall.newBuilder()
                        .setSession(s20).setMessageId(reportId).setBody(repPriv.toByteString()).build())
                        .get(5, TimeUnit.SECONDS).getBody()).getErrorMessage().getId());
                assertEquals(ChatErrorTip.chat_error.kChatMessageNotFound_VALUE,
                        ReportChatResponse.parseFrom(dispatcher.dispatch(ClientCall.newBuilder()
                                .setSession(s30).setMessageId(reportId).setBody(repPriv.toByteString()).build())
                                .get(5, TimeUnit.SECONDS).getBody()).getErrorMessage().getId());

                // 非法举报原因(0/5)或说明超长(>200字节)回 1005
                assertEquals(CommonErrorTip.common_error.kInvalidParameter_VALUE,
                        ReportChatResponse.parseFrom(dispatcher.dispatch(ClientCall.newBuilder()
                                .setSession(s20).setMessageId(reportId)
                                .setBody(repWorld.toBuilder().setReason(5).build().toByteString()).build())
                                .get(5, TimeUnit.SECONDS).getBody()).getErrorMessage().getId());
                assertEquals(CommonErrorTip.common_error.kInvalidParameter_VALUE,
                        ReportChatResponse.parseFrom(dispatcher.dispatch(ClientCall.newBuilder()
                                .setSession(s20).setMessageId(reportId)
                                .setBody(repWorld.toBuilder().setDetail("x".repeat(201)).build().toByteString()).build())
                                .get(5, TimeUnit.SECONDS).getBody()).getErrorMessage().getId());

                // 撤回世界消息：首次 retracted=true 并广播；二次幂等 retracted=false 不重复广播；不存在回 18006
                int retractId = REGISTRY.requireId("ClientPlayerChat", "RetractWorldChat");
                var retractReq = RetractWorldChatRequest.newBuilder()
                        .setSenderPlayerId(10).setSendTimeMs(worldTs).setReason("ops_remove").build();
                var r1 = RetractWorldChatResponse.parseFrom(dispatcher.dispatch(ClientCall.newBuilder()
                        .setMessageId(retractId).setBody(retractReq.toByteString()).build())
                        .get(5, TimeUnit.SECONDS).getBody());
                assertEquals(0, r1.getErrorMessage().getId());
                assertTrue(r1.getRetracted());
                assertEquals(1, retracted.size());
                assertEquals("ops_remove", retracted.getFirst().getReason());

                var r2 = RetractWorldChatResponse.parseFrom(dispatcher.dispatch(ClientCall.newBuilder()
                        .setMessageId(retractId).setBody(retractReq.toByteString()).build())
                        .get(5, TimeUnit.SECONDS).getBody());
                assertEquals(0, r2.getErrorMessage().getId());
                assertFalse(r2.getRetracted());
                assertEquals(1, retracted.size());

                var rMissing = RetractWorldChatResponse.parseFrom(dispatcher.dispatch(ClientCall.newBuilder()
                        .setMessageId(retractId).setBody(retractReq.toBuilder().setSendTimeMs(999999).build().toByteString()).build())
                        .get(5, TimeUnit.SECONDS).getBody());
                assertEquals(ChatErrorTip.chat_error.kChatMessageNotFound_VALUE, rMissing.getErrorMessage().getId());

                // 拉取世界历史不再返回已撤回条目，但原件表行仍在（追加只读）
                var history = PullChatHistoryResponse.parseFrom(dispatcher.dispatch(ClientCall.newBuilder()
                        .setSession(s20).setMessageId(REGISTRY.requireId("ClientPlayerChat", "PullChatHistory"))
                        .setBody(PullChatHistoryRequest.newBuilder()
                                .setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_WORLD).build().toByteString()).build())
                        .get(5, TimeUnit.SECONDS).getBody());
                assertEquals(0, history.getMessagesCount());
                assertEquals(2, f.sql.queryForObject("SELECT COUNT(*) FROM chat_send_authority", Integer.class));
            }
        }
    }

    @Test
    void 内容安全打码拒绝不可用重放幂等与世界异步复检撤回() throws Exception {
        var props = safetyProps(0, 0, 0, 0, 0, 0, 0);
        try (var f = new ChatSqlFixture(props)) {
            f.player(10, "A", 1);
            var s10 = session(10, "A");
            var meterRegistry = new SimpleMeterRegistry();
            var metrics = new ChatMetrics(meterRegistry);

            AtomicReference<CheckTextResponse> nextCheck = new AtomicReference<>();
            AtomicReference<CheckTextResponse> nextPlatform = new AtomicReference<>();
            List<ChatRetractedNotify> retracted = new ArrayList<>();

            ChatService.TextGuard guard = new ChatService.TextGuard() {
                @Override
                public CheckTextResponse check(TextScene scene, long playerId, String text, boolean allowAsyncPlatform) {
                    return nextCheck.get();
                }

                @Override
                public CheckTextResponse checkPlatform(TextScene scene, long playerId, String text) {
                    return nextPlatform.get();
                }
            };
            ChatService.WorldPublisher publisher = new ChatService.WorldPublisher() {
                @Override public void publishRetracted(ChatRetractedNotify n) { retracted.add(n); }
            };

            try (var service = new ChatService(f.store, metrics, props, KEYS, guard, publisher, Runnable::run)) {
                // 1. 广告/联系方式拒绝 -> 18005
                nextCheck.set(CheckTextResponse.newBuilder()
                        .setVerdict(TextVerdict.TEXT_VERDICT_REJECT).setCategory(TextCategory.TEXT_CATEGORY_AD).build());
                assertEquals(ChatErrorTip.chat_error.kChatContactRejected_VALUE,
                        service.send(s10, worldReq("r-ad", "加微123"), 3500).toCompletableFuture().get().getErrorMessage().getId());

                // 2. 涉政拒绝 -> 18000
                nextCheck.set(CheckTextResponse.newBuilder()
                        .setVerdict(TextVerdict.TEXT_VERDICT_REJECT).setCategory(TextCategory.TEXT_CATEGORY_POLITICS).build());
                assertEquals(ChatErrorTip.chat_error.kChatContentRejected_VALUE,
                        service.send(s10, worldReq("r-pol", "bad"), 3500).toCompletableFuture().get().getErrorMessage().getId());

                // 3. 不可用 fail-closed -> 18001（并被 Dispatcher 计为 INTERNAL_ERROR）
                nextCheck.set(null);
                var unavailResp = service.send(s10, worldReq("r-down", "hello"), 3500).toCompletableFuture().get();
                assertEquals(ChatErrorTip.chat_error.kChatContentCheckUnavailable_VALUE, unavailResp.getErrorMessage().getId());
                assertEquals(ChatMetrics.RequestResult.INTERNAL_ERROR, ChatDispatcher.resultOf(unavailResp));

                // 4. 打码通过 -> 落库打码正文，重放同 request_id 即使后续词库变拒也幂等成功
                nextCheck.set(CheckTextResponse.newBuilder()
                        .setVerdict(TextVerdict.TEXT_VERDICT_PASS).build());
                assertEquals(0, service.send(s10, worldReq("r-replay", "original"), 3500).toCompletableFuture().get().getErrorMessage().getId());
                nextCheck.set(CheckTextResponse.newBuilder()
                        .setVerdict(TextVerdict.TEXT_VERDICT_REJECT).setCategory(TextCategory.TEXT_CATEGORY_POLITICS).build());
                assertEquals(0, service.send(s10, worldReq("r-replay", "original"), 3500).toCompletableFuture().get().getErrorMessage().getId());
                assertEquals(1.0, meterRegistry.get("game.chat.sends").tag("channel", "world").tag("outcome", "duplicate").counter().count());

                f.clock.addAndGet(1000);
                nextCheck.set(CheckTextResponse.newBuilder()
                        .setVerdict(TextVerdict.TEXT_VERDICT_MASKED).setMaskedText("hi ***").build());
                assertEquals(0, service.send(s10, worldReq("r-mask", "hi bad"), 3500).toCompletableFuture().get().getErrorMessage().getId());
                var hist = service.pull(s10, PullChatHistoryRequest.newBuilder()
                        .setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_WORLD).build(), 3500).toCompletableFuture().get();
                assertEquals("hi ***", hist.getMessages(0).getContent());

                // 5. 世界频道短超时放行 + 异步外部平台复检判拒 -> 自动写撤回表并广播撤回通知、拉取不可见
                f.clock.addAndGet(1000);
                long pendingTs = f.clock.get();
                nextCheck.set(CheckTextResponse.newBuilder()
                        .setVerdict(TextVerdict.TEXT_VERDICT_PASS).setPendingPlatformReview(true).build());
                nextPlatform.set(CheckTextResponse.newBuilder()
                        .setVerdict(TextVerdict.TEXT_VERDICT_REJECT).setCategory(TextCategory.TEXT_CATEGORY_PORN).build());
                assertEquals(0, service.send(s10, worldReq("r-async", "sneaky text"), 3500).toCompletableFuture().get().getErrorMessage().getId());
                assertEquals(1, retracted.size());
                assertEquals(pendingTs, retracted.getFirst().getSendTimeMs());
                assertEquals("platform_async_reject", retracted.getFirst().getReason());
                var histAfter = service.pull(s10, PullChatHistoryRequest.newBuilder()
                        .setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_WORLD).build(), 3500).toCompletableFuture().get();
                assertTrue(histAfter.getMessagesList().stream().noneMatch(m -> m.getSendTimeMs() == pendingTs));
            }
        }
    }

    @Test
    void 世界冷却_最低等级_跨副本重复刷屏与新号每日私聊上限() throws Exception {
        var props = safetyProps(
                5_000,   // 世界冷却 5s
                10,      // 世界最低等级 10
                60_000,  // 重复刷屏窗 60s
                2,       // 同窗最多 2 名其他发送者
                2,       // 同窗单人最多 2 次
                24,      // 24h 内算新号
                2        // 新号每日最多私聊 2 个不同目标
        );
        try (var f = new ChatSqlFixture(props)) {
            f.clock.set(1_700_000_000_000L);
            f.player(1, "A", 1);
            f.player(2, "B", 1);
            f.player(3, "C", 1);
            f.player(4, "D", 1);
            long baseMs = f.clock.get();
            Map<Long, JdbcChatStore.PlayerProfile> profiles = Map.of(
                    1L, new JdbcChatStore.PlayerProfile(5, baseMs - 1_000),            // 低等级新号
                    2L, new JdbcChatStore.PlayerProfile(20, baseMs - 1_000),           // 达标新号
                    3L, new JdbcChatStore.PlayerProfile(20, baseMs - 48 * 3_600_000L), // 老号
                    4L, new JdbcChatStore.PlayerProfile(20, baseMs - 48 * 3_600_000L)
            );
            var store = new JdbcChatStore(f.source, f.authority, props, profiles::get);
            var meterRegistry = new SimpleMeterRegistry();
            var metrics = new ChatMetrics(meterRegistry);
            try (var service = new ChatService(store, metrics, props, KEYS)) {
                // 1. 等级不足 -> 18002
                assertEquals(ChatErrorTip.chat_error.kChatLevelTooLow_VALUE,
                        service.send(session(1, "A"), worldReq("lvl1", "hi world"), 3500)
                                .toCompletableFuture().get().getErrorMessage().getId());

                // 2. 世界冷却：达标账号 2 首条成功，1s 后再发世界被拒(1008 world_cooldown)，但发私聊不受世界冷却影响
                assertEquals(0, service.send(session(2, "B"), worldReq("cd1", "unique msg one"), 3500)
                        .toCompletableFuture().get().getErrorMessage().getId());
                f.clock.addAndGet(1_000);
                assertEquals(CommonErrorTip.common_error.kRateLimitExceeded_VALUE,
                        service.send(session(2, "B"), worldReq("cd2", "unique msg two"), 3500)
                                .toCompletableFuture().get().getErrorMessage().getId());
                assertEquals(1.0, meterRegistry.get("game.chat.sends")
                        .tag("channel", "world").tag("outcome", "world_cooldown").counter().count());

                // 3. 新号每日私聊不同目标上限（2 人）：发给 1、3 及再次发给 1 都允许，第 3 个不同目标 4 被拒(18004)
                assertEquals(0, service.send(session(2, "B"), privateReq("p1", 1, "hello one"), 3500)
                        .toCompletableFuture().get().getErrorMessage().getId());
                assertEquals(0, service.send(session(2, "B"), privateReq("p2", 3, "hello three"), 3500)
                        .toCompletableFuture().get().getErrorMessage().getId());
                assertEquals(0, service.send(session(2, "B"), privateReq("p3", 1, "again one"), 3500)
                        .toCompletableFuture().get().getErrorMessage().getId());
                assertEquals(ChatErrorTip.chat_error.kChatPrivateTargetLimit_VALUE,
                        service.send(session(2, "B"), privateReq("p4", 4, "hello four"), 3500)
                                .toCompletableFuture().get().getErrorMessage().getId());
                // 老号(3)不受新号私聊上限约束
                assertEquals(0, service.send(session(3, "C"), privateReq("old1", 1, "old to 1"), 3500)
                        .toCompletableFuture().get().getErrorMessage().getId());
                assertEquals(0, service.send(session(3, "C"), privateReq("old2", 2, "old to 2"), 3500)
                        .toCompletableFuture().get().getErrorMessage().getId());
                assertEquals(0, service.send(session(3, "C"), privateReq("old3", 4, "old to 4"), 3500)
                        .toCompletableFuture().get().getErrorMessage().getId());

                // 4. 跨副本重复刷屏：忽略全角/大小写/标点差异；同人超 2 次拒(18003)，不同发送者满 2 人后第 3 人拒(18003)
                assertEquals(0, service.send(session(3, "C"), worldReq("sp1", "ＡＢＣ-123!"), 3500)
                        .toCompletableFuture().get().getErrorMessage().getId());
                f.clock.addAndGet(6_000);
                assertEquals(0, service.send(session(3, "C"), worldReq("sp2", "abc 123?"), 3500)
                        .toCompletableFuture().get().getErrorMessage().getId());
                f.clock.addAndGet(6_000);
                assertEquals(ChatErrorTip.chat_error.kChatDuplicateSpam_VALUE,
                        service.send(session(3, "C"), worldReq("sp3", "AbC，123"), 3500)
                                .toCompletableFuture().get().getErrorMessage().getId());
                // 另一账号 4 发一次成功（others=1 < 2），再由账号 2 发被拒（others={3,4} size=2 >= 2）
                assertEquals(0, service.send(session(4, "D"), worldReq("sp4", "abc123"), 3500)
                        .toCompletableFuture().get().getErrorMessage().getId());
                assertEquals(ChatErrorTip.chat_error.kChatDuplicateSpam_VALUE,
                        service.send(session(2, "B"), worldReq("sp5", "ABC 123"), 3500)
                                .toCompletableFuture().get().getErrorMessage().getId());
            }
        }
    }

    @Test
    void 配置绑定新阈值并校验边界() {
        ChatProperties bound = new Binder(new MapConfigurationPropertySource(Map.of(
                "game.chat.world-channel-cooldown-ms", "5000",
                "game.chat.world-min-level", "15",
                "game.chat.duplicate-spam-window-ms", "30000",
                "game.chat.duplicate-spam-max-senders", "3",
                "game.chat.duplicate-spam-max-per-sender", "2",
                "game.chat.new-account-age-hours", "24",
                "game.chat.new-account-daily-private-limit", "5",
                "game.chat.content-guard-enabled", "true")))
                .bind("game.chat", Bindable.of(ChatProperties.class)).get();
        assertEquals(5000, bound.worldChannelCooldownMs());
        assertEquals(15, bound.worldMinLevel());
        assertEquals(30000, bound.duplicateSpamWindowMs());
        assertEquals(3, bound.duplicateSpamMaxSenders());
        assertEquals(2, bound.duplicateSpamMaxPerSender());
        assertEquals(24, bound.newAccountAgeHours());
        assertEquals(5, bound.newAccountDailyPrivateLimit());
        assertTrue(bound.contentGuardEnabled());

        assertThrows(Exception.class, () -> new Binder(new MapConfigurationPropertySource(Map.of(
                "game.chat.world-min-level", "1001")))
                .bind("game.chat", Bindable.of(ChatProperties.class)));
    }
}
