package com.game.match.precheck;

import com.game.common.deadline.Deadline;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.match.port.PlayerStatusReader;
import com.game.match.ticket.TicketHealing;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link MemberPrecheck} 的实现（match-spec §7.4；基线 {@code tsvc.go:417-463}、{@code act.go:209-263}）。按名单顺序逐人查，每人固定
 * 「在线目录 → 战斗锁 → 位置 → 排队票据」四项，第一个不满足的人即为结论，后面的人不再查：
 * <ol>
 *   <li>在线目录有条目 = 在游戏里（断线的重连租约期间没有条目，算不在线）；</li>
 *   <li>战斗锁（咨询性读，权威在 scene 的备战写锁）；</li>
 *   <li>位置记录的严格读：状态必须是在线、且 scene 节点号非 0（节点号为 0 = 此刻没有节点持有他，gather 必然定位不到；活动入口也按这条判，M27）；</li>
 *   <li>排队票据：按排队入口的规则自愈（{@link TicketHealing}），自愈后仍在途才算挡路。</li>
 * </ol>
 * <b>顺序不可调换</b>：两名成员各有问题时结论是名单里靠前的那个人（四项交错着查，不是先把全员的在线查完）；第 4 项的 ready 票自愈依赖第 2 项
 * 「已确认没有战斗锁」。
 *
 * <p>截止：每查一个人之前、以及读他的票据之前各看一次（同基线读票据前的 {@code ctx.Err()} 检查）——调用方已放弃就不再读，更不做有副作用的自愈。
 * 只查不翻译：读战斗锁出错在整队入口按「已在战斗」、在活动入口按「内部错误」，由各入口自己映射。永不抛异常；无可变状态，线程安全。
 */
public final class DefaultMemberPrecheck implements MemberPrecheck {

    private static final Logger log = LoggerFactory.getLogger(DefaultMemberPrecheck.class);

    private final PlayerStatusReader players;
    private final TicketHealing healing;

    public DefaultMemberPrecheck(PlayerStatusReader players, TicketHealing healing) {
        this.players = Objects.requireNonNull(players, "players");
        this.healing = Objects.requireNonNull(healing, "healing");
    }

    @Override
    public Result check(List<Long> roster, Deadline d) {
        Map<Long, Integer> zones = new LinkedHashMap<>();
        for (long playerId : roster) {
            if (d.expired()) {
                log.error("[precheck] 请求预算已用完，不再预检 player={}", id(playerId));
                return Result.failed(Reason.DEADLINE_EXPIRED, playerId);
            }

            // 1. 在线
            try {
                if (players.presence(playerId, d).isEmpty()) {
                    log.info("[precheck] 成员不在线 player={}", id(playerId));
                    return Result.failed(Reason.OFFLINE, playerId);
                }
            } catch (Deadline.DependencyException e) {
                log.error("[precheck] 读在线目录失败 player={}", id(playerId), e);
                return Result.failed(Reason.PRESENCE_READ_FAILED, playerId);
            }

            // 2. 战斗锁
            try {
                if (players.inBattle(playerId, d)) {
                    log.info("[precheck] 成员在战斗中 player={}", id(playerId));
                    return Result.failed(Reason.IN_BATTLE, playerId);
                }
            } catch (Deadline.DependencyException e) {
                log.error("[precheck] 读战斗锁失败 player={}", id(playerId), e);
                return Result.failed(Reason.LOCK_READ_FAILED, playerId);
            }

            // 3. 位置
            int zoneId;
            try {
                HolderRead read = players.location(playerId, d);
                if (read.status() != LocationStatus.ONLINE || read.location() == null || read.location().getSceneNodeId() == 0) {
                    log.info("[precheck] 成员没有可用的位置 player={} status={}", id(playerId), read.status());
                    return Result.failed(Reason.NO_LOCATION, playerId);
                }
                zoneId = read.location().getZoneId();
            } catch (Deadline.DependencyException e) {
                log.error("[precheck] 读位置记录失败 player={}", id(playerId), e);
                return Result.failed(Reason.LOCATION_READ_FAILED, playerId);
            }

            // 4. 排队票据（带自愈；前提「没有战斗锁」已由第 2 项确认）
            if (d.expired()) {
                log.error("[precheck] 请求预算已用完，不再读票据 player={}", id(playerId));
                return Result.failed(Reason.DEADLINE_EXPIRED, playerId);
            }
            try {
                if (healing.healOrBlock(playerId, d) instanceof TicketHealing.InFlight inFlight) {
                    log.info("[precheck] 成员有在途的排队票据 player={} ticket={}", id(playerId), inFlight.ticketId());
                    return Result.failed(Reason.TICKET_IN_FLIGHT, playerId);
                }
            } catch (Deadline.DependencyException e) {
                log.error("[precheck] 读票据 / 自愈失败 player={}", id(playerId), e);
                return Result.failed(Reason.TICKET_READ_FAILED, playerId);
            }

            zones.put(playerId, zoneId);
        }
        return Result.ok(zones);
    }

    private static String id(long playerId) {
        return Long.toUnsignedString(playerId);
    }
}
