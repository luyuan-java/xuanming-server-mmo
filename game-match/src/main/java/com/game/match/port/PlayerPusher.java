package com.game.match.port;

import com.game.discovery.presence.PlayerPushes;
import com.google.protobuf.MessageLite;
import java.util.concurrent.CompletionStage;

/**
 * match 向在线玩家推一条消息的出站口（切磋的 156 邀请与 154 结果；match-spec §6.3「推送」、M24）：{@code PlayerPushes::pushToPlayer} 的窄接口，
 * 抽出来只为可测（{@code PlayerPushes} 是连真 Redis 的 final 类；组件测试用 {@code RecordingPushes}）。177 / 143 由 battle 推，不经 match。
 *
 * <p><b>契约</b>（与 {@code PlayerPushes.pushToPlayer} 相同）：
 * <ul>
 *   <li><b>不阻塞</b>：查在线目录、发布到玩家所在 gate 的频道都是异步的。消息在调用线程上序列化。</li>
 *   <li><b>至多一次</b>：{@code SENT} 只表示已发布到 gate 的频道且有订阅者，不代表客户端收到；{@code OFFLINE} = 玩家不在游戏里；
 *       {@code GATE_UNREACHABLE} = 发布了但没人收（等于丢了）。Redis 故障、在线目录条目损坏时 stage <b>异常完成</b>。</li>
 *   <li>stage 在 Redis 客户端的线程上完成：挂在它上面的回调<b>不得阻塞</b>——要接着读写 Redis、或要等别的 future，就切到有界的
 *       {@code match-push} 执行器上（{@code thenApplyAsync(..., matchPush)}）。</li>
 * </ul>
 * 各调用点对结果的处理不同：156 的 {@code OFFLINE} / {@code GATE_UNREACHABLE} / 异常完成都算「邀请发送失败」（清理记录后回 16004）；
 * 154 的结果只计数，不影响应答。线程安全。
 */
@FunctionalInterface
public interface PlayerPusher {

    /**
     * 推一条消息给一个玩家。
     *
     * @param messageContent 客户端协议的 {@code MessageContent}（{@code message_id} = 156 / 154、{@code id} 填 0、{@code serialized_message} = 推送体）
     */
    CompletionStage<PlayerPushes.Outcome> push(long playerId, MessageLite messageContent);
}
