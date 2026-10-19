package com.game.api;

import com.game.api.proto.SceneInboxNotify;
import com.game.api.proto.SceneInboxNotifyReply;
import java.util.concurrent.CompletableFuture;

/**
 * scene 节点的收件箱在线提醒入口（mmorpg {@code docs/design/player-inbox-service.md} §6.3 的 Java 对应；基线经 Kafka scene-cmd 投
 * {@code PlayerInboxNotifyEvent}）。每个 scene 节点在 {@code game.scene.asset-rpc-port} 上与 {@link SceneAssetOpService} 一并导出，
 * group {@link DubboGroups#SCENE_INBOX}，{@code register = false}；调用方（game-inbox 的提醒与补发）按玩家位置记录 + Redis 节点目录直连，
 * 引用 {@code retries = 0}。
 *
 * <p>契约：
 * <ul>
 *   <li><b>只是提醒</b>：ACCEPTED 只表示投进了场景逻辑线程，玩家不在本节点时 scene 丢弃；真正的条目仍由 scene 落地时 / 被提醒后自己拉。
 *       提醒丢了由落地无条件拉取与在线有界兜底补上；</li>
 *   <li>{@code target_instance_id} 必填：与提供方实例不符 → NOT_HERE；</li>
 *   <li>{@code status = UNSPECIFIED} 与 future 异常完成一律按传输失败处理（调用方按退避重试）。</li>
 * </ul>
 */
public interface SceneInboxService {

    CompletableFuture<SceneInboxNotifyReply> notifyInbox(SceneInboxNotify request);
}
