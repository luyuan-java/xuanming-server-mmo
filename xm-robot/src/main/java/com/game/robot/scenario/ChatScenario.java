package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.chat.ChatChannelType;
import com.game.proto.chat.ChatMessage;
import com.game.proto.chat.PullChatHistoryRequest;
import com.game.proto.chat.PullChatHistoryResponse;
import com.game.proto.chat.SendChatRequest;
import com.game.proto.chat.SendChatResponse;
import com.game.robot.client.GameConnection;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.table.CommonErrorTip;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 聊天端到端（对应 mmorpg robot chat_smoke_scenario.go，两个机器人经 gate → xm-chat）：
 * <ol>
 *   <li>A 发世界频道 "world &lt;nonce&gt;"（带 request_id）→ 受理；</li>
 *   <li>B 拉世界频道最近 20 条 → 恰好看到一次该 nonce，sender = A（服务端用会话身份覆盖 sender），send_time_ms ≠ 0；</li>
 *   <li>A 私聊 B → B 拉私聊（peer = A）看到，sender = A；第三方视角（A 拉 peer = A）回 1005；</li>
 *   <li>A 发 600 字节的世界消息 → 1010（chat 侧的 512 字节闸；整个请求仍 &lt; 1 KB，gate 不会先拒）；</li>
 *   <li>A 用第 1 步的同一个 request_id 重发 → 受理，但 B 拉到的历史里该 nonce 仍只一条（幂等）；</li>
 *   <li>队伍频道 → 1006（v1 不开放）。</li>
 * </ol>
 * gate 对 61 / 28 按缺省 3 次 / 秒 / 消息号限频：相邻请求留间隔。
 */
public final class ChatScenario {

    private static final String SERVICE = "ClientPlayerChat";
    private static final String REF = "PARITY「聊天」行";
    private static final Duration CALL_SPACING = Duration.ofMillis(1100);

    private final PlayerFlow flow;
    private final String accountA;
    private final String accountB;
    private final Duration requestTimeout;
    private final int send;
    private final int pull;

    public ChatScenario(PlayerFlow flow, MessageIdRegistry registry, String accountPrefix, String runTag,
                        Duration requestTimeout) {
        this.flow = flow;
        this.accountA = accountName(accountPrefix, runTag, "a");
        this.accountB = accountName(accountPrefix, runTag, "b");
        this.requestTimeout = requestTimeout;
        this.send = registry.requireId(SERVICE, "SendChat");
        this.pull = registry.requireId(SERVICE, "PullChatHistory");
    }

    public static String accountName(String prefix, String runTag, String suffix) {
        return prefix + "ch" + runTag + "_" + suffix;
    }

    public String accountA() {
        return accountA;
    }

    public CheckReport run() {
        CheckReport report = new CheckReport();
        List<EnteredPlayer> entered = new ArrayList<>();
        try {
            EnteredPlayer a = flow.enter(accountA, new Timings());
            entered.add(a);
            EnteredPlayer b = flow.enter(accountB, new Timings());
            entered.add(b);
            GameConnection ca = a.connection();
            GameConnection cb = b.connection();
            String nonce = UUID.randomUUID().toString().substring(0, 8);
            String requestId = "robot-" + nonce;

            SendChatResponse sent = call(ca, send, world("world " + nonce, requestId), SendChatResponse.parser());
            report.check(sent.getErrorMessage().getId() == 0, "A 发世界频道受理", "tip=" + sent.getErrorMessage().getId(), REF);

            List<ChatMessage> seen = worldMatches(cb, nonce);
            report.check(seen.size() == 1 && seen.get(0).getSenderPlayerId() == a.playerId() && seen.get(0).getSendTimeMs() > 0,
                    "B 拉世界频道恰好看到一次，sender = A（会话覆盖）", seen.size() + " 条", REF);

            SendChatResponse priv = call(ca, send, SendChatRequest.newBuilder().setMessage(ChatMessage.newBuilder()
                    .setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE).setTargetPlayerId(b.playerId())
                    .setContent("private " + nonce)).build(), SendChatResponse.parser());
            report.check(priv.getErrorMessage().getId() == 0, "A 私聊 B 受理", "tip=" + priv.getErrorMessage().getId(), REF);
            PullChatHistoryResponse privHistory = call(cb, pull, PullChatHistoryRequest.newBuilder()
                    .setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE).setPeerPlayerId(a.playerId()).setLimit(20).build(),
                    PullChatHistoryResponse.parser());
            report.check(privHistory.getMessagesList().stream().anyMatch(m -> m.getContent().equals("private " + nonce)
                            && m.getSenderPlayerId() == a.playerId() && m.getTargetPlayerId() == b.playerId()),
                    "B 拉私聊（peer = A）看到，sender = A", privHistory.getMessagesCount() + " 条", REF);
            PullChatHistoryResponse self = call(ca, pull, PullChatHistoryRequest.newBuilder()
                    .setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_PRIVATE).setPeerPlayerId(a.playerId()).build(),
                    PullChatHistoryResponse.parser());
            report.check(self.getErrorMessage().getId() == CommonErrorTip.common_error.kInvalidParameter_VALUE,
                    "私聊 peer 是自己 → 1005", "tip=" + self.getErrorMessage().getId(), REF);

            SendChatResponse tooLong = call(ca, send, world("x".repeat(600), ""), SendChatResponse.parser());
            report.check(tooLong.getErrorMessage().getId() == CommonErrorTip.common_error.kMessageSizeExceeded_VALUE,
                    "600 字节 → 1010", "tip=" + tooLong.getErrorMessage().getId(), REF);

            SendChatResponse again = call(ca, send, world("world " + nonce, requestId), SendChatResponse.parser());
            List<ChatMessage> afterRetry = worldMatches(cb, nonce);
            report.check(again.getErrorMessage().getId() == 0 && afterRetry.size() == 1,
                    "同 request_id 重发受理且不重复写", "tip=" + again.getErrorMessage().getId() + " 条数=" + afterRetry.size(), REF);

            SendChatResponse team = call(ca, send, SendChatRequest.newBuilder().setMessage(ChatMessage.newBuilder()
                    .setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_TEAM).setContent("team")).build(), SendChatResponse.parser());
            report.check(team.getErrorMessage().getId() == CommonErrorTip.common_error.kFeatureUnavailable_VALUE,
                    "队伍频道 → 1006（v1 不开放）", "tip=" + team.getErrorMessage().getId(), REF);
        } catch (RobotException e) {
            report.fail("流程异常", e.getMessage(), REF);
        } finally {
            for (EnteredPlayer player : entered) {
                player.connection().close();
            }
        }
        return report;
    }

    private List<ChatMessage> worldMatches(GameConnection connection, String nonce) throws RobotException {
        PullChatHistoryResponse history = call(connection, pull, PullChatHistoryRequest.newBuilder()
                .setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_WORLD).setLimit(20).build(), PullChatHistoryResponse.parser());
        return history.getMessagesList().stream().filter(m -> m.getContent().equals("world " + nonce)).toList();
    }

    private static SendChatRequest world(String content, String requestId) {
        return SendChatRequest.newBuilder().setRequestId(requestId).setMessage(ChatMessage.newBuilder()
                .setChannel(ChatChannelType.CHAT_CHANNEL_TYPE_WORLD).setContent(content)).build();
    }

    private <T extends Message> T call(GameConnection connection, int messageId, Message body, Parser<T> parser)
            throws RobotException {
        try {
            Thread.sleep(CALL_SPACING.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待被中断", e);
        }
        return connection.call(messageId, body, parser, requestTimeout);
    }
}
