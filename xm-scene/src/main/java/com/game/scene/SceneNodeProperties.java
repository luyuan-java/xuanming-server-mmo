package com.game.scene;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
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
 */
@ConfigurationProperties("xm")
public record SceneNodeProperties(
        @DefaultValue("1") int zoneId,
        @DefaultValue("127.0.0.1") String advertiseHost,
        @DefaultValue("config-data/tables") String tableDir,
        @DefaultValue SceneSettings scene) {

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
     * @param saveInterval          在线周期存盘的周期（整秒；同基线 SCENE_PLAYER_SAVE_INTERVAL_SECONDS，缺省 300s）：
     *                              每人每周期至多写一次、没变化不写；0 = 关闭（只在离场时写回）。不带单位的数字按秒
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
            @DefaultValue("300s") @DurationUnit(ChronoUnit.SECONDS) Duration saveInterval) {

        public SceneSettings {
            if (linkPort < 0 || linkPort > 65535) {
                throw new IllegalArgumentException("xm.scene.link-port 超出范围: " + linkPort);
            }
            if (linkIoThreads < 1 || storageThreads < 1 || storageQueueCapacity < 1) {
                throw new IllegalArgumentException("xm.scene 的线程数与队列上限必须为正数");
            }
            if (linkMaxPendingFrames < 2) {
                throw new IllegalArgumentException("xm.scene.link-max-pending-frames 至少为 2: " + linkMaxPendingFrames);
            }
            if (saveInterval.isNegative() || saveInterval.toMillis() % 1000 != 0 || saveInterval.toSeconds() > 86_400) {
                throw new IllegalArgumentException("xm.scene.save-interval 必须是 0 到 1 天之间的整秒: " + saveInterval);
            }
        }
    }
}
