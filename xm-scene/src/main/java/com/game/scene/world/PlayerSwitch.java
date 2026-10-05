package com.game.scene.world;

/**
 * 一次跨节点换图在源节点上的在途状态（scene-handoff-spec §5.5）：挂在 {@link ScenePlayer} 上，结局处理完即摘掉。
 * 不持久化，只在场景逻辑线程上读写。异步回调（选目标结果、交出 / 探测结局）都捕获这个对象本身，回来时按引用比对
 * {@code player.switching() == this}——对不上就是过期结果（实例已离开、已重新进场、又发起了新的一次），丢弃。
 *
 * <p>阶段只往前走：{@link SwitchPhase#RESOLVING} → {@link SwitchPhase#FREEZING}（{@link #freeze}）。RESOLVING 的结果不是「去别的节点」时
 * 直接摘掉（回到 NONE），不经 FREEZING。
 */
final class PlayerSwitch {

    /** 冻结中发生、要等交出结局出来才处理的离场（同一玩家只有一个写在途，所以不提交第二笔写）。 */
    enum PendingAbort {
        NONE,
        /** 会话离开：断线、LeaveGame 或所在 gate 链路断开（{@link #leaveVoluntary()} 区分主动与否；记下过主动的就一直是主动）。 */
        LEAVE,
        /** login 请本节点让出这个 epoch（顶号）。 */
        TAKEOVER
    }

    /** 本次换图的令牌（世界内单调递增，只用于日志对照；过期判定按对象引用）。 */
    private final long token;
    private final long wantSceneId;
    private final int wantConfigId;
    /** RESOLVING 槽的过期时刻（单调时钟纳秒）：选目标的结果迟迟不回（实现保证会回，这里只是兜底）时，之后的 63 不再被 3014 挡住。 */
    private final long resolveDeadlineNanos;
    private SwitchPhase phase = SwitchPhase.RESOLVING;
    private int targetNodeId;
    private long targetSceneId;
    private int targetConfigId;
    /** 冻结那一刻的写回内容（交出事务写的就是它）。 */
    private PlayerSave snapshot;
    private long frozenAtNanos;
    private PendingAbort pendingAbort = PendingAbort.NONE;
    private boolean leaveVoluntary;
    /**
     * 冻结中实例已被别的路径移出世界（停服写回、同会话换角色、被更高 epoch 的进场接替）：那条路径已按自己的规则写回 / 放弃，
     * 交出结局出来时只做收尾——结局是「已交出」就释放新 epoch（交出帧没发、也不会再发，新 epoch 没有别的知情者）。
     */
    private boolean detached;

    PlayerSwitch(long token, long wantSceneId, int wantConfigId, long resolveDeadlineNanos) {
        this.token = token;
        this.wantSceneId = wantSceneId;
        this.wantConfigId = wantConfigId;
        this.resolveDeadlineNanos = resolveDeadlineNanos;
    }

    long token() {
        return token;
    }

    long wantSceneId() {
        return wantSceneId;
    }

    int wantConfigId() {
        return wantConfigId;
    }

    long resolveDeadlineNanos() {
        return resolveDeadlineNanos;
    }

    SwitchPhase phase() {
        return phase;
    }

    /** RESOLVING → FREEZING：记下目标与冻结快照。 */
    void freeze(int nodeId, long sceneId, int configId, PlayerSave frozen, long nowNanos) {
        if (phase != SwitchPhase.RESOLVING) {
            throw new IllegalStateException("只有选目标中的换图才能冻结: " + phase);
        }
        this.phase = SwitchPhase.FREEZING;
        this.targetNodeId = nodeId;
        this.targetSceneId = sceneId;
        this.targetConfigId = configId;
        this.snapshot = frozen;
        this.frozenAtNanos = nowNanos;
    }

    int targetNodeId() {
        return targetNodeId;
    }

    long targetSceneId() {
        return targetSceneId;
    }

    int targetConfigId() {
        return targetConfigId;
    }

    PlayerSave snapshot() {
        return snapshot;
    }

    long frozenAtNanos() {
        return frozenAtNanos;
    }

    PendingAbort pendingAbort() {
        return pendingAbort;
    }

    boolean leaveVoluntary() {
        return leaveVoluntary;
    }

    /**
     * 冻结中会话离开。离开优先于接管：会话已经走了，踢它没有意义。
     * 已记下的主动登出（LeaveGame）不被之后的断线离开（典型是 gate 链路随后断开）降级成断线：与没冻结时一致——主动离开即终局，
     * 写登出墓碑、下次进游戏按首登落点；降级成断线会改写成 30 s 重连租约，把再进游戏路由回旧场景旧坐标。
     */
    void requestLeave(boolean voluntary) {
        if (pendingAbort == PendingAbort.LEAVE) {
            leaveVoluntary |= voluntary;
            return;
        }
        pendingAbort = PendingAbort.LEAVE;
        leaveVoluntary = voluntary;
    }

    /** 冻结中被请求让出（顶号）。已记下离开的不改。 */
    void requestTakeover() {
        if (pendingAbort == PendingAbort.NONE) {
            pendingAbort = PendingAbort.TAKEOVER;
        }
    }

    boolean detached() {
        return detached;
    }

    void detach() {
        detached = true;
    }
}
