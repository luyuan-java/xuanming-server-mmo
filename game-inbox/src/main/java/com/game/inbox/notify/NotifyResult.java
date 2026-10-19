package com.game.inbox.notify;

import java.time.Duration;

/**
 * 一次在线提醒的结局（{@code game_inbox_notify_total{result}} 的取值；mmorpg {@code notify.go} 的 {@code NotifyResult}，设计 §6.3，
 * PARITY 收件箱行第 ⑦ 条）。落定的四种清 {@code notify_due_ms}，其余按退避重试；{@link #NODE_MISSING} 悬满 1 小时才由补发落定。
 *
 * <p>与 mmorpg 的取值对照（Java 版定位走 Redis 位置记录 + 节点目录、投递走 Dubbo 直连，没有 Kafka 与 etcd 镜像）：
 * <ul>
 *   <li>{@code nodes_unsynced} / {@code node_ambiguous}：Java 不会出现——节点目录每次现读、按节点号做键天然唯一（{@code SceneAssetLocator} 同口径），
 *       不在本枚举里；</li>
 *   <li>{@code awaiting_placement}：Java 的跨 zone 待落点写成「重连租约 + 节点号 0」，与断线重连租约同一状态；两种情况都没有任何节点持有该玩家、
 *       下次落地一定无条件拉，一并记 {@link #AWAITING_PLACEMENT}；</li>
 *   <li>{@code empty_uuid}：节点目录条目没有实例 id，不能填 target_instance_id（防僵尸契约，不发）。</li>
 * </ul>
 */
public enum NotifyResult {
    /** 读位置记录 / 节点目录失败，或 Dubbo 调用失败 / 超时 / 回 UNSPECIFIED。留给补发。 */
    ERROR("error", false),
    /** scene 已接收（ACCEPTED）。 */
    SENT("sent", true),
    /** 没有位置记录或登出墓碑：玩家离线，下次落地会拉。 */
    OFFLINE("offline", true),
    /** 重连租约（含跨 zone 待落点）：此刻没有节点持有该玩家，下次落地会无条件拉。 */
    AWAITING_PLACEMENT("awaiting_placement", true),
    /** 位置记录没写节点号，或目录条目不提供 RPC 端口（旧版本节点）：再怎么重试也送不到，交给落地拉取与在线兜底。 */
    NO_NODE("no_node", true),
    /** 节点目录条目没有实例 id：防僵尸契约，不发。留给补发。 */
    EMPTY_UUID("empty_uuid", false),
    /**
     * 目录里查不到位置记录指向的节点，或那个地址上回 NOT_HERE（节点号已被新进程接手）：不能断定玩家已不在线（续期抖动、刚注册、
     * 位置记录稍后才更新），留给补发，悬满 1 小时才落定。
     */
    NODE_MISSING("node_missing", false);

    /** {@link #NODE_MISSING} 悬了这么久仍查不到，补发就落定并打 ERROR（与 mmorpg {@code notifyNodeMissingSettleAfter} 同值）。 */
    public static final Duration NODE_MISSING_SETTLE_AFTER = Duration.ofHours(1);
    /** 补发没落定时的退避区间：已悬时长钳到 [30s, 30min]，每次约翻倍。 */
    public static final Duration RETRY_MIN = Duration.ofSeconds(30);
    public static final Duration RETRY_MAX = Duration.ofMinutes(30);

    private final String label;
    private final boolean settled;

    NotifyResult(String label, boolean settled) {
        this.label = label;
        this.settled = settled;
    }

    public String label() {
        return label;
    }

    /** 是否可以立即清 notify_due_ms（入箱后那次提醒与补发都按它）。 */
    public boolean settled() {
        return settled;
    }

    /**
     * 补发这一次没落定的提醒是否就此落定：只有 {@link #NODE_MISSING}、且已悬 {@link #NODE_MISSING_SETTLE_AFTER} 以上（now − pendingSince）。
     * pendingSince 为 0 或不早于 now（时钟回拨）时按刚开始悬算，不落定。
     */
    public static boolean givesUp(NotifyResult result, long nowMs, long pendingSinceMs) {
        if (result != NODE_MISSING || pendingSinceMs == 0 || Long.compareUnsigned(pendingSinceMs, nowMs) >= 0) {
            return false;
        }
        return nowMs - pendingSinceMs >= NODE_MISSING_SETTLE_AFTER.toMillis();
    }

    /**
     * 补发没落定之后 notify_due_ms 的新值：now + 退避，退避 = 这条提醒已悬时长（now − pendingSince，pendingSince 是流行的 updated_ms）钳到
     * [30s, 30min]。每次没落定都把「已悬时长」再等一遍，间隔按 2 倍递增，不需要新增列记退避级数。pendingSince 为 0 或不早于 now 时取下限。
     */
    public static long retryDueMs(long nowMs, long pendingSinceMs) {
        long min = RETRY_MIN.toMillis();
        long max = RETRY_MAX.toMillis();
        long backoff = min;
        if (pendingSinceMs != 0 && Long.compareUnsigned(pendingSinceMs, nowMs) < 0) {
            backoff = Math.min(Math.max(nowMs - pendingSinceMs, min), max);
        }
        return nowMs + backoff;
    }
}
