package com.game.battle.ticket;

import com.game.battle.BattleIdentity;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.TicketPath;
import com.game.common.token.BattleTickets;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleTicketPayload;
import com.game.proto.eBattleTicketRole;
import com.google.protobuf.ByteString;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 签发落点分配包 {@code BattleAssignedS2C}（基线 {@code BuildAssignment}，{@code room.cpp:1432-1498}；battle-node-spec §2.1–§2.5、§7.5）。
 *
 * <ol>
 *   <li>payload 填 {@code {battle_id, player_id, battle_node_id = 租约节点号, battle_instance_id = 实例 UUID, expire_at_ms = 房间期限, role}}；</li>
 *   <li>只调一次 {@code toByteString()}，<b>同一份字节</b>既拿去签名、又放进 {@code token_payload}；</li>
 *   <li>地址取通告地址（{@link BattleIdentity#advertiseHost()} / {@link BattleIdentity#advertisePort()}，Java 总有值，§11 N6）。</li>
 * </ol>
 * 签名与 payload 都是确定的，所以同一房间、同一玩家、同一角色补签出的票与原票逐字节相同。
 *
 * <p><b>fail-closed</b>：Java 的通告地址与密钥总有值，「签不出」只剩签名器抛异常（实际走不到）；仍保留结构——异常 → {@link Optional#empty()}、
 * 计 {@code xm_battle_tickets_total{result=failed}}、打 ERROR，调用方按 1003 处理（建房整局拒绝、观众不登记、补签不带 assignment）。
 *
 * <p>无内部可变状态；房间只在逻辑线程上调用。
 */
public final class BattleTicketIssuer {

    private static final Logger log = LoggerFactory.getLogger(BattleTicketIssuer.class);

    private final BattleIdentity identity;
    private final BattleTickets tickets;
    private final BattleMetrics metrics;

    public BattleTicketIssuer(BattleIdentity identity, BattleTickets tickets, BattleMetrics metrics) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * @param expireAtMs 房间期限（= 确认事件里的 deadline_ms = 票据寿命，§10.4）
     * @param path       指标标签：建房预签 / 观众 / 补签
     * @return 签好的分配包；签不出时 empty（已计数、已打 ERROR）
     */
    public Optional<BattleAssignedS2C> issue(long battleId, long playerId, long expireAtMs, eBattleTicketRole role,
                                             TicketPath path) {
        try {
            ByteString payload = BattleTicketPayload.newBuilder()
                    .setBattleId(battleId)
                    .setPlayerId(playerId)
                    .setBattleNodeId(identity.nodeId())
                    .setBattleInstanceId(identity.instanceId())
                    .setExpireAtMs(expireAtMs)
                    .setRole(role)
                    .build()
                    .toByteString();
            ByteString signature = tickets.sign(payload);
            BattleAssignedS2C assigned = BattleAssignedS2C.newBuilder()
                    .setBattleId(battleId)
                    .setHost(identity.advertiseHost())
                    .setPort(identity.advertisePort())
                    .setTokenPayload(payload)
                    .setTokenSignature(signature)
                    .setExpireAtMs(expireAtMs)
                    .setRole(role)
                    .build();
            metrics.ticket(path, true);
            return Optional.of(assigned);
        } catch (RuntimeException e) {
            metrics.ticket(path, false);
            log.error("metric=battle_ticket_issue_failed battle_id={} player_id={} role={} path={}，票据签不出（调用方按 1003 处理）",
                    Long.toUnsignedString(battleId), Long.toUnsignedString(playerId), role, path, e);
            return Optional.empty();
        }
    }
}
