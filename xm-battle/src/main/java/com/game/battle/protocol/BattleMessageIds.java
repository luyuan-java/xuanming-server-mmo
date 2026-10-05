package com.game.battle.protocol;

import com.game.battle.push.PushCategory;
import com.game.contract.MessageIdRegistry;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * battle 直连面与房间用到的消息号（battle-node-spec §5.1、§7.4）：启动时按「服务裸名 {@value #SERVICE} + 方法名」从契约
 * {@code message_id.txt} 解析（号会被生成器复用空洞，不许写死），缺任何一个即拒绝启动（写法同 {@code SceneMessageIds}）。
 *
 * <p>4 个上行号就是直连面的白名单（§3.5 第 3 道闸）；7 个下行号是房间推送的全部消息。握手的两种类型没有消息号，靠帧里的类型名区分。
 * 不可变，线程安全。
 */
public final class BattleMessageIds {

    /** 契约服务裸名（{@code proto/battle/player_battle.proto service BattleClientPlayer}）。 */
    public static final String SERVICE = "BattleClientPlayer";

    /** 直连上行的四条客户端 RPC（白名单）。每条都回包，成功也回（应答类型都不是 Empty）。 */
    public enum Upstream {
        /** 149 提交行动 → {@code SubmitBattleActionResponse}。 */
        SUBMIT_BATTLE_ACTION("SubmitBattleAction"),
        /** 140 补拉状态 → {@code BattleStateS2C}（非成员回默认值，不回错误码）。 */
        GET_BATTLE_STATE("GetBattleState"),
        /** 162 自动战斗开关 → {@code SetAutoBattleResponse}。 */
        SET_AUTO_BATTLE("SetAutoBattle"),
        /** 165 退出观战 → {@code StopWatchBattleResponse}。 */
        STOP_WATCH_BATTLE("StopWatchBattle");

        private final String method;

        Upstream(String method) {
            this.method = method;
        }

        /** 契约方法名；也是 {@code xm_battle_client_requests_total{method}} 的取值。 */
        public String method() {
            return method;
        }
    }

    /** 房间下行的 7 种推送（{@code MessageContent{id = 0, message_id, serialized_message}}）。 */
    public enum Notify {
        /** 177 {@code BattleAssignedS2C}：落点分配（大厅公告）。 */
        BATTLE_ASSIGNED("NotifyBattleAssigned", PushCategory.LOBBY_ANNOUNCEMENT),
        /** 143 {@code BattleStartS2C}：开局（大厅公告）。 */
        BATTLE_START("NotifyBattleStart", PushCategory.LOBBY_ANNOUNCEMENT),
        /** 139 {@code TurnResultS2C}：参战者的回合结果。 */
        TURN_RESULT("NotifyTurnResult", PushCategory.BATTLE_FRAME),
        /** 150 {@code BattleEndS2C}：参战者的终局（本人那份结算）。 */
        BATTLE_END("NotifyBattleEnd", PushCategory.BATTLE_FRAME),
        /** 161 {@code SpectateStateS2C}：观众握手后的首帧。 */
        SPECTATE_STATE("NotifySpectateState", PushCategory.BATTLE_FRAME),
        /** 158 {@code TurnResultS2C}：观众版回合结果。 */
        SPECTATE_TURN_RESULT("NotifySpectateTurnResult", PushCategory.BATTLE_FRAME),
        /** 166 {@code SpectateEndS2C}：观战结束。 */
        SPECTATE_END("NotifySpectateEnd", PushCategory.BATTLE_FRAME);

        private final String method;
        private final PushCategory category;

        Notify(String method, PushCategory category) {
            this.method = method;
            this.category = category;
        }

        /** 契约方法名；也是 {@code xm_battle_pushes_total{message}} 的取值。 */
        public String method() {
            return method;
        }

        /** 推送类别（决定没有活直连时回落 gate 还是丢弃，{@code PushPolicy}）。 */
        public PushCategory category() {
            return category;
        }
    }

    private final Map<Upstream, Integer> upstreamIds;
    private final Map<Notify, Integer> notifyIds;
    private final Map<Integer, Upstream> upstreamById;

    private BattleMessageIds(Map<Upstream, Integer> upstreamIds, Map<Notify, Integer> notifyIds) {
        this.upstreamIds = new EnumMap<>(upstreamIds);
        this.notifyIds = new EnumMap<>(notifyIds);
        Map<Integer, Upstream> byId = new HashMap<>();
        upstreamIds.forEach((u, id) -> byId.put(id, u));
        this.upstreamById = Map.copyOf(byId);
    }

    /**
     * 从契约解析全部 11 个号。
     *
     * @throws IllegalStateException 缺号（同步产物与代码不一致：拒绝启动）
     */
    public static BattleMessageIds resolve(MessageIdRegistry registry) {
        Map<Upstream, Integer> up = new EnumMap<>(Upstream.class);
        for (Upstream u : Upstream.values()) {
            up.put(u, registry.requireId(SERVICE, u.method()));
        }
        Map<Notify, Integer> down = new EnumMap<>(Notify.class);
        for (Notify n : Notify.values()) {
            down.put(n, registry.requireId(SERVICE, n.method()));
        }
        return new BattleMessageIds(up, down);
    }

    /** 从类路径上的契约解析（{@link MessageIdRegistry#loadFromClasspath()}）。 */
    public static BattleMessageIds loadFromClasspath() {
        return resolve(MessageIdRegistry.loadFromClasspath());
    }

    public int id(Upstream upstream) {
        return upstreamIds.get(upstream);
    }

    public int id(Notify notify) {
        return notifyIds.get(notify);
    }

    /** 白名单查询：这个号是不是四条上行之一。 */
    public Optional<Upstream> upstream(int messageId) {
        return Optional.ofNullable(upstreamById.get(messageId));
    }
}
