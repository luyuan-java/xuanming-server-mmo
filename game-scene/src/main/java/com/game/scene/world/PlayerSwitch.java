package com.game.scene.world;

import com.game.api.proto.ZoneRedirect;
import java.util.Objects;

/**
 * 一次换图在源节点上的在途状态（scene-handoff-spec §5.5、zone-travel-spec §5.5）：挂在 {@link ScenePlayer} 上，结局处理完即摘掉。
 * 不持久化，只在场景逻辑线程上读写。异步回调（在队检查、选目标结果、交出 / 探测结局）都捕获这个对象本身，回来时按引用比对
 * {@code player.switching() == this}——对不上就是过期结果（实例已离开、已重新进场、槽已作废、又发起了新的一次），丢弃。
 *
 * <p>阶段只往前走：{@link SwitchPhase#RESOLVING} → {@link SwitchPhase#FREEZING}（{@link #freeze}）。RESOLVING 的结果不是「去别的节点」时
 * 直接摘掉（回到 NONE），不经 FREEZING。
 *
 * <p>批次 5.3（dungeon-mirror-spec §6.7）：63 镜像分支取号期间复用同一个 RESOLVING 状态（{@link #mirrorCreate}，{@link Purpose#MIRROR_CREATE}）——
 * 期间再发 63 回 3014（D4）、组队跟随跳过、选目标 / 取号的迟到结果按引用比对丢弃；镜像恒与源同节点，所以取号这一种<b>永不冻结</b>。
 *
 * <p>批次 5.4（zone-travel-spec §5.5，§0.6 裁决 16）：226 跨 zone 传送也用这个槽，{@link #reason()} = {@link SwitchReason#TRAVEL}
 * （{@link #travel}）。它多一个起始阶段 {@link SwitchPhase#PRECHECK}（在队检查，延迟应答 {@link #deferred()} 挂在槽上），之后
 * PRECHECK → RESOLVING（{@link #accept}）→ FREEZING（{@link #freezeTravel}，记下选中的重定向载荷与解析后的地图）。不另起类：
 * 在途判定（{@link SceneWorld#switchInFlight}）、冻结闸、墓碑、pendingAbort、交出 / 探测结局的分派全部共用。
 */
final class PlayerSwitch {

    /** 这次在途怎么做（与 {@link SwitchReason}「为什么」正交）。 */
    enum Purpose {
        /**
         * 请 scene-manager 选目标，结果在别处就冻结交出：63 的远端去向（{@link SwitchReason#PLAYER}，交出并保持，批次 5.2），
         * 或 226 跨 zone 传送（{@link SwitchReason#TRAVEL}，交出并释放，批次 5.4）。
         */
        REMOTE_SWITCH,
        /** 63 镜像分支：向 scene-manager 取实例号，拿到号后在本节点建镜像并换入（批次 5.3），不冻结。 */
        MIRROR_CREATE
    }

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
    private final SwitchReason reason;
    private final Purpose purpose;
    /** REMOTE_SWITCH：63 指定的场景号（226 恒为 0）；MIRROR_CREATE：镜像的源频道。 */
    private final long wantSceneId;
    /** REMOTE_SWITCH：63 / 226 带的地图（226 的 0 = 目标 zone 的默认主世界）；MIRROR_CREATE：63 带的 mirror_config_id。 */
    private final int wantConfigId;
    /** TRAVEL：226 的目标 zone；其余为 0。 */
    private final int toZoneId;
    /** TRAVEL：226 的延迟应答（PRECHECK 结束时回；PRECHECK 槽过期时由作废方回 3027）；其余为 null。 */
    private final DeferredReply deferred;
    /**
     * 槽的过期时刻（单调时钟纳秒）：结果回调迟迟不回（实现保证会回，这里只是兜底）时，之后的 63 / 226 不再被挡住。
     * PRECHECK 是在队检查上限 + 1 s，RESOLVING 是选目标兜底超时 + 1 s（TRAVEL 受理时改成后者）；FREEZING 不过期。
     */
    private long deadlineNanos;
    private SwitchPhase phase;
    private int targetNodeId;
    private long targetSceneId;
    private int targetConfigId;
    /** TRAVEL：选中的目标 gate 与票据，原样进重定向帧；其余为 null。 */
    private ZoneRedirect redirect;
    /** 冻结那一刻的写回内容（交出事务写的就是它）。 */
    private PlayerSave snapshot;
    private long frozenAtNanos;
    private PendingAbort pendingAbort = PendingAbort.NONE;
    private boolean leaveVoluntary;
    /**
     * 冻结中实例已被别的路径移出世界（停服写回、同会话换角色、被更高 epoch 的进场接替）：那条路径已按自己的规则写回 / 放弃，
     * 交出结局出来时只做收尾——跨节点换图的结局是「已交出」就释放新 epoch（交出通知没发、也不会再发，新 epoch 没有别的知情者）；
     * 跨 zone 传送的新 epoch 已在交出事务里释放，什么都不用做。
     */
    private boolean detached;
    /**
     * TRAVEL：槽过了期限、已由作废方（{@link SceneWorld#switchInFlight}）给出这次传送的结论——PRECHECK 经延迟应答回 3027 并计
     * {@code travel_requests{team_read_error}}，RESOLVING 推 23 {3027} 并计 {@code travel_resolves{error}}。之后迟到的结果只丢弃，
     * 不再推、也不再计数（每次受理在 {@code travel_resolves} 上恰好计一次）。
     */
    private boolean expired;
    /** 5.6：只有客户端显式选线请求可以在成功后建立共享冷却。 */
    private boolean clientChannelPick;
    private int channelCooldownSeconds;
    private boolean channelCooldownStarted;

    void clientChannelPick(boolean value) { clientChannelPick = value; }
    boolean clientChannelPick() { return clientChannelPick; }
    void channelCooldownSeconds(int seconds) { channelCooldownSeconds = clientChannelPick ? seconds : 0; }
    int channelCooldownSeconds() { return channelCooldownSeconds; }
    boolean channelCooldownStarted() { return channelCooldownStarted; }
    void startChannelCooldown(long deadline) {
        channelCooldownStarted = true;
        if (phase == SwitchPhase.RESOLVING) deadlineNanos = deadline;
    }

    PlayerSwitch(long token, long wantSceneId, int wantConfigId, long resolveDeadlineNanos) {
        this(token, SwitchReason.PLAYER, Purpose.REMOTE_SWITCH, SwitchPhase.RESOLVING, wantSceneId, wantConfigId, 0, null,
                resolveDeadlineNanos);
    }

    static PlayerSwitch evacuate(long token, int wantConfigId, long resolveDeadlineNanos) {
        return new PlayerSwitch(token, SwitchReason.EVACUATE, Purpose.REMOTE_SWITCH, SwitchPhase.RESOLVING,
                0, wantConfigId, 0, null, resolveDeadlineNanos);
    }

    private PlayerSwitch(long token, SwitchReason reason, Purpose purpose, SwitchPhase phase, long wantSceneId, int wantConfigId,
                         int toZoneId, DeferredReply deferred, long deadlineNanos) {
        this.token = token;
        this.reason = reason;
        this.purpose = purpose;
        this.phase = phase;
        this.wantSceneId = wantSceneId;
        this.wantConfigId = wantConfigId;
        this.toZoneId = toZoneId;
        this.deferred = deferred;
        this.deadlineNanos = deadlineNanos;
    }

    /** 63 镜像分支的取号在途（RESOLVING，永不冻结）。 */
    static PlayerSwitch mirrorCreate(long token, long sourceSceneId, int mirrorConfigId, long resolveDeadlineNanos) {
        return new PlayerSwitch(token, SwitchReason.PLAYER, Purpose.MIRROR_CREATE, SwitchPhase.RESOLVING, sourceSceneId,
                mirrorConfigId, 0, null, resolveDeadlineNanos);
    }

    /**
     * 226 跨 zone 传送（批次 5.4）：同步校验已过，从 PRECHECK 开始。
     *
     * @param deferred        226 的延迟应答（不能为 null）
     * @param precheckDeadlineNanos 在队检查上限 + 1 s 宽限之后的单调时刻
     */
    static PlayerSwitch travel(long token, int toZoneId, int wantConfigId, DeferredReply deferred, long precheckDeadlineNanos) {
        return new PlayerSwitch(token, SwitchReason.TRAVEL, Purpose.REMOTE_SWITCH, SwitchPhase.PRECHECK, 0, wantConfigId, toZoneId,
                Objects.requireNonNull(deferred, "deferred"), precheckDeadlineNanos);
    }

    long token() {
        return token;
    }

    SwitchReason reason() {
        return reason;
    }

    Purpose purpose() {
        return purpose;
    }

    long wantSceneId() {
        return wantSceneId;
    }

    int wantConfigId() {
        return wantConfigId;
    }

    int toZoneId() {
        return toZoneId;
    }

    DeferredReply deferred() {
        return deferred;
    }

    long deadlineNanos() {
        return deadlineNanos;
    }

    SwitchPhase phase() {
        return phase;
    }

    /** TRAVEL：PRECHECK → RESOLVING（不在队，{@code {0}} 已回）；槽的期限换成选目标兜底超时 + 1 s。 */
    void accept(long resolveDeadlineNanos) {
        if (phase != SwitchPhase.PRECHECK || reason != SwitchReason.TRAVEL) {
            throw new IllegalStateException("只有在队检查中的跨 zone 传送才能受理: " + phase + " " + reason);
        }
        this.phase = SwitchPhase.RESOLVING;
        this.deadlineNanos = resolveDeadlineNanos;
    }

    /** 跨节点换图 RESOLVING → FREEZING：记下目标与冻结快照。 */
    void freeze(int nodeId, long sceneId, int configId, PlayerSave frozen, long nowNanos) {
        if (phase != SwitchPhase.RESOLVING || purpose != Purpose.REMOTE_SWITCH || reason == SwitchReason.TRAVEL) {
            throw new IllegalStateException("只有选目标中的跨节点换图才能冻结: " + phase + " " + purpose + " " + reason);
        }
        this.phase = SwitchPhase.FREEZING;
        this.targetNodeId = nodeId;
        this.targetSceneId = sceneId;
        this.targetConfigId = configId;
        this.snapshot = frozen;
        this.frozenAtNanos = nowNanos;
    }

    /** 跨 zone 传送 RESOLVING → FREEZING：记下解析后的目标地图、选中的重定向载荷与冻结快照（目标节点与场景恒为 0）。 */
    void freezeTravel(int configId, ZoneRedirect chosen, PlayerSave frozen, long nowNanos) {
        if (phase != SwitchPhase.RESOLVING || reason != SwitchReason.TRAVEL) {
            throw new IllegalStateException("只有选目标中的跨 zone 传送才能这样冻结: " + phase + " " + reason);
        }
        this.phase = SwitchPhase.FREEZING;
        this.targetConfigId = configId;
        this.redirect = Objects.requireNonNull(chosen, "redirect");
        this.snapshot = frozen;
        this.frozenAtNanos = nowNanos;
    }

    int targetNodeId() {
        return targetNodeId;
    }

    long targetSceneId() {
        return targetSceneId;
    }

    /** 冻结时记下的目标地图（跨 zone 传送是 scene-manager 解析后的地图，写进待落点记录）。 */
    int targetConfigId() {
        return targetConfigId;
    }

    ZoneRedirect redirect() {
        return redirect;
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

    boolean expired() {
        return expired;
    }

    /** TRAVEL 的 PRECHECK / RESOLVING 槽过期、作废方已给出结论（见 {@link #expired}）。 */
    void expire() {
        if (reason != SwitchReason.TRAVEL || phase == SwitchPhase.FREEZING) {
            throw new IllegalStateException("只有冻结前的跨 zone 传送槽会过期作废并给结论: " + phase + " " + reason);
        }
        expired = true;
    }
}
