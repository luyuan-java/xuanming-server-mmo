package com.game.battle.push;

import com.game.proto.MessageContent;
import java.util.List;

/**
 * 大厅公告的 gate 回落出口（基线 Kafka gate-cmd {@code PushToPlayerEvent}，{@code room.cpp:66-118}；battle-node-spec §5.7、§7.7）。
 * 只在 {@code PushPolicy.decide(LOBBY_ANNOUNCEMENT, false) == VIA_GATE}（该玩家没有活直连）时由房间调用；有活直连时房间按序直写，不经这里。
 *
 * <p>契约：
 * <ul>
 *   <li>只在逻辑线程上调用；实现必须<b>异步、不阻塞、不抛异常</b>。</li>
 *   <li>{@code contents} 里的多条消息必须<b>按序</b>到达客户端（177 先于 143，R5、O1），所以同一玩家的一组公告一次交过来，
 *       由实现放进一条 {@code GatePush{message_batch}}；不得拆成多次独立推送。</li>
 *   <li>寻址按在线目录里该玩家的<b>当前</b>会话（修基线「备战期间换会话，公告投到旧会话」，§11 N2），不用快照路由；gate 侧玩家栅栏照常生效。</li>
 *   <li>语义至多一次：结局只计数（{@code xm_battle_lobby_push_outcomes_total}），不回传成业务失败。丢失时靠 6.3 scene 推的 144 →
 *       客户端 179 补签兜底。</li>
 * </ul>
 */
@FunctionalInterface
public interface LobbyAnnouncer {

    /**
     * @param contents 推送形状的 {@code MessageContent}（id = 0），按应到达的顺序；至少一条
     */
    void announce(long playerId, List<MessageContent> contents);
}
