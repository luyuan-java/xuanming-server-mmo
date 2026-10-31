package com.game.scene.inbox;

import com.game.player.store.inbox.InboxLedgerRules;
import com.game.player.store.state.InboxDecisionState;
import com.game.player.store.state.InboxDomainCursorState;
import com.game.player.store.state.PlayerState;
import com.game.proto.player_inbox.AckPlayerInboxRequest;
import com.game.proto.player_inbox.AckPlayerInboxResponse;
import com.game.proto.player_inbox.FetchPlayerInboxRequest;
import com.game.proto.player_inbox.FetchPlayerInboxResponse;
import com.game.proto.player_inbox.PlayerInboxAckItem;
import com.game.proto.player_inbox.PlayerInboxAckResult;
import com.game.proto.player_inbox.PlayerInboxDecisionReport;
import com.game.proto.player_inbox.PlayerInboxDirective;
import com.game.proto.player_inbox.PlayerInboxDomain;
import com.game.proto.player_inbox.PlayerInboxFenceVerdict;
import com.game.proto.player_inbox.PlayerInboxItem;
import com.game.proto.player_inbox.PlayerInboxMessageType;
import com.game.proto.player_inbox.PlayerInboxOutcome;
import com.game.scene.inbox.InboxConsumers.Applied;
import com.game.scene.inbox.InboxConsumers.Deferred;
import com.game.scene.inbox.InboxConsumers.ExecResult;
import com.game.scene.inbox.InboxConsumers.Rejected;
import com.game.scene.inbox.InboxConsumers.VersionSkew;
import com.game.scene.inbox.InboxRules.Classified;
import com.game.scene.inbox.InboxRules.Durability;
import com.game.scene.inbox.InboxRules.ItemAction;
import com.game.scene.inbox.PlayerInboxRuntime.DeferReason;
import com.game.scene.inbox.PlayerInboxRuntime.DomainState;
import com.game.scene.inbox.PlayerInboxRuntime.HaltReason;
import com.game.scene.inbox.PlayerInboxRuntime.Phase;
import com.game.scene.world.InboxHooks;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.table.CommonErrorTip;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家持久收件箱的 scene 消费端（mmorpg C++ {@code PlayerInboxSystem}，设计 §7；PARITY 收件箱行第 ⑧–⑩ 条）。只在场景逻辑线程上运行，不加锁。
 *
 * <p><b>一轮</b>：落地 / 提醒 / 在线兜底 / 战斗解冻触发一次检查 → 过前置闸 → Fetch（每批 ≤ 16）→ 回包到达再过一次前置闸 → 按 seq 升序逐条处理
 * （<b>先按账本分类、只有新条目才看指令、执行器最后查</b>）→ 效果与记账同一个任务写进内存 → 请求存盘 → 存档确认落库（{@code onPersisted}，
 * 或 tick 每秒复查）之后才 Ack → 本轮结束。单飞：一个域同时至多一个在途请求；上一批全部确认之前不拉下一批（「环装得下」的前提）。
 *
 * <p><b>Java 与 C++ 的对应</b>（行为对齐，结构按 Java 惯用方式）：
 * <ul>
 *   <li>「已落盘」：C++ 读最后一次写进 Redis 的快照；Java 读最近一次<b>确认落库</b>的 player_state（{@link ScenePlayer#persistedState()}，
 *       带 owner_epoch 条件的 SQL 提交成功），更强；PING 快路径对应 {@link #onPersisted}；</li>
 *   <li>「新落地」：Java 每次进场都新建 {@link ScenePlayer}。同一 epoch 接替旧实例时运行时整份转交（等同 C++「同节点重复路由不算新落地」，含停用）；
 *       换了 epoch 的新实例从空运行时起步、无条件拉一次。所以 C++ 的「新落地时本域还在 Applying，先确认前缀」在 Java 由结构保证：旧 epoch 的实例
 *       带写回离场时在 {@link #onLeaving} 补发已落盘前缀，新实例按账本回放，不会出现同一 seq 在一次 Ack 里出现两次；</li>
 *   <li>退出期：Java 离场即当场移除实例（不存在 C++ 那段「退出存盘在途、实体还在」的异步退出期），在途 Fetch 的回包按 player_id 找不到实例就丢弃，
 *       所以「退出期回包时先确认账本里已有结局的前缀」没有触发点；这些行留在「已投递未确认」，下次落地回放确认（离场打
 *       {@code metric=inbox_leave_ack result=fetch_inflight}）；</li>
 *   <li>找不到收件箱节点（C++ {@code no_node}）与 gRPC UNIMPLEMENTED：Java 直连固定地址，调用失败一律按传输失败退避。</li>
 * </ul>
 * 日志里的 {@code metric=} 键名与 mmorpg 逐字相同（值班的固定查询共用一套）。
 */
public final class InboxService implements InboxHooks {

    private static final Logger log = LoggerFactory.getLogger(InboxService.class);

    /** 在途请求的兜底期限 = Dubbo 调用超时 + 5s（正常走不到：传输保证回调恰好一次；它只防完成通知永远不来）。 */
    static final long INFLIGHT_TIMEOUT_MS = DubboInboxTransport.CALL_TIMEOUT.toMillis() + 5000;
    private static final int SUCCESS_TIP = CommonErrorTip.common_error.kSuccess_VALUE;
    private static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;

    /**
     * @param consumerEnabled      是否消费（{@code game.scene.inbox.consumer-enabled}，权威值在 git 跟踪的 application.yaml；缺省 false）。
     *                             关闭时不建运行时、不拉取；已应用待确认的照常确认（这里不会有：关闭时从不拉取）
     * @param periodicFetchMinutes 在线有界兜底（分钟，±20%；0 = 关闭；缺省 30）
     */
    public record Settings(boolean consumerEnabled, int periodicFetchMinutes) {
        public Settings {
            if (periodicFetchMinutes < 0) {
                throw new IllegalArgumentException("periodic-fetch-minutes 不能为负: " + periodicFetchMinutes);
            }
        }
    }

    private final InboxConsumers consumers;
    private final InboxTransport transport;
    private final SceneClock clock;
    private final Settings settings;
    private final IntSupplier sceneNodeId;
    private final IntSupplier jitterPermille;
    private final String registryBroken;
    private SceneWorld world;
    /** 有运行时状态的实例（player_id → 实例）；tick 只遍历它们。实例被换掉 / 移除后在 tick 里摘掉。 */
    private final Map<Long, ScenePlayer> tracked = new HashMap<>();
    /** 关联号：本节点单调递增，不按实例从头计（已销毁实例的在途回包不可能与新实例的请求同号）；0 保留给「没有在途」。 */
    private long correlationSeq;
    private long nextRegistryBrokenLogMs;
    private long nextConsumerDisabledLogMs;

    /**
     * @param sceneNodeId    本节点号（只用于对端日志：Fetch / Ack 的 scene_node_id）
     * @param jitterPermille [0, 1000] 的抖动（生产随机，单测注入定值）
     */
    public InboxService(InboxConsumers consumers, InboxTransport transport, SceneClock clock, Settings settings, IntSupplier sceneNodeId,
                        IntSupplier jitterPermille) {
        this.consumers = Objects.requireNonNull(consumers, "consumers");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sceneNodeId = Objects.requireNonNull(sceneNodeId, "sceneNodeId");
        this.jitterPermille = Objects.requireNonNull(jitterPermille, "jitterPermille");
        this.registryBroken = consumers.selfCheck();
        long now = nowMs();
        if (registryBroken != null) {
            // 不阻断启动（IB-7）：收件箱停用只是延迟处置，条目留在服务端 PENDING。之后 tick 周期性补打同一行
            logRegistryBroken();
            nextRegistryBrokenLogMs = now + InboxRules.NODE_STUCK_RELOG_INTERVAL_MS;
        }
        if (!settings.consumerEnabled()) {
            logConsumerDisabled();
            nextConsumerDisabledLogMs = now + InboxRules.NODE_STUCK_RELOG_INTERVAL_MS;
        } else {
            log.info("[PlayerInbox] consumer_enabled={} periodic_fetch_minutes={} consumer_domains={}", consumerEnabled(),
                    settings.periodicFetchMinutes(), consumers.consumerDomains());
        }
    }

    /** 绑定世界（装配时一次）。 */
    public void attach(SceneWorld world) {
        if (this.world != null && this.world != world) {
            throw new IllegalStateException("InboxService 已绑定了另一个世界");
        }
        this.world = Objects.requireNonNull(world, "world");
    }

    /** 生效的消费开关：注册表自检失败时一律关。 */
    public boolean consumerEnabled() {
        return registryBroken == null && settings.consumerEnabled();
    }

    private long nowMs() {
        return clock.nanoTime() / 1_000_000L;
    }

    private int jitter() {
        return jitterPermille.getAsInt();
    }

    private long nextCorrelationId() {
        return ++correlationSeq;
    }

    // ================================================================ 节点级日志

    private void logRegistryBroken() {
        log.error("[PlayerInbox] metric=inbox_registry_broken detail={}; registry self-check failed, inbox consumption disabled on this node",
                registryBroken);
    }

    private void logConsumerDisabled() {
        // metric=inbox_consumer_disabled 是告警固定查询的契约（mmorpg 评审 H1）：开关关着时收件箱照常收单，本节点却一条都不执行
        log.warn("[PlayerInbox] metric=inbox_consumer_disabled consumer_enabled=0 periodic_fetch_minutes={} consumer_domains={}; "
                        + "game.scene.inbox.consumer-enabled is off, inbox items are accepted by the service but not executed on this node",
                settings.periodicFetchMinutes(), consumers.consumerDomains().size());
    }

    // ================================================================ 触发入口

    /** 加载时（进场景前，{@code PlayerInitializer}）：账本损坏就大声报一次（该玩家的收件箱随之停用，原样保留、不清空重建）。 */
    public static void checkLedgerOnLoad(ScenePlayer player) {
        String reason = player.inboxLedger().invalidReason();
        if (reason != null) {
            log.error("[PlayerInbox] metric=inbox_ledger_invalid source=loader player_id={} detail={}; inbox consumption is disabled for this "
                    + "player until the ledger is repaired (do NOT clear it: that would re-execute applied sanctions)",
                    Long.toUnsignedString(player.playerId()), reason);
        }
    }

    @Override
    public void onEntered(SceneWorld world, ScenePlayer player, ScenePlayer carried) {
        if (!consumerEnabled()) {
            // 消费关闭：不建运行时、不拉取；账本照常随存档往返（回滚下限，设计 §3.3、§11.7）
            return;
        }
        long playerId = player.playerId();
        if (carried != null && carried.inboxRuntime() != null) {
            // 同一 epoch 接替旧实例：不算新落地，运行时（含在途请求、待确认队列、停用）整份转交
            player.setInboxRuntime(carried.inboxRuntime());
            carried.setInboxRuntime(null);
            tracked.put(playerId, player);
            log.debug("[PlayerInbox] landing skipped (same owner_epoch carried) player_id={} epoch={}", Long.toUnsignedString(playerId),
                    player.ownerEpoch());
            return;
        }
        PlayerInboxRuntime runtime = new PlayerInboxRuntime();
        player.setInboxRuntime(runtime);
        tracked.put(playerId, player);
        long now = nowMs();
        for (int domain : consumers.consumerDomains()) {
            DomainState state = runtime.domainForWrite(domain);
            state.nextPeriodicMs = settings.periodicFetchMinutes() > 0
                    ? now + InboxRules.periodicDelayMs(settings.periodicFetchMinutes(), jitter()) : 0;
            // 新落地：无条件拉一次，不去抖（设计 §7.3 第 1 条）
            state.failStreak = 0;
            state.deferReason = DeferReason.NONE;
            state.nextCheckMs = now;
        }
        log.debug("[PlayerInbox] landing scheduled a check player_id={} epoch={}", Long.toUnsignedString(playerId), player.ownerEpoch());
    }

    /** 在线提醒（{@code SceneInboxService.notifyInbox}，逻辑线程上）：把这个域的下一次检查提前；玩家不在本节点 / 还没落地就丢弃（下次落地会拉）。 */
    public void onNotify(long playerId, int domain) {
        ScenePlayer player = world == null ? null : world.playerById(playerId);
        if (player == null || player.inboxRuntime() == null) {
            log.debug("[PlayerInbox] notify dropped: player not landed on this node player_id={}", Long.toUnsignedString(playerId));
            return;
        }
        DomainState state = player.inboxRuntime().domain(domain);
        if (state == null) {
            log.warn("[PlayerInbox] notify for a domain this binary does not consume dropped player_id={} domain={}",
                    Long.toUnsignedString(playerId), domain);
            return;
        }
        if (state.halted) {
            return;
        }
        if (state.phase != Phase.IDLE) {
            state.recheckRequested = true;
            return;
        }
        long now = nowMs();
        long debounced = Math.max(now, state.lastFetchMs + InboxRules.MIN_CHECK_GAP_MS);
        if (state.nextCheckMs == 0 || state.nextCheckMs > debounced) {
            state.nextCheckMs = debounced;
        }
    }

    @Override
    public void onBattleFreezeCleared(SceneWorld world, ScenePlayer player) {
        PlayerInboxRuntime runtime = player.inboxRuntime();
        if (runtime == null) {
            return;
        }
        long now = nowMs();
        for (DomainState state : runtime.domains()) {
            if (state.halted || state.deferReason != DeferReason.IN_BATTLE) {
                continue;
            }
            if (state.phase == Phase.IDLE || state.phase == Phase.APPLYING) {
                // 立即重查：不加这一行，战斗结束后最多要再等 60 秒的退避
                state.failStreak = 0;
                state.nextCheckMs = now;
            }
        }
    }

    @Override
    public void onPersisted(SceneWorld world, ScenePlayer player) {
        PlayerInboxRuntime runtime = player.inboxRuntime();
        if (runtime == null) {
            return;
        }
        long now = nowMs();
        for (DomainState state : runtime.domains()) {
            if (state.phase == Phase.AWAIT_DURABLE) {
                tryAck(player, state, now);
            }
        }
    }

    /**
     * 实例带最终写回离场之后：尽力补发一次 Ack（评审 M4）。覆盖范围只到「结局已定、已落盘」的行：域处于 AWAIT_DURABLE，或处于 APPLYING 且带执行器
     * DEFER 留下的已定前缀（先丢剩下的本地条目再确认前缀）；按最近一次确认落库的存档判 durable（IB-3）。满足就发一次（不看 Ack 退避、不等回包）；
     * 不 durable 就什么都不做，绝不在落盘前确认。owner_epoch 此刻仍是本实例的值，服务端应判 MATCH；新 owner 已推进围栏时判 STALE，无害。
     * 回包按 player_id + 关联号找实例：实例已移除就丢弃；同一玩家重新落地后的新实例关联号也对不上（节点级单调计数器）。
     */
    @Override
    public void onLeaving(SceneWorld world, ScenePlayer player) {
        tracked.remove(player.playerId(), player);
        PlayerInboxRuntime runtime = player.inboxRuntime();
        if (runtime == null) {
            return;
        }
        long playerId = player.playerId();
        long now = nowMs();
        for (DomainState state : runtime.domains()) {
            if (state.phase == Phase.FETCHING) {
                log.info("[PlayerInbox] metric=inbox_leave_ack result=fetch_inflight player_id={} domain={} corr={}; the reply will be discarded, "
                                + "entries it delivered stay delivered-but-unacknowledged until the player next lands", Long.toUnsignedString(playerId),
                        state.domain, state.correlationId);
                continue;
            }
            if (state.phase == Phase.APPLYING) {
                logDeliveredUndecided(playerId, state, "leaving");
            }
            if (state.ackQueue.isEmpty()) {
                continue;
            }
            if (state.phase == Phase.ACKING) {
                log.info("[PlayerInbox] metric=inbox_leave_ack result=ack_inflight player_id={} domain={} corr={} decisions={}; if this Ack fails "
                                + "after the instance is gone, these entries stay delivered-but-unacknowledged until the player next lands",
                        Long.toUnsignedString(playerId), state.domain, state.correlationId, state.ackQueue.size());
                continue;
            }
            if (state.phase == Phase.APPLYING) {
                dropPendingForRefetch(state);
                enterAwaitDurable(state, now);
            } else if (state.phase != Phase.AWAIT_DURABLE) {
                continue;
            }
            if (!allDecisionsDurable(player, state)) {
                log.info("[PlayerInbox] metric=inbox_leave_ack result=not_durable player_id={} domain={} decisions={}; the entries stay "
                        + "delivered-but-unacknowledged until the player next lands", Long.toUnsignedString(playerId), state.domain,
                        state.ackQueue.size());
                continue;
            }
            sendAck(player, state, now);
            boolean sent = state.phase == Phase.ACKING;
            log.info("[PlayerInbox] metric=inbox_leave_ack result={} player_id={} domain={} decisions={} corr={}; best effort, the reply is not awaited",
                    sent ? "sent" : "not_sent", Long.toUnsignedString(playerId), state.domain, state.ackQueue.size(), state.correlationId);
        }
    }

    /** 每秒一次（逻辑线程上）：节点级补打、在线兜底、在途超时、等落盘的复查、到期的检查（单 tick 至多 200 次拉取）。 */
    public void tick() {
        long now = nowMs();
        if (registryBroken != null && now >= nextRegistryBrokenLogMs) {
            logRegistryBroken();
            nextRegistryBrokenLogMs = now + InboxRules.NODE_STUCK_RELOG_INTERVAL_MS;
        }
        if (!settings.consumerEnabled() && now >= nextConsumerDisabledLogMs) {
            logConsumerDisabled();
            nextConsumerDisabledLogMs = now + InboxRules.NODE_STUCK_RELOG_INTERVAL_MS;
        }
        // 先收集再处理：处理里会调执行器、请求存盘、发请求，不在遍历中改集合
        List<Work> timeouts = new ArrayList<>();
        List<Work> acks = new ArrayList<>();
        List<Work> checks = new ArrayList<>();
        for (Iterator<Map.Entry<Long, ScenePlayer>> it = tracked.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, ScenePlayer> entry = it.next();
            ScenePlayer player = entry.getValue();
            if (world == null || world.playerById(entry.getKey()) != player || player.inboxRuntime() == null) {
                // 实例已被换掉或移除（交出、失去归属、被更新的进场接替）：运行时随实例丢弃，下次落地重拉
                it.remove();
                continue;
            }
            for (DomainState state : player.inboxRuntime().domains()) {
                int periodic = settings.periodicFetchMinutes();
                if (periodic > 0 && !state.halted && state.nextPeriodicMs != 0 && now >= state.nextPeriodicMs) {
                    state.nextPeriodicMs = now + InboxRules.periodicDelayMs(periodic, jitter());
                    if (state.phase == Phase.IDLE) {
                        if (state.nextCheckMs == 0) {
                            state.nextCheckMs = now;
                        }
                    } else {
                        state.recheckRequested = true;
                    }
                }
                switch (state.phase) {
                    case FETCHING, ACKING -> {
                        if (state.inflightSinceMs != 0 && now - state.inflightSinceMs > INFLIGHT_TIMEOUT_MS) {
                            timeouts.add(new Work(player, state));
                        }
                    }
                    case AWAIT_DURABLE -> acks.add(new Work(player, state));
                    case IDLE, APPLYING -> {
                        if (!state.halted && state.nextCheckMs != 0 && now >= state.nextCheckMs) {
                            checks.add(new Work(player, state));
                        }
                    }
                }
            }
        }
        for (Work work : timeouts) {
            if (work.state.phase == Phase.FETCHING || work.state.phase == Phase.ACKING) {
                handleInflightTimeout(work.player, work.state, now);
            }
        }
        // 等落盘与确认：每秒复查一次存档（纯内存比较），每 5 秒补请求一次存盘。不受前置闸与检查预算限制
        for (Work work : acks) {
            if (work.state.phase == Phase.AWAIT_DURABLE) {
                tryAck(work.player, work.state, now);
            }
        }
        int budget = InboxRules.TICK_CHECK_BUDGET;
        for (Work work : checks) {
            DomainState state = work.state;
            if (state.halted || state.nextCheckMs == 0 || now < state.nextCheckMs) {
                continue;
            }
            if (state.phase == Phase.IDLE) {
                if (budget == 0) {
                    continue;
                }
                budget--;
                state.nextCheckMs = 0;
                startFetch(work.player, state, now);
            } else if (state.phase == Phase.APPLYING) {
                state.nextCheckMs = 0;
                applyPending(work.player, state, now);
            }
        }
    }

    private record Work(ScenePlayer player, DomainState state) {
    }

    /** 本节点有运行时状态的实例数（测试 / 排障用）。 */
    public int trackedPlayers() {
        return tracked.size();
    }

    // ================================================================ 退避与停用

    /** 不会自愈的延后原因：目前只有账本损坏（加载时判定、此后不变）。 */
    private static boolean stuckDeferReason(DeferReason reason) {
        return reason == DeferReason.LEDGER_INVALID || reason == DeferReason.ADMISSION_MISSING;
    }

    /** 记下延后原因并在 delayMs 之后续做。同一原因连续出现只在第一次打 INFO；不会自愈的另按节拍打 WARN（落在 [10m] 告警窗口之内）。 */
    private void scheduleRetry(DomainState state, long playerId, DeferReason reason, long delayMs, long now) {
        state.nextCheckMs = now + delayMs;
        boolean changed = state.deferReason != reason;
        if (changed) {
            log.info("[PlayerInbox] metric=inbox_deferred reason={} player_id={} domain={} phase={} retry_in_ms={}", reason.label(),
                    Long.toUnsignedString(playerId), state.domain, state.phase.label(), delayMs);
        } else {
            log.debug("[PlayerInbox] deferred again reason={} player_id={} domain={} streak={} retry_in_ms={}", reason.label(),
                    Long.toUnsignedString(playerId), state.domain, state.failStreak, delayMs);
        }
        if (stuckDeferReason(reason) && (changed || InboxRules.stuckWarnDue(state.failStreak))) {
            log.warn("[PlayerInbox] metric=inbox_ledger_invalid source=apply_gate player_id={} domain={} streak={}; inbox consumption stays "
                    + "deferred until the ledger is repaired (does not self-heal)", Long.toUnsignedString(playerId), state.domain, state.failStreak);
        }
        state.deferReason = reason;
    }

    /** 按 1s → 60s 加抖动退避。消费关闭不安排重试：配置在进程内不会翻转。 */
    private void defer(DomainState state, long playerId, DeferReason reason, long now) {
        if (reason == DeferReason.DISABLED) {
            state.nextCheckMs = 0;
            state.deferReason = reason;
            return;
        }
        if (state.failStreak < Integer.MAX_VALUE) {
            state.failStreak++;
        }
        scheduleRetry(state, playerId, reason, InboxRules.backoffMs(state.failStreak, jitter()), now);
    }

    /** 停用这名玩家的这个域：本实例不再拉取、不再应用，直到实例下次在本节点新建。ackQueue 与阶段由调用方决定。 */
    private static void halt(DomainState state, long playerId, HaltReason reason, String detail) {
        state.halted = true;
        state.haltReason = reason;
        state.pendingItems.clear();
        state.nextCheckMs = 0;
        state.recheckRequested = false;
        state.hasMore = false;
        log.error("[PlayerInbox] metric=inbox_halted reason={} player_id={} domain={} detail={}; this domain stays disabled for the player until "
                + "the instance is recreated on this node", reason.label(), Long.toUnsignedString(playerId), state.domain, detail);
    }

    /** 一轮结束：回到 IDLE。has_more 立即拉下一批；期间来过新触发则去抖后再拉一次。 */
    private static void finishRound(DomainState state, long now) {
        state.phase = Phase.IDLE;
        state.correlationId = 0;
        state.inflightSinceMs = 0;
        state.durableMismatchReported = false;
        if (state.halted) {
            state.nextCheckMs = 0;
        } else if (state.hasMore) {
            state.nextCheckMs = now;
        } else if (state.recheckRequested) {
            state.nextCheckMs = Math.max(now, state.lastFetchMs + InboxRules.MIN_CHECK_GAP_MS);
        } else {
            state.nextCheckMs = 0;
        }
        state.hasMore = false;
        state.recheckRequested = false;
    }

    // ================================================================ 前置闸（设计 §7.5）

    /** 返回 NONE = 放行。拉取前判一次、应用前再判一次；确认不经过它。 */
    private DeferReason checkApplyGate(ScenePlayer player, int domain) {
        if (player.admission() == null) return DeferReason.ADMISSION_MISSING;
        if (!consumerEnabled()) {
            return DeferReason.DISABLED;
        }
        if (player.frozen()) {
            // 跨节点换图 / 跨 zone 传送的交出冻结在途：本节点无权再改它
            return DeferReason.FROZEN;
        }
        if (player.ownerEpoch() == 0) {
            return DeferReason.EPOCH_ZERO;
        }
        if (player.inboxLedger().invalidReason() != null) {
            return DeferReason.LEDGER_INVALID;
        }
        InboxConsumers.DomainRule rule = consumers.rule(domain);
        if (rule != null && rule.requiresOutOfBattle() && player.inBattle()) {
            // 战斗快照已经发出去，此刻改货币状态会与结算打架（同 GM 写操作的战斗闸）
            return DeferReason.IN_BATTLE;
        }
        return DeferReason.NONE;
    }

    // ================================================================ 落盘与确认（设计 §7.7）

    /** 请求一次存盘（500ms 节流）；结局只看存档，不看这次请求的返回值。 */
    private void requestPersist(ScenePlayer player, DomainState state, long now) {
        if (state.lastPersistRequestMs != 0 && now - state.lastPersistRequestMs < InboxRules.PERSIST_THROTTLE_MS) {
            return;
        }
        state.lastPersistRequestMs = now;
        if (world != null) {
            world.requestSave(player);
        }
    }

    /** 本批每一条结局是否都已出现在最近一次确认落库的存档里。 */
    private static boolean allDecisionsDurable(ScenePlayer player, DomainState state) {
        PlayerState persisted = player.persistedState();
        if (persisted == null) {
            return false;
        }
        for (InboxDecisionState decision : state.ackQueue) {
            Durability durability = InboxRules.durability(persisted.getInboxLedger(), state.domain, decision);
            if (durability == Durability.DURABLE) {
                continue;
            }
            if (durability != Durability.NOT_YET && !state.durableMismatchReported) {
                // 盘上与内存不一致：结局固定被破坏，只可能是缺陷。不敢报 durable、不确认，继续等并告警（每批只报一次）
                state.durableMismatchReported = true;
                log.error("[PlayerInbox] Persisted ledger disagrees with in-memory decision; not acknowledging player_id={} domain={} seq={} "
                                + "outcome={} verdict={}", Long.toUnsignedString(player.playerId()), state.domain,
                        Long.toUnsignedString(decision.getSeq()), outcomeVerb(decision.getOutcome()), durability);
            }
            return false;
        }
        return true;
    }

    private void sendAck(ScenePlayer player, DomainState state, long now) {
        long epoch = player.ownerEpoch();
        long playerId = player.playerId();
        if (epoch == 0 || player.admission() == null) {
            // 拉取前已过 epoch 闸，epoch 是实例上的 final：走到这里只可能是缺陷。发 0 会被判 EPOCH_REQUIRED 并停用，不如保留队列退避再看
            state.ackFailStreak = saturatingIncrement(state.ackFailStreak);
            state.nextAckMs = now + InboxRules.backoffMs(state.ackFailStreak, jitter());
            log.error("[PlayerInbox] Ack postponed: original admission or owner_epoch missing player_id={} domain={}",
                    Long.toUnsignedString(playerId), state.domain);
            return;
        }
        long correlationId = nextCorrelationId();
        AckPlayerInboxRequest.Builder request = AckPlayerInboxRequest.newBuilder()
                .setPlayerId(playerId)
                .setDomainValue(state.domain)
                .setOwnerEpoch(epoch)
                .setAdmission(player.admission())
                .setCorrelationId(correlationId)
                // 只用于对端日志：服务端围栏 / Ack 异常的 ERROR 据此定位发送方（评审 L10）
                .setSceneNodeId(sceneNodeId.getAsInt());
        for (InboxDecisionState decision : state.ackQueue) {
            request.addDecisions(PlayerInboxDecisionReport.newBuilder()
                    .setSeq(decision.getSeq())
                    .setOutcomeValue(decision.getOutcome())
                    .setReason(decision.getReason())
                    .setRequestId(decision.getRequestId())); // 服务端再核对一次（IB-9）
        }
        int domain = state.domain;
        state.phase = Phase.ACKING;
        state.correlationId = correlationId;
        state.inflightSinceMs = now;
        transport.ack(request.build(), (response, failure) -> {
            if (failure != null || response == null) {
                onAckFailed(playerId, domain, correlationId, failure);
            } else {
                onAckReply(playerId, domain, correlationId, response);
            }
        });
    }

    /** AWAIT_DURABLE 的推进：全部落盘就确认；没落盘就（每 5 秒）补请求一次存盘。确认不受前置闸限制。 */
    private void tryAck(ScenePlayer player, DomainState state, long now) {
        if (state.phase != Phase.AWAIT_DURABLE) {
            return;
        }
        if (state.ackQueue.isEmpty()) {
            finishRound(state, now);
            return;
        }
        if (!allDecisionsDurable(player, state)) {
            if (state.lastPersistRequestMs == 0 || now - state.lastPersistRequestMs >= InboxRules.RESAVE_INTERVAL_MS) {
                requestPersist(player, state, now);
            }
            return;
        }
        if (now < state.nextAckMs) {
            return;
        }
        sendAck(player, state, now);
    }

    /** 只切进 AWAIT_DURABLE、不当场推进（调用方 ackQueue 非空）。之后由 {@link #onPersisted} 或 tick 的 {@link #tryAck} 推进。 */
    private static void enterAwaitDurable(DomainState state, long now) {
        state.phase = Phase.AWAIT_DURABLE;
        state.nextAckMs = now;
        state.ackFailStreak = 0;
        state.durableMismatchReported = false;
    }

    /** 本批处理完（或在某一条停下）之后：有新记账就请求存盘；有待确认的进 AWAIT_DURABLE，否则本轮结束。 */
    private void afterBatchDecided(ScenePlayer player, DomainState state, boolean recorded, long now) {
        if (recorded) {
            requestPersist(player, state, now);
        }
        if (state.ackQueue.isEmpty()) {
            finishRound(state, now);
            return;
        }
        enterAwaitDurable(state, now);
        tryAck(player, state, now);
    }

    /** 即将丢掉本地缓存里已投递、还没定结局的条目时打一行可检索的 INFO（只记日志，不改状态）。 */
    private static void logDeliveredUndecided(long playerId, DomainState state, String source) {
        if (state.pendingItems.isEmpty()) {
            return;
        }
        log.info("[PlayerInbox] metric=inbox_leave_ack result=delivered_undecided source={} player_id={} domain={} items={} first_seq={} last_seq={}; "
                        + "delivered entries without a decision are dropped and stay delivered-but-unacknowledged until they are fetched again", source,
                Long.toUnsignedString(playerId), state.domain, state.pendingItems.size(), Long.toUnsignedString(state.pendingItems.get(0).getSeq()),
                Long.toUnsignedString(state.pendingItems.get(state.pendingItems.size() - 1).getSeq()));
    }

    /**
     * 丢掉本地缓存的条目，改为下次重新拉取（评审 L1）。已记账的前缀（只可能来自执行器 DEFER 之后的续做）不能跟着丢，也不能留到下一批：那些条目在服务端
     * 仍是 PENDING，下一次 Fetch 会再投一遍，按账本回放后同一 seq 在 ackQueue 里出现两次，服务端判 InvalidParameter。所以 ackQueue 非空时先进
     * AWAIT_DURABLE 把前缀确认掉，{@link #finishRound} 再按 recheckRequested 重拉。
     *
     * @return true = ackQueue 非空、已置 recheckRequested，调用方须把本域转入 AWAIT_DURABLE；false = 已回到 IDLE，检查时刻由调用方安排
     */
    private static boolean dropPendingForRefetch(DomainState state) {
        state.pendingItems.clear();
        state.hasMore = false;
        if (state.ackQueue.isEmpty()) {
            state.phase = Phase.IDLE;
            return false;
        }
        state.recheckRequested = true;
        return true;
    }

    private static boolean battleDomain(DomainState state) {
        return state.domain == PlayerInboxDomain.PLAYER_INBOX_DOMAIN_SCENE_BATTLE_SETTLEMENT_VALUE;
    }

    /**
     * 战斗域的一条投递能不能按「原 APPLIED 结局」处理（同 C++ 战斗域守卫）：类型必须是战斗结算、指令必须是 NONE（战斗终局不接受 VOID），
     * 水位下已出环（只能报 UNKNOWN）不行，回放时环里的原结局必须是 APPLIED。新条目（EXECUTE）与 MISMATCH 交给后面的原有路径。
     */
    private static boolean originalBattleOutcome(int msgType, int directive, ItemAction action, Classified classified) {
        if (msgType != PlayerInboxMessageType.PLAYER_INBOX_MSG_BATTLE_SETTLEMENT_VALUE
                || directive != PlayerInboxDirective.PLAYER_INBOX_DIRECTIVE_NONE_VALUE || action == ItemAction.ACK_UNKNOWN) {
            return false;
        }
        return action != ItemAction.REPLAY
                || classified.recorded().getOutcome() == PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_APPLIED_VALUE;
    }

    /**
     * 本域最近结局环里同一局（request_id = battle_id）已记 APPLIED 的那一条；没有为 null。只看环（内存账本，含本实例尚未落盘、与效果同一份状态的结局），
     * 不看已挤出环的历史——有界缓解，见调用处。
     */
    private static InboxDecisionState appliedBattleRequest(InboxLedger ledger, int domain, long requestId) {
        InboxDomainCursorState cursor = ledger.cursor(domain);
        if (cursor == null || requestId == 0) {
            return null;
        }
        for (InboxDecisionState recent : cursor.getRecentList()) {
            if (recent.getRequestId() == requestId && recent.getMsgType() == PlayerInboxMessageType.PLAYER_INBOX_MSG_BATTLE_SETTLEMENT_VALUE
                    && recent.getOutcome() == PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_APPLIED_VALUE) {
                return recent;
            }
        }
        return null;
    }

    /** 水位已过这一条、原结局已不在环里时报的 UNKNOWN 结局。 */
    private static InboxDecisionState unknownDecisionFor(PlayerInboxItem item) {
        return InboxDecisionState.newBuilder()
                .setSeq(item.getSeq())
                .setOutcome(PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_UNKNOWN_VALUE)
                .setReason(0)
                .setRequestId(item.getRequestId())
                .setMsgType(item.getMsgTypeValue())
                .build();
    }

    // ================================================================ 应用（设计 §7.6 第 3 步）

    /** 按 seq 升序逐条处理 pendingItems；在某一条停下时，已定的前缀照常进入确认流程（MISMATCH 除外：那时什么都不确认）。 */
    private void applyPending(ScenePlayer player, DomainState state, long now) {
        long playerId = player.playerId();
        if (state.halted) {
            state.pendingItems.clear();
            afterBatchDecided(player, state, false, now);
            return;
        }
        DeferReason gate = checkApplyGate(player, state.domain);
        if (gate != DeferReason.NONE) {
            // 闸不过就丢掉本地副本、下次重新拉（评审 L1），不在本地等闸再续做：等闸期间服务端可能给条目挂上 VOID，按旧副本续做会把已作废的处置照样执行。
            // 代价只是服务端 delivery_count 多 +1。只有执行器 DEFER（闸已过、条目已开始处理）才保留本地续做；续做时闸不过同样丢副本、先确认前缀再重拉
            if (dropPendingForRefetch(state)) {
                log.info("[PlayerInbox] Apply gate closed ({}); acknowledging the decided prefix before refetching player_id={} domain={} decisions={}",
                        gate.label(), Long.toUnsignedString(playerId), state.domain, state.ackQueue.size());
                afterBatchDecided(player, state, false, now);
                return;
            }
            defer(state, playerId, gate, now);
            return;
        }
        InboxLedger ledger = player.inboxLedger();
        boolean recorded = false;
        for (int consumed = 0; consumed < state.pendingItems.size(); consumed++) {
            PlayerInboxItem item = state.pendingItems.get(consumed);
            long seq = item.getSeq();
            long requestId = item.getRequestId();
            int msgType = item.getMsgTypeValue();
            // 先按账本分类，只有新条目才看指令，执行器最后才查（评审 M1）
            Classified classified = InboxRules.classify(ledger.cursor(state.domain), seq, requestId, msgType);
            ItemAction action = InboxRules.plan(classified.deliveryClass(), item.getDirectiveValue());

            if (battleDomain(state) && !originalBattleOutcome(msgType, item.getDirectiveValue(), action, classified)) {
                // 战斗域守卫（同 C++ player_inbox_system.cpp 的同名守卫）：永久终局只能由原 APPLIED 结局终结。类型 / 指令不符、水位下已出环（只能报
                // UNKNOWN）、回放的原结局不是 APPLIED，都不能确认——确认了服务端就把一局未应用的奖励永久销账。按版本错位停在这一条、不确认，
                // 已定前缀照常确认；这一行留在服务端 PENDING，等实例重建后由人工核对处置。
                log.error("[PlayerInbox] Battle delivery is not an original APPLIED outcome player_id={} domain={} seq={} msg_type={} directive={} "
                                + "action={} request_id={}", Long.toUnsignedString(playerId), state.domain, Long.toUnsignedString(seq), msgType,
                        item.getDirectiveValue(), action, Long.toUnsignedString(requestId));
                halt(state, playerId, HaltReason.VERSION_SKEW, "battle outcome requires original APPLIED decision");
                afterBatchDecided(player, state, recorded, now);
                return;
            }

            if (action == ItemAction.REPLAY) {
                // 重复投递：按 (seq, request_id, msg_type) 核对后回放原结局（IB-9），不需要执行器、不看指令
                state.ackQueue.add(classified.recorded());
                continue;
            }
            if (action == ItemAction.ACK_UNKNOWN) {
                state.ackQueue.add(unknownDecisionFor(item));
                log.warn("[PlayerInbox] Redelivered entry is below the ledger watermark but no longer in the recent ring; acknowledging as UNKNOWN "
                        + "player_id={} domain={} seq={} request_id={}", Long.toUnsignedString(playerId), state.domain, Long.toUnsignedString(seq),
                        Long.toUnsignedString(requestId));
                continue;
            }
            if (action == ItemAction.HALT_MISMATCH) {
                InboxDecisionState ledgerEntry = classified.recorded();
                log.error("[PlayerInbox] metric=inbox_replay_mismatch player_id={} domain={} seq={} delivered_request_id={} delivered_msg_type={} "
                                + "ledger_request_id={} ledger_msg_type={}", Long.toUnsignedString(playerId), state.domain, Long.toUnsignedString(seq),
                        Long.toUnsignedString(requestId), msgType, ledgerEntry == null ? 0 : Long.toUnsignedString(ledgerEntry.getRequestId()),
                        ledgerEntry == null ? 0 : ledgerEntry.getMsgType());
                // 什么都不确认（设计 §7.6）：本批已记账的前缀照常随存盘落地，但不会被之后的会话自动补确认——这条 MISMATCH 行还是 PENDING，
                // 每次落地都会排在前缀之后再投一遍、再次停用。解卡靠服务端 VoidItem 的强制模式（FORCE_MISMATCH）
                state.ackQueue.clear();
                halt(state, playerId, HaltReason.REPLAY_MISMATCH, "seq=" + Long.toUnsignedString(seq));
                if (recorded) {
                    requestPersist(player, state, now);
                }
                finishRound(state, now);
                return;
            }

            // 新条目（seq 在水位之上）
            InboxDecisionState.Builder decision = InboxDecisionState.newBuilder().setSeq(seq).setRequestId(requestId).setMsgType(msgType);
            if (action == ItemAction.SKIP_VOIDED) {
                // 作废：不查执行器、不解码载荷；本二进制不认识这个类型也照样跳过——这正是运维用 VoidItem 解开「未知类型停用」的路径
                decision.setOutcome(PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_SKIPPED_VALUE).setReason(item.getVoidReasonValue());
            } else if (action != ItemAction.EXECUTE) {
                log.error("[PlayerInbox] metric=inbox_unknown_type player_id={} domain={} seq={} directive={} request_id={} action={}",
                        Long.toUnsignedString(playerId), state.domain, Long.toUnsignedString(seq), item.getDirectiveValue(),
                        Long.toUnsignedString(requestId), action);
                halt(state, playerId, HaltReason.UNKNOWN_TYPE, "directive=" + item.getDirectiveValue());
                afterBatchDecided(player, state, recorded, now);
                return;
            } else {
                InboxConsumers.Executor executor = consumers.executor(msgType, state.domain);
                if (executor == null) {
                    // 本二进制不认识的类型，或类型不属于本域：版本错位。在这一条停下、不记账、不确认这一条，停用该域——绝不能当成终态的 REJECTED
                    log.error("[PlayerInbox] metric=inbox_unknown_type player_id={} domain={} seq={} msg_type={} request_id={}",
                            Long.toUnsignedString(playerId), state.domain, Long.toUnsignedString(seq), msgType, Long.toUnsignedString(requestId));
                    halt(state, playerId, HaltReason.UNKNOWN_TYPE, "msg_type=" + msgType);
                    afterBatchDecided(player, state, recorded, now);
                    return;
                }
                InboxDecisionState appliedBefore = battleDomain(state) ? appliedBattleRequest(ledger, state.domain, requestId) : null;
                ExecResult result = appliedBefore != null ? new Applied() : executor.execute(player, item.getPayload());
                if (appliedBefore != null) {
                    // 同一局（request_id = battle_id）换了新 seq 再投：收件箱库回档后 Match 首投回包丢失、重投拿到新 seq。本域最近结局环里已有
                    // 这一局的 APPLIED，奖励已随那一次落盘，不再执行，只为新 seq 记 APPLIED 并照常确认。这是有界缓解：只能看见环里（至多
                    // InboxLedgerRules.RECENT_CAPACITY 条）的结局，原结局已被挤出环时识别不了，仍会重复应用——根治要靠收件箱不回档或跨库对账。
                    log.warn("[PlayerInbox] metric=inbox_battle_duplicate_request player_id={} domain={} seq={} request_id={} original_seq={}; "
                                    + "the battle was already applied under an earlier seq, recording APPLIED without executing it again",
                            Long.toUnsignedString(playerId), state.domain, Long.toUnsignedString(seq), Long.toUnsignedString(requestId),
                            Long.toUnsignedString(appliedBefore.getSeq()));
                }
                if (result instanceof Applied) {
                    decision.setOutcome(PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_APPLIED_VALUE).setReason(0);
                } else if (result instanceof Rejected rejected) {
                    decision.setOutcome(PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_REJECTED_VALUE).setReason(rejected.reason());
                } else if (result instanceof VersionSkew skew) {
                    // 版本错位（同 C++ kVersionSkew）：与未知类型同口径——停在这一条、不记账、不确认这一条，停用该域，已定前缀照常确认。
                    // 不走 DEFER 的本地续做：错位不会在本实例里消失，续做只会空转；按旧本地副本续做也看不到服务端之后挂的 VOID。
                    // 告警走 halt 打的 metric=inbox_halted reason=version_skew，这一行补条目上下文。
                    log.error("[PlayerInbox] Executor reported version skew player_id={} domain={} seq={} type={} request_id={} reason={}",
                            Long.toUnsignedString(playerId), state.domain, Long.toUnsignedString(seq), InboxConsumers.typeName(msgType),
                            Long.toUnsignedString(requestId), Integer.toUnsignedString(skew.reason()));
                    halt(state, playerId, HaltReason.VERSION_SKEW, "seq=" + Long.toUnsignedString(seq));
                    afterBatchDecided(player, state, recorded, now);
                    return;
                } else {
                    // DEFER：严格顺序，停在这一条；已处理的前缀从本地缓存摘掉，剩下的按退避续做，不重新 Fetch（续做时仍先过前置闸）
                    Deferred deferred = (Deferred) result;
                    log.warn("[PlayerInbox] Executor deferred player_id={} domain={} seq={} type={} reason={} cause={}", Long.toUnsignedString(playerId),
                            state.domain, Long.toUnsignedString(seq), InboxConsumers.typeName(msgType), deferred.reason(), deferred.cause());
                    state.pendingItems.subList(0, consumed).clear();
                    if (recorded) {
                        requestPersist(player, state, now);
                    }
                    defer(state, playerId, DeferReason.EXECUTOR_DEFER, now);
                    return;
                }
            }
            InboxDecisionState built = decision.build();
            // 记账：与效果同一个任务，推进水位并压入环（超过 32 条挤掉最旧的）
            if (!ledger.record(state.domain, built)) {
                // 分类刚说「未见」，记账却拒绝：前置被破坏，只可能是缺陷。效果已经写了、结局却没记——停用该域、不确认这一条；已定的前缀照常确认
                log.error("[PlayerInbox] Ledger precondition violated after the effect was applied player_id={} domain={} seq={} outcome={}",
                        Long.toUnsignedString(playerId), state.domain, Long.toUnsignedString(seq), outcomeVerb(built.getOutcome()));
                halt(state, playerId, HaltReason.LEDGER_WRITE, "seq=" + Long.toUnsignedString(seq));
                afterBatchDecided(player, state, recorded, now);
                return;
            }
            recorded = true;
            state.ackQueue.add(built);
            // 可检索的审计行：库按备份恢复后，运维对照它与工单人工核对
            log.info("[PlayerInbox] {} type={} request_id={} seq={} player_id={} domain={} reason={}", outcomeVerb(built.getOutcome()),
                    InboxConsumers.typeName(msgType), Long.toUnsignedString(requestId), Long.toUnsignedString(seq), Long.toUnsignedString(playerId),
                    state.domain, Integer.toUnsignedString(built.getReason()));
        }
        state.pendingItems.clear();
        state.failStreak = 0;
        state.deferReason = DeferReason.NONE;
        afterBatchDecided(player, state, recorded, now);
    }

    // ================================================================ 拉取

    private void startFetch(ScenePlayer player, DomainState state, long now) {
        long playerId = player.playerId();
        if (!state.ackQueue.isEmpty()) {
            // 不变量：回到 IDLE 时 ackQueue 必为空（上一批全部确认之前不拉下一批）。走到这里只可能是缺陷：照发 Fetch 会让重投的条目按账本回放后
            // 与队列里的同一 seq 重复、服务端判 InvalidParameter；改为先确认、确认完再拉（确认不受前置闸限制，所以放在闸之前）
            log.error("[PlayerInbox] Fetch requested while decisions are still unacknowledged; acknowledging first player_id={} domain={} decisions={}",
                    Long.toUnsignedString(playerId), state.domain, state.ackQueue.size());
            state.recheckRequested = true;
            afterBatchDecided(player, state, false, now);
            return;
        }
        DeferReason gate = checkApplyGate(player, state.domain);
        if (gate != DeferReason.NONE) {
            defer(state, playerId, gate, now);
            return;
        }
        long epoch = player.ownerEpoch();
        long correlationId = nextCorrelationId();
        FetchPlayerInboxRequest request = FetchPlayerInboxRequest.newBuilder()
                .setPlayerId(playerId)
                .setDomainValue(state.domain)
                .setOwnerEpoch(epoch)
                .setAdmission(player.admission())
                .setCorrelationId(correlationId)
                .setMaxItems(InboxRules.FETCH_BATCH_MAX)
                // 账本水位：服务端据此检测收件箱库被恢复（CursorAhead）并抬号
                .setConsumerDecidedThrough(player.inboxLedger().decidedThrough(state.domain))
                .setSceneNodeId(sceneNodeId.getAsInt())
                .build();
        int domain = state.domain;
        state.phase = Phase.FETCHING;
        state.correlationId = correlationId;
        state.inflightSinceMs = now;
        state.sentEpoch = epoch;
        state.lastFetchMs = now;
        state.recheckRequested = false;
        transport.fetch(request, (response, failure) -> {
            if (failure != null || response == null) {
                onFetchFailed(playerId, domain, correlationId, failure);
            } else {
                onFetchReply(playerId, domain, correlationId, response);
            }
        });
    }

    /** 回包形状校验：超过一批上限、seq 为 0 或不严格递增都是协议异常。返回 null = 合法。 */
    static String describeMalformedBatch(FetchPlayerInboxResponse response) {
        if (response.getItemsCount() > InboxRules.FETCH_BATCH_MAX) {
            return "items=" + response.getItemsCount() + " exceeds batch max " + InboxRules.FETCH_BATCH_MAX;
        }
        long previous = 0;
        for (PlayerInboxItem item : response.getItemsList()) {
            if (item.getSeq() == 0) {
                return "seq 0 in batch";
            }
            if (Long.compareUnsigned(item.getSeq(), previous) <= 0) {
                return "seq " + Long.toUnsignedString(item.getSeq()) + " not strictly ascending after " + Long.toUnsignedString(previous);
            }
            previous = item.getSeq();
        }
        return null;
    }

    /** 按 player_id 找回当前实例与该域状态；实例已换 / 已移除、或该域不在等这个关联号时返回 null（调用方丢弃）。 */
    private Live live(long playerId, int domain, long correlationId, Phase expected) {
        ScenePlayer player = world == null ? null : world.playerById(playerId);
        if (player == null || player.inboxRuntime() == null) {
            return null;
        }
        DomainState state = player.inboxRuntime().domain(domain);
        if (state == null || state.phase != expected || correlationId == 0 || state.correlationId != correlationId) {
            return null;
        }
        return new Live(player, state);
    }

    private record Live(ScenePlayer player, DomainState state) {
    }

    private static boolean echoMismatch(long playerId, int domain, long correlationId, long echoedPlayer, int echoedDomain, long echoedCorrelation) {
        // 回显逐字段检查，不能因某一项为 0 而绕过其它项的不一致；丢弃后由在途超时收敛
        return (echoedPlayer != 0 && echoedPlayer != playerId) || (echoedDomain != 0 && echoedDomain != domain)
                || (echoedCorrelation != 0 && echoedCorrelation != correlationId);
    }

    void onFetchReply(long playerId, int domain, long correlationId, FetchPlayerInboxResponse response) {
        if (echoMismatch(playerId, domain, correlationId, response.getPlayerId(), response.getDomainValue(), response.getCorrelationId())) {
            log.error("[PlayerInbox] Fetch reply echo mismatch captured_player_id={} captured_domain={} captured_corr={} echoed_player_id={} "
                            + "echoed_domain={} echoed_corr={}", Long.toUnsignedString(playerId), domain, correlationId,
                    Long.toUnsignedString(response.getPlayerId()), response.getDomainValue(), response.getCorrelationId());
            return;
        }
        Live live = live(playerId, domain, correlationId, Phase.FETCHING);
        if (live == null) {
            log.debug("[PlayerInbox] Fetch reply discarded (instance gone, recreated or request superseded) player_id={} domain={} corr={}",
                    Long.toUnsignedString(playerId), domain, correlationId);
            return;
        }
        DomainState state = live.state();
        long now = nowMs();
        state.phase = Phase.IDLE;
        state.correlationId = 0;
        state.inflightSinceMs = 0;

        PlayerInboxFenceVerdict fence = response.getFence();
        if (fence == PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_STALE) {
            log.error("[PlayerInbox] metric=inbox_stale_owner op=fetch player_id={} domain={} sent_epoch={}", Long.toUnsignedString(playerId), domain,
                    state.sentEpoch);
            scheduleRetry(state, playerId, DeferReason.STALE_OWNER, InboxRules.STALE_OWNER_BACKOFF_MS, now);
            return;
        }
        if (fence == PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_EPOCH_REQUIRED || fence == PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_EPOCH_AHEAD) {
            // 缺陷或伪造（IB-8）：停用，不重试
            halt(state, playerId, fence == PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_EPOCH_REQUIRED ? HaltReason.EPOCH_REQUIRED
                    : HaltReason.EPOCH_AHEAD, "op=fetch sent_epoch=" + state.sentEpoch);
            finishRound(state, now);
            return;
        }
        int tip = response.getErrorMessage().getId();
        if (!tipOk(tip)) {
            // 服务端故障（例如 kPlayerInboxStoreUnavailable）：退避，不计协议异常。kInvalidParameter 目前也按这一条退避（同 mmorpg 小取舍第 11 条）
            if (state.deferReason != DeferReason.SERVER_ERROR) {
                log.warn("[PlayerInbox] Fetch answered with tip={} player_id={} domain={}", tip, Long.toUnsignedString(playerId), domain);
            }
            defer(state, playerId, DeferReason.SERVER_ERROR, now);
            return;
        }
        if (fence == PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_NO_STREAM || response.getItemsCount() == 0) {
            // 本轮结束。空批不信 has_more，免得对着一个空回包反复空转
            state.failStreak = 0;
            state.deferReason = DeferReason.NONE;
            state.hasMore = false;
            finishRound(state, now);
            return;
        }
        if (fence != PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_FIRST && fence != PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_MATCH
                && fence != PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_ADVANCE) {
            log.error("[PlayerInbox] Fetch reply carries items under unexpected fence={} player_id={} domain={}", fence,
                    Long.toUnsignedString(playerId), domain);
            defer(state, playerId, DeferReason.SERVER_ERROR, now);
            return;
        }
        String malformed = describeMalformedBatch(response);
        if (malformed != null) {
            halt(state, playerId, HaltReason.BATCH_MALFORMED, malformed);
            finishRound(state, now);
            return;
        }
        state.hasMore = response.getHasMore();
        state.pendingItems.clear();
        state.pendingItems.addAll(response.getItemsList());
        state.phase = Phase.APPLYING;
        // 回包到达、准备应用之前再判一次前置闸（在 applyPending 里）
        applyPending(live.player(), state, now);
    }

    void onFetchFailed(long playerId, int domain, long correlationId, Throwable failure) {
        Live live = live(playerId, domain, correlationId, Phase.FETCHING);
        if (live == null) {
            log.debug("[PlayerInbox] Fetch failure discarded (instance gone or request superseded) player_id={} domain={}",
                    Long.toUnsignedString(playerId), domain);
            return;
        }
        DomainState state = live.state();
        long now = nowMs();
        state.phase = Phase.IDLE;
        state.correlationId = 0;
        state.inflightSinceMs = 0;
        if (state.deferReason != DeferReason.TRANSPORT) {
            log.warn("[PlayerInbox] Fetch failed (result unknown) player_id={} domain={} detail={}", Long.toUnsignedString(playerId), domain,
                    String.valueOf(failure));
        }
        defer(state, playerId, DeferReason.TRANSPORT, now);
    }

    void onAckReply(long playerId, int domain, long correlationId, AckPlayerInboxResponse response) {
        if (echoMismatch(playerId, domain, correlationId, response.getPlayerId(), response.getDomainValue(), response.getCorrelationId())) {
            log.error("[PlayerInbox] Ack reply echo mismatch captured_player_id={} captured_domain={} captured_corr={} echoed_player_id={} "
                            + "echoed_domain={} echoed_corr={}", Long.toUnsignedString(playerId), domain, correlationId,
                    Long.toUnsignedString(response.getPlayerId()), response.getDomainValue(), response.getCorrelationId());
            return;
        }
        Live live = live(playerId, domain, correlationId, Phase.ACKING);
        if (live == null) {
            log.debug("[PlayerInbox] Ack reply discarded (instance gone, recreated or request superseded) player_id={} domain={} corr={}",
                    Long.toUnsignedString(playerId), domain, correlationId);
            return;
        }
        DomainState state = live.state();
        long now = nowMs();
        state.phase = Phase.AWAIT_DURABLE;
        state.correlationId = 0;
        state.inflightSinceMs = 0;

        PlayerInboxFenceVerdict fence = response.getFence();
        if (fence == PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_STALE) {
            // 新 owner 已经推进了围栏：它加载的存档里已有这些结局，会自己补确认（设计 §7.7、§7.10）
            log.warn("[PlayerInbox] Ack fenced off as STALE; the newer owner re-acknowledges from its persisted ledger player_id={} domain={} decisions={}",
                    Long.toUnsignedString(playerId), domain, state.ackQueue.size());
            state.ackQueue.clear();
            finishRound(state, now);
            // 已停用的域不再安排重试：再排一次只会打出「60 秒后重试」的 inbox_deferred，把永久停用伪装成暂时延后（编译前复核 SM-1）
            if (!state.halted) {
                scheduleRetry(state, playerId, DeferReason.STALE_OWNER, InboxRules.STALE_OWNER_BACKOFF_MS, now);
            }
            return;
        }
        if (fence == PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_EPOCH_REQUIRED || fence == PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_EPOCH_AHEAD) {
            state.ackQueue.clear();
            halt(state, playerId, fence == PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_EPOCH_REQUIRED ? HaltReason.EPOCH_REQUIRED
                    : HaltReason.EPOCH_AHEAD, "op=ack");
            finishRound(state, now);
            return;
        }
        int tip = response.getErrorMessage().getId();
        if (!tipOk(tip)) {
            if (tip == INVALID_PARAMETER) {
                // 确定性协议错误（评审 L2）：同一份 ackQueue 重发只会得到同一个答复。按协议异常停用、不重试；这些结局都已落盘，
                // 实例下次在本节点新建时会按账本回放、重新确认
                log.error("[PlayerInbox] metric=inbox_ack_anomaly player_id={} domain={} detail=tip={} decisions={}", Long.toUnsignedString(playerId),
                        domain, tip, state.ackQueue.size());
                state.ackQueue.clear();
                halt(state, playerId, HaltReason.ACK_ANOMALY, "op=ack tip=" + tip);
                finishRound(state, now);
                return;
            }
            // 服务端故障：保留 ackQueue，退避重发
            state.ackFailStreak = saturatingIncrement(state.ackFailStreak);
            state.nextAckMs = now + InboxRules.backoffMs(state.ackFailStreak, jitter());
            log.warn("[PlayerInbox] Ack answered with tip={} player_id={} domain={} streak={}", tip, Long.toUnsignedString(playerId), domain,
                    state.ackFailStreak);
            return;
        }
        // 逐条结果：FINALIZED / ALREADY_SAME 视为成功；其余全是协议异常——记 ERROR、停用、不重试（设计 §7.7）
        String anomaly = null;
        if (response.getResultsCount() != state.ackQueue.size()) {
            anomaly = "results=" + response.getResultsCount() + " decisions=" + state.ackQueue.size();
        } else {
            for (int i = 0; i < response.getResultsCount(); i++) {
                PlayerInboxAckItem result = response.getResults(i);
                InboxDecisionState decision = state.ackQueue.get(i);
                if (result.getSeq() != decision.getSeq()) {
                    anomaly = "result[" + i + "] seq=" + Long.toUnsignedString(result.getSeq()) + " expected=" + Long.toUnsignedString(decision.getSeq());
                    break;
                }
                if (result.getResult() != PlayerInboxAckResult.PLAYER_INBOX_ACK_FINALIZED
                        && result.getResult() != PlayerInboxAckResult.PLAYER_INBOX_ACK_ALREADY_SAME) {
                    anomaly = "seq=" + Long.toUnsignedString(result.getSeq()) + " result=" + result.getResult() + " state=" + result.getState();
                    break;
                }
            }
        }
        if (anomaly != null) {
            log.error("[PlayerInbox] metric=inbox_ack_anomaly player_id={} domain={} detail={}", Long.toUnsignedString(playerId), domain, anomaly);
            state.ackQueue.clear();
            halt(state, playerId, HaltReason.ACK_ANOMALY, anomaly);
            finishRound(state, now);
            return;
        }
        log.debug("[PlayerInbox] Acknowledged {} decision(s) player_id={} domain={}", state.ackQueue.size(), Long.toUnsignedString(playerId), domain);
        state.ackQueue.clear();
        state.ackFailStreak = 0;
        finishRound(state, now);
    }

    void onAckFailed(long playerId, int domain, long correlationId, Throwable failure) {
        Live live = live(playerId, domain, correlationId, Phase.ACKING);
        if (live == null) {
            log.debug("[PlayerInbox] Ack failure discarded (instance gone or request superseded) player_id={} domain={}",
                    Long.toUnsignedString(playerId), domain);
            return;
        }
        DomainState state = live.state();
        long now = nowMs();
        state.phase = Phase.AWAIT_DURABLE;
        state.correlationId = 0;
        state.inflightSinceMs = 0;
        // Ack 请求失败：保留 ackQueue，退避重发。实例若在这期间被移除，下次落地时 Fetch 会再次返回这些 PENDING 条目，届时从账本补确认
        state.ackFailStreak = saturatingIncrement(state.ackFailStreak);
        state.nextAckMs = now + InboxRules.backoffMs(state.ackFailStreak, jitter());
        log.warn("[PlayerInbox] Ack failed (result unknown; will resend) player_id={} domain={} detail={} streak={}", Long.toUnsignedString(playerId),
                domain, String.valueOf(failure), state.ackFailStreak);
    }

    /** 在途请求超过期限仍无完成通知：按传输失败收场（结果未知）。迟到的回包对不上关联号，会被丢弃。 */
    private void handleInflightTimeout(ScenePlayer player, DomainState state, long now) {
        long playerId = player.playerId();
        log.warn("[PlayerInbox] Request got no completion in time; treating as transport failure player_id={} domain={} phase={} corr={} inflight_ms={}",
                Long.toUnsignedString(playerId), state.domain, state.phase.label(), state.correlationId, now - state.inflightSinceMs);
        state.correlationId = 0;
        state.inflightSinceMs = 0;
        if (state.phase == Phase.FETCHING) {
            state.phase = Phase.IDLE;
            defer(state, playerId, DeferReason.TRANSPORT, now);
            return;
        }
        state.phase = Phase.AWAIT_DURABLE;
        state.ackFailStreak = saturatingIncrement(state.ackFailStreak);
        state.nextAckMs = now + InboxRules.backoffMs(state.ackFailStreak, jitter());
    }

    // ================================================================ 小工具

    private static boolean tipOk(int tip) {
        return tip == 0 || tip == SUCCESS_TIP;
    }

    private static int saturatingIncrement(int value) {
        return value < Integer.MAX_VALUE ? value + 1 : value;
    }

    private static String outcomeVerb(int outcome) {
        return switch (outcome) {
            case PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_APPLIED_VALUE -> "applied";
            case PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_REJECTED_VALUE -> "rejected";
            case PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_SKIPPED_VALUE -> "skipped";
            case PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_UNKNOWN_VALUE -> "unknown";
            default -> "?";
        };
    }

    /** {@link InboxLedgerRules} 的结局常量与契约枚举同值（编译期之外的一道核对：启动时不一致直接拒启）。 */
    static {
        if (InboxLedgerRules.OUTCOME_APPLIED != PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_APPLIED_VALUE
                || InboxLedgerRules.OUTCOME_REJECTED != PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_REJECTED_VALUE
                || InboxLedgerRules.OUTCOME_SKIPPED != PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_SKIPPED_VALUE
                || InboxLedgerRules.OUTCOME_UNKNOWN != PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_UNKNOWN_VALUE) {
            throw new IllegalStateException("InboxLedgerRules 的结局常量与 PlayerInboxOutcome 不一致");
        }
        if (PlayerInboxDomain.PLAYER_INBOX_DOMAIN_SCENE_GM_SANCTION_VALUE == 0) {
            throw new IllegalStateException("GM 处置域号不能为 0");
        }
    }
}
