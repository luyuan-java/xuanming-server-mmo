package com.game.scene;

import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.storage.HandOffSettings;
import com.game.scene.transfer.SceneManagerSwitchTargets;
import com.game.scene.world.SceneInstances;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;

/**
 * 场景节点配置（前缀 {@code xm}；{@code xm.redis.*} 由 xm-discovery 的 RedisProperties 绑定，这里不重复）。
 *
 * @param zoneId        本节点所属 zone；gate 链路握手时必须一致
 * @param advertiseHost 写进节点目录的链路地址（gate 按它连本节点）
 * @param tableDir      配置表 .pb 目录；默认相对路径 {@code config-data/tables}，要求进程从仓库根目录启动，
 *                      否则配成绝对路径
 * @param scene         场景节点自身的参数
 * @param runMode       运行模式（{@code RunMode}）：只有 dev / test 放行 GM 类客户端指令，其余一律按 prod 拒绝
 */
@ConfigurationProperties("xm")
public record SceneNodeProperties(
        @DefaultValue("1") int zoneId,
        @DefaultValue("127.0.0.1") String advertiseHost,
        @DefaultValue("config-data/tables") String tableDir,
        @DefaultValue SceneSettings scene,
        @DefaultValue("prod") String runMode) {

    public SceneNodeProperties {
        if (zoneId <= 0) {
            throw new IllegalArgumentException("xm.zone-id 必须为正数: " + zoneId);
        }
        if (advertiseHost == null || advertiseHost.isBlank()) {
            throw new IllegalArgumentException("xm.advertise-host 不能为空");
        }
    }

    /**
     * @param linkBindHost          节点链路监听地址。握手有共享密钥鉴权（XM_NODE_LINK_SECRET），但链路不加密、
     *                              60s 窗口内的握手可被重放，所以默认只绑本机；多机部署时改成内网地址，
     *                              仍要靠网络隔离保证只有 gate 能连
     * @param linkPort              节点链路端口（0 = 系统分配，目录里发布实际端口）
     * @param linkIoThreads         链路 I/O 线程数（gate 连接数很少）
     * @param linkHandshakeTimeout  连上后多久内必须完成握手
     * @param storageThreads        存储线程池大小（MySQL 阻塞调用）
     * @param storageQueueCapacity  存储线程池队列上限；满了新进场失败（fail-closed），写回丢弃并告警
     * @param shutdownSaveTimeout   停服时等待写回完成的上限（写回任务在逻辑线程上排队 + 落库，共用这一个预算）
     * @param linkMaxPendingFrames  每条 gate 链路已投递给逻辑线程、还没执行完的帧数上限；达到即暂停读这条链路，
     *                              降到一半以下恢复（背压，逻辑线程的任务队列因此有界）
     * @param auditQueueCapacity    审计线程（资产流水发往 Kafka）的队列上限；满了新记录只写兜底日志
     * @param auditMaxBlock         审计线程上 Kafka 发送的最长阻塞（元数据 / 缓冲；只阻塞审计线程）
     * @param auditFlushTimeout     停服时等审计记录发完的上限
     * @param snapshotMaxBytes      一份玩家快照序列化后的上限（超了丢弃并记 ERROR；须小于 Kafka 生产者 / broker 的单条消息上限 1MB）
     * @param saveInterval          在线周期存盘的周期（整秒；同基线 SCENE_PLAYER_SAVE_INTERVAL_SECONDS，缺省 300s）：
     *                              每人每周期至多写一次、没变化不写；0 = 关闭（只在离场时写回）。不带单位的数字按秒
     * @param gainBlockRefresh      全服产出封禁名单的兜底重读周期（变更通知走 Redis pub/sub，可能丢）
     * @param anomaly               获取异常检测的阈值
     * @param assetRpcPort          通用资产通道 Dubbo Triple 提供方的端口（缺省 21100，link 21000 旁边；同机起多个 scene 实例时必须各不相同，
     *                              与 link-port 一样）。导出成功后与 {@code xm.advertise-host} 一起写进节点目录的 rpc_host / rpc_port
     * @param assetOpMaxInflight    资产通道在途调用上限（缺省 256）：超出直接回失败（过载，调用方按传输失败重投），不排队进逻辑线程；
     *                              应 ≥ 调用方副本数 × 每副本 Workers + 同步投递并发（guild-economy-spec Q9）
     * @param channelPlanPollInterval 主世界频道计划的拉取周期（缺省 1s，100ms～1min；scene-channels-spec §4.10.1、§5.3）：每周期读一次版本号，
     *                              变了才整读计划；排空推进另挂在每秒任务上，与它无关
     * @param transferLeaseMargin   跨节点换图交出归属时要求的剩余租约下限 M（缺省 15s；scene-handoff-spec §5.2、§6.2）：
     *                              须在续约周期 10s 与 租约 − 续约周期 20s 之间（不含端点），见 {@link HandOffSettings}
     * @param transferProbeStatementTimeout 交出事务（不含提交）与交出探测加锁读的时限（缺省 3s，≥ 1s 且小于 transfer-lease-margin）：
     *                              行锁被占时尝试以超时失败（重试 / 探测），而不是在冻结里一直等
     * @param sceneManagerUrl       跨节点换图选目标（scene-manager {@code selectSwitchTarget}）的直连地址（缺省 {@code tri://127.0.0.1:20882}，
     *                              与 login 同；scene-handoff-spec §5.4、§6.2）。本批只支持直连（scene 没带 Nacos 注册中心依赖），不能为空
     * @param switchResolveTimeout  选目标的本地兜底超时（缺省 4s；须大于 scene-manager 的 Dubbo 提供方超时 3s、不超过 30s）：到时推 23 {1003}，
     *                              也决定 63 在途槽（RESOLVING，期间再发 63 回 3014）的寿命
     * @param transferTombstoneTtl  交出墓碑的存活时长（缺省 30s，1s～5min）：与 PlayerTransfer 在链路上交叉的 PlayerLeave 靠它按旧 epoch 补写位置
     * @param instance              镜像 / 副本实例的回收与上限（批次 5.3，{@code xm.scene.instance.*}，dungeon-mirror-spec §7.2）
     */
    public record SceneSettings(
            @DefaultValue("127.0.0.1") String linkBindHost,
            @DefaultValue("21000") int linkPort,
            @DefaultValue("2") int linkIoThreads,
            @DefaultValue("10s") Duration linkHandshakeTimeout,
            @DefaultValue("4") int storageThreads,
            @DefaultValue("10000") int storageQueueCapacity,
            @DefaultValue("15s") Duration shutdownSaveTimeout,
            @DefaultValue("10000") int linkMaxPendingFrames,
            @DefaultValue("300s") @DurationUnit(ChronoUnit.SECONDS) Duration saveInterval,
            @DefaultValue("10000") int auditQueueCapacity,
            @DefaultValue("2s") Duration auditMaxBlock,
            @DefaultValue("5s") Duration auditFlushTimeout,
            @DefaultValue("1000000") int snapshotMaxBytes,
            @DefaultValue("10s") Duration gainBlockRefresh,
            @DefaultValue AnomalySettings anomaly,
            @DefaultValue("21100") int assetRpcPort,
            @DefaultValue("256") int assetOpMaxInflight,
            @DefaultValue("1s") Duration channelPlanPollInterval,
            @DefaultValue("15s") Duration transferLeaseMargin,
            @DefaultValue("3s") Duration transferProbeStatementTimeout,
            @DefaultValue("tri://127.0.0.1:20882") String sceneManagerUrl,
            @DefaultValue("4s") Duration switchResolveTimeout,
            @DefaultValue("30s") Duration transferTombstoneTtl,
            @DefaultValue InstanceSettings instance) {

        public SceneSettings {
            // 启动期校验（不满足即拒启）：续约周期 < M < 租约 − 续约周期，语句时限 < M
            new HandOffSettings(transferLeaseMargin, transferProbeStatementTimeout);
            if (sceneManagerUrl == null || sceneManagerUrl.isBlank()) {
                throw new IllegalArgumentException("xm.scene.scene-manager-url 不能为空（跨节点换图选目标的直连地址）");
            }
            // 本地兜底超时必须大于 Dubbo 单次超时：正常的超时由 Dubbo 先报，兜底只管建引用卡住等情形
            if (switchResolveTimeout.compareTo(SceneManagerSwitchTargets.DUBBO_TIMEOUT) <= 0
                    || switchResolveTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException("xm.scene.switch-resolve-timeout 必须大于 scene-manager 的 Dubbo 超时 "
                        + SceneManagerSwitchTargets.DUBBO_TIMEOUT + " 且不超过 30s: " + switchResolveTimeout);
            }
            if (transferTombstoneTtl.compareTo(Duration.ofSeconds(1)) < 0
                    || transferTombstoneTtl.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("xm.scene.transfer-tombstone-ttl 必须在 1s～5min 之间: " + transferTombstoneTtl);
            }
            if (channelPlanPollInterval.compareTo(Duration.ofMillis(100)) < 0
                    || channelPlanPollInterval.compareTo(Duration.ofMinutes(1)) > 0) {
                throw new IllegalArgumentException("xm.scene.channel-plan-poll-interval 必须在 100ms～1min 之间: "
                        + channelPlanPollInterval);
            }
            if (assetRpcPort < 1 || assetRpcPort > 65535) {
                throw new IllegalArgumentException("xm.scene.asset-rpc-port 超出范围: " + assetRpcPort);
            }
            if (assetOpMaxInflight < 1) {
                throw new IllegalArgumentException("xm.scene.asset-op-max-inflight 至少为 1: " + assetOpMaxInflight);
            }
            if (gainBlockRefresh.compareTo(Duration.ofSeconds(1)) < 0) {
                throw new IllegalArgumentException("xm.scene.gain-block-refresh 至少 1s: " + gainBlockRefresh);
            }
            if (linkPort < 0 || linkPort > 65535) {
                throw new IllegalArgumentException("xm.scene.link-port 超出范围: " + linkPort);
            }
            if (linkIoThreads < 1 || storageThreads < 1 || storageQueueCapacity < 1) {
                throw new IllegalArgumentException("xm.scene 的线程数与队列上限必须为正数");
            }
            if (auditQueueCapacity < 1 || auditMaxBlock.isNegative() || auditFlushTimeout.isNegative()
                    || snapshotMaxBytes < 1) {
                throw new IllegalArgumentException("xm.scene.audit-* 配置非法");
            }
            if (linkMaxPendingFrames < 2) {
                throw new IllegalArgumentException("xm.scene.link-max-pending-frames 至少为 2: " + linkMaxPendingFrames);
            }
            if (saveInterval.isNegative() || saveInterval.toMillis() % 1000 != 0 || saveInterval.toSeconds() > 86_400) {
                throw new IllegalArgumentException("xm.scene.save-interval 必须是 0 到 1 天之间的整秒: " + saveInterval);
            }
        }

        /** 交出归属在存储层的参数（已在构造时校验）。 */
        public HandOffSettings handOff() {
            return new HandOffSettings(transferLeaseMargin, transferProbeStatementTimeout);
        }
    }

    /**
     * 镜像 / 副本实例（{@code xm.scene.instance}，批次 5.3，dungeon-mirror-spec §6.10、§7.2）。不带单位的数字按秒；不满足校验拒启。
     *
     * @param mirrorIdleTimeout 镜像空置多久进入回收宽限（缺省 30s，≥ 0；0 = 用 {@code idle-timeout}，同基线 MirrorIdleTimeoutSeconds）
     * @param idleTimeout       副本（及回落到它的镜像）空置多久进入回收宽限（缺省 300s，≥ 0；0 = 不自动回收，同基线 InstanceIdleTimeoutSeconds）
     * @param reclaimGrace      回收宽限（缺省 30s，≥ 10s）：宽限内不接本地新进入、在途进场到达即复活；宽限满且仍空才销毁
     * @param maxPerNode        本节点实例数上限（缺省 200，1..10000；超限 63 镜像回 3005）
     * @param maxPerCreator     本节点上同一玩家创建的镜像数上限（缺省 3，1..100；超限 3005）
     */
    public record InstanceSettings(
            @DefaultValue("30s") @DurationUnit(ChronoUnit.SECONDS) Duration mirrorIdleTimeout,
            @DefaultValue("300s") @DurationUnit(ChronoUnit.SECONDS) Duration idleTimeout,
            @DefaultValue("30s") @DurationUnit(ChronoUnit.SECONDS) Duration reclaimGrace,
            @DefaultValue("200") int maxPerNode,
            @DefaultValue("3") int maxPerCreator) {

        public InstanceSettings {
            SceneInstances.validate(mirrorIdleTimeout, idleTimeout, reclaimGrace, maxPerNode, maxPerCreator);
        }
    }

    /**
     * 获取异常检测（{@code xm.scene.anomaly}；缺省同基线 AnomalyThreshold：600 秒内超过 50 次或累计超过 100000 即告警）。
     * 某一维填 0 只关掉这一维，两维都填 0 关掉检测。窗口 1 秒到 1 天，不带单位的数字按秒。
     *
     * @param currency 按币种覆盖（键是币种号，如 {@code xm.scene.anomaly.currency.0.max-amount=5000000}）；
     *                 覆盖里没写的项取内置缺省（600s / 50 / 100000），不继承上面的全局值
     * @param item     按物品配置覆盖（键是物品配置号，如 {@code xm.scene.anomaly.item.10.max-count=200}），规则同上
     */
    public record AnomalySettings(
            @DefaultValue("600s") @DurationUnit(ChronoUnit.SECONDS) Duration window,
            @DefaultValue("50") int maxCount,
            @DefaultValue("100000") long maxAmount,
            Map<Integer, Threshold> currency,
            Map<Integer, Threshold> item) {

        public AnomalySettings {
            // 紧凑构造器里字段还没赋值，校验与归一只能用参数
            new GainAnomalyDetector.Threshold(window, maxCount, maxAmount);
            currency = currency == null ? Map.of() : Map.copyOf(currency);
            item = item == null ? Map.of() : Map.copyOf(item);
        }

        public GainAnomalyDetector.Threshold defaults() {
            return new GainAnomalyDetector.Threshold(window, maxCount, maxAmount);
        }

        public Map<Integer, GainAnomalyDetector.Threshold> currencyThresholds() {
            return thresholds(currency);
        }

        public Map<Integer, GainAnomalyDetector.Threshold> itemThresholds() {
            return thresholds(item);
        }

        private static Map<Integer, GainAnomalyDetector.Threshold> thresholds(Map<Integer, Threshold> overrides) {
            Map<Integer, GainAnomalyDetector.Threshold> out = new HashMap<>();
            overrides.forEach((key, t) -> out.put(key, new GainAnomalyDetector.Threshold(t.window(), t.maxCount(),
                    t.maxAmount())));
            return out;
        }
    }

    /** 某币种 / 物品配置的阈值覆盖。 */
    public record Threshold(
            @DefaultValue("600s") @DurationUnit(ChronoUnit.SECONDS) Duration window,
            @DefaultValue("50") int maxCount,
            @DefaultValue("100000") long maxAmount) {

        public Threshold {
            new GainAnomalyDetector.Threshold(window, maxCount, maxAmount);
        }
    }
}
