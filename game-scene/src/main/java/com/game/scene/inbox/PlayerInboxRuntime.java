package com.game.scene.inbox;

import com.game.player.store.state.InboxDecisionState;
import com.game.proto.player_inbox.PlayerInboxItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一名玩家的收件箱运行时状态（mmorpg C++ {@code PlayerInboxRuntimeComp}，设计 §7.2）：<b>不落库</b>、随实例清空，每个消费域一份。
 * 只在场景逻辑线程上读写。实例上还没有它 = 还没在本节点落地过（落地钩子据此判「新落地」）；同一 epoch 接替旧实例时整份随实例转交（等同 C++ 的
 * 「同节点重复路由不算新落地」），换 epoch 的新实例从空状态起步、无条件拉一次。
 */
public final class PlayerInboxRuntime {

    /** 一个域的阶段。 */
    public enum Phase {
        IDLE, FETCHING, APPLYING, AWAIT_DURABLE, ACKING;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 延后原因（{@code metric=inbox_deferred reason=}，取值同 mmorpg；Java 没有异步退出期与「找不到收件箱节点」，exiting / no_node 不会出现）。 */
    public enum DeferReason {
        NONE, DISABLED, FROZEN, HANDOFF, EXITING, EPOCH_ZERO, LEDGER_INVALID, IN_BATTLE, NO_NODE, TRANSPORT, UNIMPLEMENTED, STALE_OWNER,
        SERVER_ERROR, EXECUTOR_DEFER;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 停用原因（{@code metric=inbox_halted reason=}）。停用到实例下次在本节点新建为止。 */
    public enum HaltReason {
        NONE, EPOCH_REQUIRED, EPOCH_AHEAD, UNKNOWN_TYPE, REPLAY_MISMATCH, BATCH_MALFORMED, LEDGER_WRITE, ACK_ANOMALY;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 一个域的运行时状态（字段由 {@link InboxService} 读写，这里只是载体）。 */
    public static final class DomainState {
        final int domain;
        Phase phase = Phase.IDLE;
        /** 在途请求的关联号（节点级单调递增，0 = 没有在途）。 */
        long correlationId;
        /** 在途请求发出的时刻（单调毫秒；0 = 没有在途）。 */
        long inflightSinceMs;
        /** 最近一次 Fetch 带的 owner_epoch（只进日志）。 */
        long sentEpoch;
        /** 本地缓存的、还没处理的条目（按 seq 升序；只在 APPLYING 非空）。 */
        final List<PlayerInboxItem> pendingItems = new ArrayList<>();
        /** 已定、待落盘后确认的结局（按 seq 升序）。回到 IDLE 时必为空。 */
        final List<InboxDecisionState> ackQueue = new ArrayList<>();
        boolean recheckRequested;
        boolean hasMore;
        /** 下一次检查的时刻（单调毫秒；0 = 没有安排）。 */
        long nextCheckMs;
        int failStreak;
        DeferReason deferReason = DeferReason.NONE;
        long lastFetchMs;
        long nextAckMs;
        int ackFailStreak;
        long lastPersistRequestMs;
        boolean durableMismatchReported;
        boolean halted;
        HaltReason haltReason = HaltReason.NONE;
        /** 下一次在线有界兜底的时刻（单调毫秒；0 = 关闭）。 */
        long nextPeriodicMs;

        DomainState(int domain) {
            this.domain = domain;
        }

        public int domain() {
            return domain;
        }

        public Phase phase() {
            return phase;
        }

        public boolean halted() {
            return halted;
        }

        public HaltReason haltReason() {
            return haltReason;
        }

        public DeferReason deferReason() {
            return deferReason;
        }

        public List<InboxDecisionState> ackQueue() {
            return List.copyOf(ackQueue);
        }

        public int pendingCount() {
            return pendingItems.size();
        }

        public long nextCheckMs() {
            return nextCheckMs;
        }
    }

    private final List<DomainState> domains = new ArrayList<>();

    /** 某个域的状态；没有为 null。按 domain 字段查找，不靠下标。 */
    public DomainState domain(int domain) {
        for (DomainState state : domains) {
            if (state.domain == domain) {
                return state;
            }
        }
        return null;
    }

    DomainState domainForWrite(int domain) {
        DomainState existing = domain(domain);
        if (existing != null) {
            return existing;
        }
        DomainState added = new DomainState(domain);
        domains.add(added);
        return added;
    }

    public List<DomainState> domains() {
        return domains;
    }
}
