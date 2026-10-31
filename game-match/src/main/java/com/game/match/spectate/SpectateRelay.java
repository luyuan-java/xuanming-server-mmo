package com.game.match.spectate;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.common.token.SpectateTickets;
import com.game.match.metrics.MatchMetrics.WatchOutcome;
import com.game.match.proto.BattlePlacement;
import com.game.match.support.MatchTip;
import com.game.match.support.MatchTips;
import com.game.proto.SpectateTicketPayload;
import com.game.proto.eSpectateTier;
import com.game.proto.match.WatchBattleResponse;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 观战的大厅档准入（mmorpg battle-spectate-tiered-fanout.md §15.6 的 Java 版；本地与全球两条 163 路径共用这一份判定）。三件事：读档位、满员时打开 battle 的中继开关、
 * 签观战凭证。大厅档的观众在 match 没有任何逐人状态（§15.1）：不写观战标记、不进观众名单、不写占用行，服务端状态只有 gate 上的订阅。
 *
 * <p>档位只升不降，是提示性质的记账：battle 的开关才是权威。所以「开关开了、记账失败」照样回 LOBBY——只是下一个观众多撞一次直连席。反过来「读档位失败」按依赖故障收场（fail-closed）。
 *
 * <p>无状态、线程安全；阻塞等 Redis 与 battle，在 163 的虚拟线程上调。
 */
public final class SpectateRelay {

    private static final Logger log = LoggerFactory.getLogger(SpectateRelay.class);

    private static final SpectateRelay DISABLED = new SpectateRelay();

    /** 一次大厅档准入的结局。 */
    public sealed interface Admission {

        /** 回 {@code delivery = LOBBY} + 新签的凭证。 */
        record Lobby(WatchBattleResponse response) implements Admission {
        }

        /** 这一场转不了大厅档（房间已不在、来源实例已换、不公开）：确定的业务结论，全球路径可以持久化。 */
        record Rejected(WatchOutcome outcome, MatchTip tip) implements Admission {
        }

        /** battle 没调通（含超时）：开关可能开了也可能没开，不是结论。回 16004，<b>不持久化</b>，留给同 UUID 重试（打开开关幂等）。 */
        record Unavailable() implements Admission {
        }
    }

    private final boolean enabled;
    private final SpectateTierStore tiers;
    private final SpectateRelayDialer dialer;
    private final SpectateTickets tickets;
    private final Duration ticketTtl;
    private final BattleInstanceCheck instances;

    private SpectateRelay() {
        this.enabled = false;
        this.tiers = null;
        this.dialer = null;
        this.tickets = null;
        this.ticketTtl = Duration.ZERO;
        this.instances = null;
    }

    /**
     * @param ticketTtl 凭证有效期（凭证只决定「能不能订阅」，订阅建立后的寿命由战斗终局 / 期限 / 会话决定）
     * @param instances 已是中继档的路径不调 battle，签票前靠它核对来源实例还在
     */
    public SpectateRelay(SpectateTierStore tiers, SpectateRelayDialer dialer, SpectateTickets tickets, Duration ticketTtl, BattleInstanceCheck instances) {
        this.enabled = true;
        this.tiers = Objects.requireNonNull(tiers, "tiers");
        this.dialer = Objects.requireNonNull(dialer, "dialer");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.ticketTtl = Objects.requireNonNull(ticketTtl, "ticketTtl");
        this.instances = Objects.requireNonNull(instances, "instances");
        if (!tickets.enabled()) {
            throw new IllegalArgumentException("打开观战中继必须配凭证密钥");
        }
    }

    /** 开关关闭：观众只走直连席，满了回 16021。 */
    public static SpectateRelay disabled() {
        return DISABLED;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 这场是否已是中继档（是就不再撞直连席）。
     *
     * @throws Deadline.DependencyException 读 Redis 失败（调用方回 16004）
     */
    public boolean relayed(long battleId, Deadline d) {
        return enabled && tiers.tier(battleId, d) != eSpectateTier.SPECTATE_TIER_DIRECT;
    }

    /**
     * 已是中继档：不调 battle，按落点记录的实例与期限签凭证。这条路径量大，也就没有 RPC 替它发现实例已死：目录里同节点号已是别的实例就按房间不存在处理，
     * 否则档位键过期之前（最多 {@code PLACEMENT_TTL_SECONDS}）每名新观众都拿到一张旧实例的凭证，空等 30 秒后收 {@code SOURCE_LOST}。查不到不判。
     *
     * @param d 整请求截止（目录读受它约束，读不到不判）
     */
    public Admission lobby(BattlePlacement placement, long playerId, long nowMs, Deadline d) {
        requireEnabled();
        if (instances.replaced(placement.getBattleNodeId(), placement.getBattleInstanceId(), d)) {
            log.debug("[spectate] 观战帧来源实例已换，不签凭证 battle_id={} node={}", Long.toUnsignedString(placement.getBattleId()),
                    Integer.toUnsignedString(placement.getBattleNodeId()));
            return new Admission.Rejected(WatchOutcome.NOT_FOUND, MatchTip.WATCH_NOT_FOUND);
        }
        return sign(placement.getBattleId(), playerId, placement.getBattleInstanceId(), placement.getDeadlineMs(), nowMs);
    }

    /**
     * 直连席满了（或重放大厅回执）：打开 battle 的中继开关（幂等）→ 记档位 → 按 battle 回包的实例与期限签凭证。先 RPC 后记账：记账失败不影响这一次。
     *
     * @param d 整请求截止：这一跳的超时按它收短，给回包留余量
     */
    public Admission escalate(BattlePlacement placement, long playerId, long nowMs, Deadline d) {
        requireEnabled();
        long battleId = placement.getBattleId();
        Deadline hardStop = SpectateRules.reserveBefore(d, MatchBudgets.WATCH_ADD_RESERVE_MS);
        SpectateRelayDialer.Outcome outcome = dialer.attach(placement, SpectateRules.hopTimeout(MatchBudgets.ADD_OBSERVER_TIMEOUT_MS, hardStop), hardStop);
        return switch (outcome) {
            case SpectateRelayDialer.Outcome.Enabled granted -> {
                raiseQuietly(battleId, eSpectateTier.SPECTATE_TIER_RELAY, d);
                yield sign(battleId, playerId, granted.battleInstanceId(), granted.deadlineMs(), nowMs);
            }
            case SpectateRelayDialer.Outcome.Rejected rejected when rejected.tipId() == MatchTips.BATTLE_ROOM_NOT_FOUND ->
                    new Admission.Rejected(WatchOutcome.NOT_FOUND, MatchTip.WATCH_NOT_FOUND);
            case SpectateRelayDialer.Outcome.Rejected rejected -> {
                log.info("[spectate] battle 拒绝打开中继 battle_id={} tip_id={}", Long.toUnsignedString(battleId), Integer.toUnsignedString(rejected.tipId()));
                yield new Admission.Rejected(WatchOutcome.REJECTED, MatchTip.WATCH_NOT_WATCHABLE);
            }
            case SpectateRelayDialer.Outcome.Unavailable unavailable -> {
                log.warn("[spectate] 打开中继没有调通 battle_id={}: {}", Long.toUnsignedString(battleId), unavailable.detail());
                yield new Admission.Unavailable();
            }
        };
    }

    /** 运营标记赛事局的结局：{@code gone} = 房间已不在。 */
    public record Promotion(eSpectateTier tier, boolean gone) {
    }

    /**
     * 运营标记赛事局时立刻打开开关（Java 版中继与广播同一个开关，没有全服配额）。调不通不算错：保持原档，下一个观众满员时还会再试。
     *
     * @throws Deadline.DependencyException 读 Redis 失败
     */
    public Promotion promote(BattlePlacement placement, Deadline d) {
        long battleId = placement.getBattleId();
        if (!enabled) {
            return new Promotion(eSpectateTier.SPECTATE_TIER_DIRECT, false);
        }
        Deadline hardStop = SpectateRules.reserveBefore(d, MatchBudgets.WATCH_ADD_RESERVE_MS);
        return switch (dialer.promote(placement, SpectateRules.hopTimeout(MatchBudgets.ADD_OBSERVER_TIMEOUT_MS, hardStop), hardStop)) {
            case SpectateRelayDialer.Outcome.Enabled granted -> new Promotion(tiers.raise(battleId, eSpectateTier.SPECTATE_TIER_BROADCAST, d), false);
            case SpectateRelayDialer.Outcome.Rejected rejected when rejected.tipId() == MatchTips.BATTLE_ROOM_NOT_FOUND ->
                    new Promotion(eSpectateTier.SPECTATE_TIER_DIRECT, true);
            case SpectateRelayDialer.Outcome.Rejected rejected -> new Promotion(tiers.tier(battleId, d), false);
            case SpectateRelayDialer.Outcome.Unavailable unavailable -> new Promotion(tiers.tier(battleId, d), false);
        };
    }

    private void raiseQuietly(long battleId, eSpectateTier target, Deadline d) {
        try {
            tiers.raise(battleId, target, d);
        } catch (Deadline.DependencyException e) {
            log.warn("[spectate] 记观战档位失败（开关已开，这一次照常回 LOBBY） battle_id={}: {}", Long.toUnsignedString(battleId), e.toString());
        }
    }

    private Admission sign(long battleId, long playerId, String instance, long deadlineMs, long nowMs) {
        requireEnabled();
        // 实例为空的凭证 gate 必然拒绝（也收不到任何帧）：不如当场回失败
        if (instance.isEmpty()) {
            log.error("[spectate] 观战帧来源实例未知，签不出凭证 battle_id={}", Long.toUnsignedString(battleId));
            return new Admission.Rejected(WatchOutcome.REJECTED, MatchTip.WATCH_NOT_WATCHABLE);
        }
        SpectateTicketPayload payload = SpectateTicketPayload.newBuilder().setBattleId(battleId).setPlayerId(playerId).setBattleInstanceId(instance)
                .setDeadlineMs(deadlineMs).setExpireAtMs(nowMs + ticketTtl.toMillis()).build();
        return new Admission.Lobby(MatchTips.watchLobby(battleId, tickets.sign(payload)));
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException("观战中继没打开，调用方应先看 enabled()");
        }
    }
}
