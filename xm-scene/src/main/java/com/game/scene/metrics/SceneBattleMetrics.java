package com.game.scene.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * scene 侧回合制战斗的指标（scene-battle-spec §9）。在途闸的拒绝计数在 {@link SceneMetrics#battleGateReject}（各服务闸共用那一个实例）；
 * 这里是冻结 / 确认 / 恢复 / reaper / 结算应用 / 销账 / RPC 提供方的计数。全部标签都是本类的有界枚举（不带 player_id / battle_id / 会话 / 节点号）。
 * 构造时预建全部组合（「从没发生」与「指标不存在」分得开）。线程安全，任意线程可调。
 */
public final class SceneBattleMetrics {

    static final String PREPARES = "xm.scene.battle.prepares";
    static final String CANCELS = "xm.scene.battle.cancels";
    static final String CONFIRMS = "xm.scene.battle.confirms";
    static final String REBUILDS = "xm.scene.battle.rebuilds";
    static final String FREEZE_EXPIRED = "xm.scene.battle.freeze.expired";
    static final String RESCUES = "xm.scene.battle.rescues";
    static final String RECONNECT_HINTS = "xm.scene.battle.reconnect.hints";
    static final String FROZEN = "xm.scene.battle.frozen";
    static final String RPC = "xm.scene.battle.rpc";
    static final String SETTLEMENTS = "xm.scene.battle.settlements";
    static final String ACKS = "xm.scene.battle.acks";
    static final String RECOVERY = "xm.scene.battle.recovery";
    static final String ITEMS = "xm.scene.battle.items";
    static final String LEDGER_EVICTIONS = "xm.scene.battle.ledger.evictions";
    static final String PENDING_CORRUPT = "xm.scene.battle.pending.corrupt";
    static final String EXP_IGNORED = "xm.scene.battle.exp.ignored";

    /** 备战的结论（§7.5）。 */
    public enum Prepare {
        OK, INVALID, NOT_HERE, SWITCHING, IN_BATTLE, NOT_READY, DEAD, LOCK_HELD, REDIS_ERROR, STALE, CANCELLED
    }

    /** 取消的结论（§7.6）。 */
    public enum Cancel {
        CLEARED, IDEMPOTENT, MISMATCH, REJECTED_FIGHTING, DEFERRED, OFFLINE_DELETED, OFFLINE_REJECTED_FIGHTING, OFFLINE_ABSENT,
        OFFLINE_ERROR
    }

    /**
     * 确认的结论（§7.7）。口径（审计 OPS-22）——<b>不是「每条确认恰好计一次」</b>：
     * <ul>
     *   <li>{@code error} 既计「确认脚本失败」（离线 / 交出冻结 / 迟到确认那一段 CONFIRM），也计「升级 / 再续之后的续锁脚本失败」——
     *       后者与同一条确认已计的 {@code upgraded} / {@code reextended} <b>叠加</b>，所以 Σconfirms 可以大于收到的确认数；
     *       迟到确认 {@code deadline_ms = 0} 时补发的那次续期失败同样计在这里；</li>
     *   <li>{@code idempotent} 除了「已 FIGHTING 且续期已确认」的零 Redis 重复确认，也包含迟到确认回调时「实例已换 / 已在交出冻结 /
     *       已有别的局或同局 FIGHTING 的冻结」这些没有可做之事的情形；</li>
     *   <li>{@code upgraded} 也包含迟到确认回调时发现已有同局备战冻结、按正常确认升级的那一支（审计 FRZ-5）；</li>
     *   <li>{@code ledger_hit} 也包含「本实例已为这一局发出过销账」（账本已 forget，迟到的确认回复是过期结果）。</li>
     * </ul>
     */
    public enum Confirm {
        UPGRADED, IDEMPOTENT, REEXTENDED, MISMATCH, REBUILT, FROZEN_EXTENDED, OFFLINE_EXTENDED, OFFLINE_MISS, LEDGER_HIT, INVALID, ERROR
    }

    /** 冻结重建的来由（§7.7 迟到确认、§7.8 登录 / 沿用）。 */
    public enum RebuildReason {
        LOGIN, LATE_CONFIRM, CARRIED
    }

    /**
     * 冻结重建的结论。{@code reason = login} 时「锁指向的局在本次恢复读到的待结算字段里」分三种计（审计 OPS-13）：
     * <ul>
     *   <li>{@code skipped_corrupt}：那个字段是坏的（同时计 {@code pending_corrupt}）——只有它表示坏记录；</li>
     *   <li>{@code ledger_hit}：这一局本轮刚应用或账本命中（离线结算后在期限内登录的<b>常态</b>），也包含锁指向账本里已有的局、
     *       以及本实例已为它发出过销账的局（过期快照）；</li>
     *   <li>{@code skipped_pending}：这一局的记录被延后（金币被拒等），或排在延后的那一局之后还没轮到。</li>
     * </ul>
     * {@code rebuilt}：重建并复核命中（含复核返回「锁上已是 F、没改」）；{@code reverted}：复核没命中、撤销；{@code error}：复核脚本失败（冻结保守保留）；
     * {@code miss}：进场恢复回来时已在交出冻结、迟到确认没有可重建的（锁不是本局 / 实例已换 / 已有冻结）。
     */
    public enum RebuildResult {
        REBUILT, REVERTED, LEDGER_HIT, SKIPPED_CORRUPT, SKIPPED_PENDING, MISS, ERROR
    }

    /** 冻结作废时的阶段（§7.9）。 */
    public enum Phase {
        PREPARING, FIGHTING
    }

    /** FIGHTING 判废前的 rescue（D21）。 */
    public enum Rescue {
        APPLIED, ALREADY_APPLIED, DEFERRED, MISS, ERROR, GAVE_UP
    }

    /** 推 144 的来由。 */
    public enum HintTrigger {
        CONFIRM, LATE_CONFIRM, LOGIN, CARRIED
    }

    /** {@code SceneBattleService} 的四个方法。 */
    public enum RpcMethod {
        PREPARE, CANCEL, CONFIRM, SETTLEMENT
    }

    /** RPC 提供方的结局。 */
    public enum RpcResult {
        HANDLED, NOT_HERE, DEFERRED, OVERLOADED, ERROR
    }

    /** 结算到达的路径（§7.10、§7.8、§7.9）。 */
    public enum SettlementPath {
        ONLINE, BY_LOCK, LOGIN, RESCUE
    }

    /**
     * 结算到达的结论。口径（审计 OPS-22）：{@code path = by_lock} 的 {@code deferred_recovering} 也包含「读锁回来时实例已换」
     * （回调时玩家在交出冻结则计 {@code deferred_frozen}）；{@code already_applied} 也包含「本实例已为这一局发出过销账、过期的读又把它带回来」
     * （账本可能已 forget，按已应用收尾、不再应用，审计 FRZ-1）。
     */
    public enum SettlementResult {
        APPLIED, ALREADY_APPLIED, DISCARDED_INVALID, DISCARDED_MISMATCH, DISCARDED_VOID, DEFERRED_FROZEN, DEFERRED_CURRENCY,
        DEFERRED_RECOVERING, DEFERRED_LOCK_READ, DEFERRED_LEDGER, NOT_HERE
    }

    /** 销账的触发点（§7.12）。 */
    public enum AckTrigger {
        APPLY, PERSISTED, REAPER, LOGIN, DISCARD
    }

    /** 销账的结局：released = 脚本删到了记录或锁；not_ours = 都不是本局；deferred = 还没落盘（压一次存盘）；error = 脚本失败。 */
    public enum AckResult {
        RELEASED, NOT_OURS, DEFERRED, ERROR
    }

    /**
     * 进场恢复的结局（§7.8）。{@code retry}：恢复读失败（J18），或有延后的待结算记录（后者同时计
     * {@code settlements{path=login,result=deferred_*}}，据此区分）；{@code error}：读回来了，但处理快照（第 2–4 步）时出了意外异常——
     * 状态同样置 RETRY 由 reaper 重跑，只是单独计数（审计 FRZ-2 / OPS-11；正常恒为 0，非 0 就是代码缺陷）。更旧一代的恢复读被丢弃时不计数。
     */
    public enum Recovery {
        READY, RETRY, ERROR
    }

    /** 结算道具的异常路径（§7.11 h / i 步）。 */
    public enum ItemKind {
        CONSUME_CLAMPED, CONSUME_REJECTED, DROP_OVERFLOW, DROP_LOST
    }

    private final Map<Prepare, Counter> prepares;
    private final Map<Cancel, Counter> cancels;
    private final Map<Confirm, Counter> confirms;
    private final Map<RebuildReason, Map<RebuildResult, Counter>> rebuilds = new EnumMap<>(RebuildReason.class);
    private final Map<Phase, Counter> freezeExpired;
    private final Map<Rescue, Counter> rescues;
    private final Map<HintTrigger, Counter> hints;
    private final Map<Phase, AtomicInteger> frozen = new EnumMap<>(Phase.class);
    private final Map<RpcMethod, Map<RpcResult, Counter>> rpcs = new EnumMap<>(RpcMethod.class);
    private final Map<SettlementPath, Map<SettlementResult, Counter>> settlements = new EnumMap<>(SettlementPath.class);
    private final Map<AckTrigger, Map<AckResult, Counter>> acks = new EnumMap<>(AckTrigger.class);
    private final Map<Recovery, Counter> recoveries;
    private final Map<ItemKind, Counter> items;
    private final Counter ledgerEvictions;
    private final Counter pendingCorrupt;
    private final Counter expIgnored;

    public SceneBattleMetrics(MeterRegistry registry) {
        prepares = counters(registry, Prepare.class, PREPARES, "result", "回合制战斗备战的结论（scene-battle-spec §7.5）");
        cancels = counters(registry, Cancel.class, CANCELS, "result", "取消备战的结论（§7.6）");
        confirms = counters(registry, Confirm.class, CONFIRMS, "result", "开局确认的结论（§7.7）");
        for (RebuildReason reason : RebuildReason.values()) {
            Map<RebuildResult, Counter> byResult = new EnumMap<>(RebuildResult.class);
            for (RebuildResult result : RebuildResult.values()) {
                byResult.put(result, Counter.builder(REBUILDS).description("按锁重建冻结（迟到确认 / 进场恢复 / 沿用旧实例）")
                        .tag("reason", lower(reason)).tag("result", lower(result)).register(registry));
            }
            rebuilds.put(reason, byResult);
        }
        freezeExpired = counters(registry, Phase.class, FREEZE_EXPIRED, "phase", "reaper 按期限摘掉的冻结（§7.9）");
        rescues = counters(registry, Rescue.class, RESCUES, "result", "FIGHTING 判废前读本局记录（D21）");
        hints = counters(registry, HintTrigger.class, RECONNECT_HINTS, "trigger", "推 144 重连提示");
        for (Phase phase : Phase.values()) {
            AtomicInteger value = new AtomicInteger();
            frozen.put(phase, value);
            Gauge.builder(FROZEN, value, AtomicInteger::get).description("当前冻结中的玩家数（reaper 每轮数一遍）")
                    .tag("state", lower(phase)).register(registry);
        }
        for (RpcMethod method : RpcMethod.values()) {
            Map<RpcResult, Counter> byResult = new EnumMap<>(RpcResult.class);
            for (RpcResult result : RpcResult.values()) {
                byResult.put(result, Counter.builder(RPC).description("SceneBattleService 提供方的结局（§7.3）")
                        .tag("method", lower(method)).tag("result", lower(result)).register(registry));
            }
            rpcs.put(method, byResult);
        }
        for (SettlementPath path : SettlementPath.values()) {
            Map<SettlementResult, Counter> byResult = new EnumMap<>(SettlementResult.class);
            for (SettlementResult result : SettlementResult.values()) {
                byResult.put(result, Counter.builder(SETTLEMENTS).description("结算到达的结论（§7.10）")
                        .tag("path", lower(path)).tag("result", lower(result)).register(registry));
            }
            settlements.put(path, byResult);
        }
        for (AckTrigger trigger : AckTrigger.values()) {
            Map<AckResult, Counter> byResult = new EnumMap<>(AckResult.class);
            for (AckResult result : AckResult.values()) {
                byResult.put(result, Counter.builder(ACKS).description("销账（ACK 脚本，§7.12）")
                        .tag("trigger", lower(trigger)).tag("result", lower(result)).register(registry));
            }
            acks.put(trigger, byResult);
        }
        recoveries = counters(registry, Recovery.class, RECOVERY, "result", "进场恢复的结局（§7.8）");
        items = counters(registry, ItemKind.class, ITEMS, "kind", "结算道具的异常路径（§7.11）");
        ledgerEvictions = Counter.builder(LEDGER_EVICTIONS).description("结算账本满 64 淘汰最旧项（告警）").register(registry);
        pendingCorrupt = Counter.builder(PENDING_CORRUPT).description("待结算记录里的坏字段（只删该字段）").register(registry);
        expIgnored = Counter.builder(EXP_IGNORED).description("结算里的经验（两版都没有经验系统，只打日志）").register(registry);
    }

    /** 不导出（测试用）。 */
    public static SceneBattleMetrics noop() {
        return new SceneBattleMetrics(new CompositeMeterRegistry());
    }

    public void prepare(Prepare result) {
        prepares.get(result).increment();
    }

    public void cancel(Cancel result) {
        cancels.get(result).increment();
    }

    public void confirm(Confirm result) {
        confirms.get(result).increment();
    }

    public void rebuild(RebuildReason reason, RebuildResult result) {
        rebuilds.get(reason).get(result).increment();
    }

    public void freezeExpired(Phase phase) {
        freezeExpired.get(phase).increment();
    }

    public void rescue(Rescue result) {
        rescues.get(result).increment();
    }

    public void reconnectHint(HintTrigger trigger) {
        hints.get(trigger).increment();
    }

    /** 冻结中的玩家数（逻辑线程推绝对值）。 */
    public void frozen(int preparing, int fighting) {
        frozen.get(Phase.PREPARING).set(preparing);
        frozen.get(Phase.FIGHTING).set(fighting);
    }

    public void rpc(RpcMethod method, RpcResult result) {
        rpcs.get(method).get(result).increment();
    }

    public void settlement(SettlementPath path, SettlementResult result) {
        settlements.get(path).get(result).increment();
    }

    public void ack(AckTrigger trigger, AckResult result) {
        acks.get(trigger).get(result).increment();
    }

    public void recovery(Recovery result) {
        recoveries.get(result).increment();
    }

    public void item(ItemKind kind) {
        items.get(kind).increment();
    }

    public void ledgerEvicted() {
        ledgerEvictions.increment();
    }

    public void pendingCorrupt() {
        pendingCorrupt.increment();
    }

    public void expIgnored() {
        expIgnored.increment();
    }

    private static <E extends Enum<E>> Map<E, Counter> counters(MeterRegistry registry, Class<E> type, String name, String tag,
                                                               String description) {
        Map<E, Counter> map = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            map.put(value, Counter.builder(name).description(description).tag(tag, lower(value)).register(registry));
        }
        return map;
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
