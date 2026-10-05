package com.game.battle.push;

import com.game.battle.metrics.BattleMetrics;
import com.game.battle.protocol.BattleFrames;
import com.game.battle.protocol.BattleMessageIds;
import com.game.battle.protocol.BattleMessageIds.Notify;
import com.game.battle.room.DirectLink;
import com.game.proto.MessageContent;
import com.google.protobuf.MessageLite;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 房间的下行出口（基线 {@code PushBattleFrame} / {@code PushLobbyAnnouncement}，{@code room.cpp:1378-1430}；battle-node-spec §5.7、§7.7）：
 * 按 {@link PushPolicy} 决定直发 / 回落 gate / 丢弃，并计 {@code xm_battle_pushes_total}（每个收件人每条消息恰好一次）。
 *
 * <ul>
 *   <li>{@link #pushBattleFrame}（139 / 150 / 158 / 161 / 166）：有活直连就直写，否则<b>丢弃</b>，按消息号采样打
 *       {@code metric=battle_frame_dropped_no_direct}（每个号第一次必打，之后每 1024 次一行，{@code room.cpp:124-136}）；
 *       直连是战斗帧的唯一通路，不回落 gate。</li>
 *   <li>{@link #pushLobby}（177 / 143）：有活直连就按序直写；否则一组公告<b>一次</b>交给 {@link LobbyAnnouncer}（一条
 *       {@code GatePush{message_batch}}，177 先于 143，R5）。判定理论上不会得出「丢弃」，万一得出就打 ERROR。</li>
 * </ul>
 * 「活直连」= 槽里有这条连接且 {@link DirectLink#isLive()}（{@code room.cpp:1357-1376}）；调用方传槽里的 link（可为 null）。
 *
 * <p><b>线程</b>：只在逻辑线程上用（采样计数无锁）。
 */
public final class BattleOutbound {

    private static final Logger log = LoggerFactory.getLogger(BattleOutbound.class);

    /** 丢弃日志的采样掩码：第 1、1025、2049 … 次打一行。 */
    private static final long DROP_LOG_MASK = 0x3FF;

    /** 一条大厅公告（消息与它的推送形状帧）。 */
    public record Announcement(Notify message, MessageContent content) {
        public Announcement {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(content, "content");
            if (message.category() != PushCategory.LOBBY_ANNOUNCEMENT) {
                throw new IllegalArgumentException("不是大厅公告: " + message);
            }
        }
    }

    private final BattleMessageIds messageIds;
    private final LobbyAnnouncer lobby;
    private final BattleMetrics metrics;
    private final Map<Notify, Long> droppedByMessage = new EnumMap<>(Notify.class);

    public BattleOutbound(BattleMessageIds messageIds, LobbyAnnouncer lobby, BattleMetrics metrics) {
        this.messageIds = Objects.requireNonNull(messageIds, "messageIds");
        this.lobby = Objects.requireNonNull(lobby, "lobby");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * 构造推送形状的帧 {@code MessageContent{id = 0, message_id, serialized_message}}。同一帧可以写给多条直连
     * （观众版回合帧每回合只构造、序列化一次，§11 N15）。
     */
    public MessageContent frame(Notify notify, MessageLite body) {
        return BattleFrames.push(messageIds.id(notify), body);
    }

    /** 构造一条大厅公告。 */
    public Announcement announcement(Notify notify, MessageLite body) {
        return new Announcement(notify, frame(notify, body));
    }

    /**
     * 推一条战斗帧。
     *
     * @param link 该玩家直连槽里的连接（没有时 null）
     * @return 实际走的路由（{@link PushRoute#DIRECT} 或 {@link PushRoute#DROP}）
     */
    public PushRoute pushBattleFrame(long battleId, long playerId, DirectLink link, Notify notify, MessageContent frame) {
        if (notify.category() != PushCategory.BATTLE_FRAME) {
            throw new IllegalArgumentException("不是战斗帧: " + notify);
        }
        PushRoute route = PushPolicy.decide(PushCategory.BATTLE_FRAME, live(link));
        if (route == PushRoute.DIRECT) {
            link.send(frame);
            metrics.push(PushCategory.BATTLE_FRAME, PushRoute.DIRECT, notify);
            return PushRoute.DIRECT;
        }
        // 战斗帧的判定不会产出 VIA_GATE（PushPolicyTest 钉住）；本出口也没有路由可回落，防御性按丢弃处理
        metrics.push(PushCategory.BATTLE_FRAME, PushRoute.DROP, notify);
        long count = droppedByMessage.merge(notify, 1L, Long::sum);
        if (((count - 1) & DROP_LOG_MASK) == 0) {
            log.info("metric=battle_frame_dropped_no_direct message={} dropped_total={} latest_battle_id={} latest_player_id={}，"
                            + "玩家无活直连，战斗帧不回落 gate（直连就绪后 GetBattleState 补拉）",
                    notify.method(), count, Long.toUnsignedString(battleId), Long.toUnsignedString(playerId));
        }
        return PushRoute.DROP;
    }

    /**
     * 推一组大厅公告（按到达顺序；至少一条）。
     *
     * @param link 该玩家直连槽里的连接（没有时 null）
     * @return 实际走的路由（{@link PushRoute#DIRECT} 或 {@link PushRoute#VIA_GATE}）
     */
    public PushRoute pushLobby(long battleId, long playerId, DirectLink link, List<Announcement> announcements) {
        if (announcements.isEmpty()) {
            throw new IllegalArgumentException("大厅公告至少一条");
        }
        PushRoute route = PushPolicy.decide(PushCategory.LOBBY_ANNOUNCEMENT, live(link));
        switch (route) {
            case DIRECT -> {
                for (Announcement announcement : announcements) {
                    link.send(announcement.content());
                    metrics.push(PushCategory.LOBBY_ANNOUNCEMENT, PushRoute.DIRECT, announcement.message());
                }
            }
            case VIA_GATE -> {
                List<MessageContent> contents = new ArrayList<>(announcements.size());
                for (Announcement announcement : announcements) {
                    contents.add(announcement.content());
                    metrics.push(PushCategory.LOBBY_ANNOUNCEMENT, PushRoute.VIA_GATE, announcement.message());
                }
                try {
                    lobby.announce(playerId, List.copyOf(contents));
                } catch (RuntimeException e) {
                    // 契约是不抛；万一抛了也不能打断房间流程（公告是至多一次的）
                    log.error("大厅公告回落抛出异常（已吞掉） battle_id={} player_id={}", Long.toUnsignedString(battleId),
                            Long.toUnsignedString(playerId), e);
                }
            }
            case DROP -> {
                for (Announcement announcement : announcements) {
                    metrics.push(PushCategory.LOBBY_ANNOUNCEMENT, PushRoute.DROP, announcement.message());
                }
                log.error("battle 大厅公告被判定丢弃（PushPolicy 契约破坏） battle_id={} player_id={}", Long.toUnsignedString(battleId),
                        Long.toUnsignedString(playerId));
            }
        }
        return route;
    }

    private static boolean live(DirectLink link) {
        return link != null && link.isLive();
    }
}
