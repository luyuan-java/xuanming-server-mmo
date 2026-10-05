package com.game.battle.edge;

import com.game.battle.BattleIdentity;
import com.game.battle.BattleProperties;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.protocol.BattleMessageIds;
import com.game.battle.room.BattleClock;
import com.game.battle.room.BattleRoomService;
import com.game.common.token.BattleTickets;
import com.game.net.limit.MessageLimits;
import io.netty.channel.EventLoopGroup;
import java.util.Objects;

/**
 * {@link BattleEdgeServer} 的全部依赖（装配方 {@code BattleConfiguration} / {@code BattleNode} 构造）。不可变。
 *
 * @param properties    {@code xm.battle.*}：绑定地址与端口、有效连接上限、握手期限、非法包阈值
 * @param logicGroup    battle 逻辑线程组（单线程 {@code NioEventLoopGroup(1, "battle-logic")}）：用作 {@code ServerBootstrap} 的 child group，
 *                      全部直连都注册到它唯一的 EventLoop 上（与房间同一条线程，§7.3）。归 {@code BattleNode} 所有，直连面不关它；
 *                      boss（accept）线程由直连面自己建、自己关
 * @param rooms         房间服务（握手挂接 / 断开摘除 / 四条上行的处理器；在逻辑线程上直接调）
 * @param tickets       票据验签（与房间签票共用同一个实例）
 * @param identity      本进程身份（{@code BattleTickets.classify} 的 self 节点号与实例 UUID）
 * @param clock         墙钟（票据过期判定）
 * @param messageLimits 按消息号限频的上限（MessageLimiter 表；每条连接一份 {@code MessageRateLimiter}）
 * @param messageIds    4 条上行的白名单
 * @param metrics       指标
 */
public record EdgeDependencies(
        BattleProperties properties,
        EventLoopGroup logicGroup,
        BattleRoomService rooms,
        BattleTickets tickets,
        BattleIdentity identity,
        BattleClock clock,
        MessageLimits messageLimits,
        BattleMessageIds messageIds,
        BattleMetrics metrics) {

    public EdgeDependencies {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(logicGroup, "logicGroup");
        Objects.requireNonNull(rooms, "rooms");
        Objects.requireNonNull(tickets, "tickets");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(messageLimits, "messageLimits");
        Objects.requireNonNull(messageIds, "messageIds");
        Objects.requireNonNull(metrics, "metrics");
    }
}
