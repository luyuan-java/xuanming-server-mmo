package com.game.match.gather;

import java.util.Locale;

/**
 * 一次 gather 的结局（match-spec §3.3 补偿矩阵、§9.6、§11 的 {@code xm_match_gathers_total{outcome}}）。标签取值就是枚举名的小写（{@link #label()}），
 * 与基线 {@code match_gather_total} 的 outcome 同名；{@link #OVERLOADED} 与 {@link #CREATE_REJECTED} 是 Java 新增。
 *
 * <p>入口（切磋、整队、活动、PVE_SOLO）拿到的只是成功与否加这个标签：<b>不要按具体的失败原因分支</b>，客户端可见的失败处理只看 {@link GatherResult#ok()}
 * （§8.5）。唯一需要知道的区别已经由管线处理掉了（见各值的注释）。
 */
public enum GatherOutcome {

    /** 已建房：全员票据置 ready（切磋没有票），落点记录已补写。 */
    SUCCESS(false),
    /** 发号失败（租约无效 / 时钟回拨）或种子生成失败。无肇事者。 */
    INTERNAL(false),
    /** 拿不到在途许可（{@code xm.match.gather-max-inflight}）：没有任何副作用，没冻结任何人。无肇事者。 */
    OVERLOADED(false),
    /** 目录里没有可分配的 battle 节点（或目录读失败）：还没冻结任何人。无肇事者。 */
    NO_BATTLE_NODE(false),
    /** 某名成员的位置读不到 / 不是在线 / 找不到持有他的 scene 节点：<b>该成员是肇事者</b>。 */
    NO_LOCATION(true),
    /** 某名成员备战失败（scene 拒绝、实例不符、过载、传输失败、超时、快照为空）：<b>该成员是肇事者</b>。 */
    PREPARE_FAILED(true),
    /** 指纹闸是 enforce 且成员之间的配表指纹不一致：<b>少数派里顺序最靠前者是肇事者</b>。 */
    FINGERPRINT_MISMATCH(true),
    /** 落点记录写不进去（首写或换节点改写）：不建房。无肇事者。 */
    INDEX_FAILED(false),
    /** battle 节点级拒绝，且换了一个节点仍被拒（或没有可换的节点）：保证哪个节点上都没有这间房。无肇事者。 */
    NOT_ALLOCATABLE(false),
    /** battle 已受理但明确拒绝建房（应答带错误码，保证零副作用）：不发 destroy，直接补偿（修基线 F-g2）。无肇事者。 */
    CREATE_REJECTED(false),
    /** 建房结局不明（超时 / 传输失败 / 准入字段缺失），回滚的 destroy 成功：房间确定不在，照常补偿。无肇事者。 */
    CREATE_FAILED(false),
    /**
     * 建房结局不明，回滚的 destroy <b>也失败</b>：房间可能活着。<b>不解冻、不动票据、保留落点记录</b>——票留在 matched 等 TTL，
     * 冻结由 scene 按备战期限（或确认事件）收尾。无肇事者。
     */
    CREATE_FAILED_ROOM_ALIVE(false);

    private final boolean hasOffender;
    private final String label;

    GatherOutcome(boolean hasOffender) {
        this.hasOffender = hasOffender;
        this.label = name().toLowerCase(Locale.ROOT);
    }

    /** 这种结局是否归咎于某一名成员（凑单入口：肇事者删票、其余回队首；无肇事者时全员回队首并带退避）。 */
    public boolean hasOffender() {
        return hasOffender;
    }

    /** 指标标签与日志里的取值（枚举名小写，如 {@code create_failed_room_alive}）；也是 {@code TeamGatherReply.outcome} 的取值。 */
    public String label() {
        return label;
    }
}
