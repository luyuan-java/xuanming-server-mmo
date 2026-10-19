package com.game.inbox.rules;

import com.game.proto.player_inbox.PlayerInboxFenceVerdict;

/**
 * owner_epoch 围栏的纯判定（mmorpg {@code go/player_inbox/internal/inbox/fence.go}，设计 §7.8、IB-8）。
 *
 * <pre>
 * | 条件                       | 结局           | 动作                                   |
 * | req == 0                   | EPOCH_REQUIRED | 拒绝，不改任何状态                      |
 * | req &lt; fence                | STALE          | 拒绝，不投递、不终结（另按 R 分类）      |
 * | req == fence               | MATCH          | 放行                                   |
 * | req &gt; fence 且 req &gt; R     | EPOCH_AHEAD    | 拒绝并告警，不改状态（只会来自缺陷或伪造）|
 * | fence == 0 且 req ≤ R      | FIRST          | fence = req                            |
 * | fence &lt; req ≤ R            | ADVANCE        | fence = req                            |
 * </pre>
 *
 * <p><b>R 在 Java 版是什么</b>：mmorpg 的 owner_epoch 只在核心 Redis；Java 的 owner_epoch 是玩家表 {@code player.owner_epoch} 列
 * （login 夺权时 +1、跨节点交出时 +1，只增不减，见 game-player-store 的 {@code PlayerMapper}）。所以 R = 该列的当前值，读法同 mmorpg：
 * 只在快照读到的 req &gt; fence 时，才在<b>事务外</b>读一次；epoch 只增不减，所以「先读 R、后在锁内判定并写」做上界判定没有竞态。
 * owner_epoch、fence 都是 uint64，放在 long 里按无符号比较。线程安全（纯函数）。
 */
public final class FenceRules {

    /** STALE 的分类（只进日志与指标，不改变 STALE 本身的处置）。 */
    public enum StaleKind {
        /** 分类用的那次 R 读失败。 */
        UNCLASSIFIED("unclassified"),
        /** R &gt; req：请求来自已失去归属的旧 owner（僵尸 scene），正常收尾路径上的迟到请求。 */
        ZOMBIE("zombie"),
        /** R ≤ req &lt; fence：玩家表里的 owner_epoch 倒退了（库回档 / 人工改库），critical，不归零。 */
        FENCE_REGRESSED("fence_regressed");

        private final String label;

        StaleKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private FenceRules() {
    }

    /** 判定是否需要上界 R：只有 req 非 0 且 req &gt; fence 时才需要。 */
    public static boolean needsEpochBound(long req, long fence) {
        return req != 0 && Long.compareUnsigned(req, fence) > 0;
    }

    /** 围栏判定。{@code r} 只在 {@link #needsEpochBound} 为真时有意义，其余情况结果与它无关。 */
    public static PlayerInboxFenceVerdict decide(long req, long fence, long r) {
        if (req == 0) {
            return PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_EPOCH_REQUIRED;
        }
        int vsFence = Long.compareUnsigned(req, fence);
        if (vsFence < 0) {
            return PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_STALE;
        }
        if (vsFence == 0) {
            return PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_MATCH;
        }
        if (Long.compareUnsigned(req, r) > 0) {
            return PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_EPOCH_AHEAD;
        }
        return fence == 0 ? PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_FIRST : PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_ADVANCE;
    }

    /** 是否放行（MATCH / FIRST / ADVANCE）。 */
    public static boolean allows(PlayerInboxFenceVerdict verdict) {
        return verdict == PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_MATCH
                || verdict == PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_FIRST
                || verdict == PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_ADVANCE;
    }

    /** 放行结局是否要把 fence_epoch 写成 req（FIRST / ADVANCE）。 */
    public static boolean writes(PlayerInboxFenceVerdict verdict) {
        return verdict == PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_FIRST
                || verdict == PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_ADVANCE;
    }

    /** 围栏拒绝对应的 tip（STALE / EPOCH_REQUIRED / EPOCH_AHEAD；其余为 0）。 */
    public static int rejectTip(PlayerInboxFenceVerdict verdict) {
        return switch (verdict) {
            case PLAYER_INBOX_FENCE_STALE -> InboxTips.STALE_OWNER;
            case PLAYER_INBOX_FENCE_EPOCH_REQUIRED -> InboxTips.EPOCH_REQUIRED;
            case PLAYER_INBOX_FENCE_EPOCH_AHEAD -> InboxTips.EPOCH_AHEAD;
            default -> 0;
        };
    }

    /**
     * 按返回前再读一次的 R 给 STALE 分类：R ≤ req 记 fence_regressed（同 mmorpg {@code ClassifyStale}：R &lt; req 同样只可能是计数器倒退，
     * 归进 zombie 会把 critical 信号藏进正常收尾的计数里），否则 zombie。
     */
    public static StaleKind classifyStale(long req, long r) {
        return Long.compareUnsigned(r, req) <= 0 ? StaleKind.FENCE_REGRESSED : StaleKind.ZOMBIE;
    }

    /** 指标标签（no_stream / first / match / advance / stale / epoch_required / epoch_ahead）。 */
    public static String label(PlayerInboxFenceVerdict verdict) {
        if (verdict == null || verdict == PlayerInboxFenceVerdict.UNRECOGNIZED) {
            return "unknown";
        }
        return InboxLabels.suffix(verdict.name(), "PLAYER_INBOX_FENCE_");
    }
}
