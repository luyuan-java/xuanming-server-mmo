package com.game.inbox;

import com.game.inbox.registry.InboxRegistry;
import com.game.inbox.rules.InboxRules;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * game-inbox 的配置（前缀 {@code game.inbox}；{@code game.redis.*}、{@code game.namespace} 由 game-discovery 绑定）。构造时校验，不满足即拒启——
 * 每一条拒绝的都是「进程能起来、但行为静默出错」的配置（mmorpg {@code config.Validate}）。
 *
 * @param enqueueTypes          入箱白名单：PlayerInboxMessageType 的枚举名（缺省空 = 全部停用）。<b>权威值在 git 跟踪的 application.yaml</b>
 *                              （设计 §3.3：开关只有一个来源）；v1 只放封锁 / 解封，列进挂欠款即拒启（{@link InboxRegistry#parseEnqueueTypes}）
 * @param requestBudget         一次 Fetch / Ack / 运维请求的预算（≤ 3.5s：scene 的 Dubbo 调用超时是 4s）
 * @param workerThreads         Fetch / Ack 的工作池大小（阻塞 JDBC 只在这里跑）
 * @param workerQueueCapacity   工作池队列上限；满了回 StoreUnavailable（scene 退避）
 * @param terminalRetention     终态行保留期（缺省 30d），超过后由清理循环物理删除；必须大于签发窗 7d（生产方契约：遵守契约的重试不会重复入箱）
 * @param sweepInterval         两轮清理之间的间隔（每轮最多删 500 行）
 * @param notifyResendInterval  提醒补发循环的间隔（设计 §6.3：30 秒一轮）
 * @param gaugeInterval         扫描型 gauge 的刷新间隔
 * @param reconcileInterval     对账间隔（缺省 60m；0 = 不跑）
 * @param reconcileWindow       对账只看最近这么久内由消费方终结的行（缺省 24h）；对账开启时必须 ≥ 2 × 间隔（一轮失败或易主就会有行一次都没核对就滑出窗口）
 * @param leaderTtl             后台维护领导锁的 TTL（续期间隔 = TTL/3）
 */
@ConfigurationProperties("game.inbox")
public record InboxProperties(
        @DefaultValue List<String> enqueueTypes,
        @DefaultValue("3s") Duration requestBudget,
        @DefaultValue("8") int workerThreads,
        @DefaultValue("1024") int workerQueueCapacity,
        @DefaultValue("30d") Duration terminalRetention,
        @DefaultValue("10m") Duration sweepInterval,
        @DefaultValue("30s") Duration notifyResendInterval,
        @DefaultValue("60s") Duration gaugeInterval,
        @DefaultValue("60m") Duration reconcileInterval,
        @DefaultValue("24h") Duration reconcileWindow,
        @DefaultValue("30s") Duration leaderTtl) {

    public InboxProperties {
        enqueueTypes = enqueueTypes == null ? List.of() : List.copyOf(enqueueTypes);
        // 解析一遍：未知名 / 重复 / v1 未开放的类型在这里就拒启（装配层还会再解析一次拿集合，同一个函数）
        InboxRegistry.parseEnqueueTypes(enqueueTypes);
        if (requestBudget.isNegative() || requestBudget.isZero() || requestBudget.compareTo(Duration.ofMillis(3500)) > 0) {
            throw new IllegalArgumentException("game.inbox.request-budget 必须在 (0, 3.5s] 内（scene 的 Dubbo 调用超时是 4s）: " + requestBudget);
        }
        if (workerThreads < 1 || workerQueueCapacity < 1) {
            throw new IllegalArgumentException("game.inbox.worker-threads / worker-queue-capacity 必须为正");
        }
        if (terminalRetention.compareTo(InboxRules.ISSUED_AT_MAX_PAST) <= 0) {
            throw new IllegalArgumentException("game.inbox.terminal-retention 必须大于签发窗 " + InboxRules.ISSUED_AT_MAX_PAST + ": " + terminalRetention);
        }
        if (!positive(sweepInterval) || !positive(notifyResendInterval) || !positive(gaugeInterval) || reconcileInterval.isNegative()) {
            throw new IllegalArgumentException("game.inbox 的循环间隔必须为正（reconcile-interval 可以是 0 = 不跑）");
        }
        if (!reconcileInterval.isZero() && (!positive(reconcileWindow) || reconcileWindow.compareTo(reconcileInterval.multipliedBy(2)) < 0)) {
            throw new IllegalArgumentException("game.inbox.reconcile-window 必须覆盖至少两轮 reconcile-interval: window=" + reconcileWindow
                    + " interval=" + reconcileInterval);
        }
        if (leaderTtl.compareTo(Duration.ofSeconds(3)) < 0 || leaderTtl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("game.inbox.leader-ttl 必须在 3s～5min 之间: " + leaderTtl);
        }
    }

    private static boolean positive(Duration d) {
        return d != null && !d.isNegative() && !d.isZero();
    }
}
