package com.game.robot.scenario;

import com.game.robot.client.BattleFrame;
import java.util.ArrayList;
import java.util.List;

/**
 * 直连上帧序列的判读（battle-node-spec §5.8 O3–O8、R1–R3），纯函数：输入按到达顺序的记录（含关闭标记），返回问题描述，没问题返回 null。
 * 场景只负责收帧，判据集中在这里便于单测。
 */
final class BattleOrder {

    /** 全自动节奏 2 s（§4.4.1），robot 接受 [1.5 s, 3.5 s]（§13.8 第 9 步）。 */
    static final long AUTO_GAP_MIN_MS = 1500;
    static final long AUTO_GAP_MAX_MS = 3500;
    /** 终局包之后 FIN 的上限（§13.8 第 9 步：1.5 s 内被服务端 FIN；服务端 1 s 强关兜底）。 */
    static final long FIN_AFTER_END_MAX_MS = 1500;

    static final String FIN = "closed:" + BattleFrame.FIN;

    private BattleOrder() {
    }

    /**
     * 参战者发 162 开挂机之后、直到连接关闭的尾部（{@code tail} 从 162 发出前的序号起）：
     * <ul>
     *   <li>「未就绪 → 全员就绪」翻转当场结算：第一条是 139，先于 162 的应答（R2 / O3）；</li>
     *   <li>翻转那一回合就打完：… 139 → 150 → 应答 → FIN；否则应答之后每 2 s 一条 139（定时器结算，O4），150 之后紧跟 FIN；</li>
     *   <li>应答恰好一条，150 恰好一条，最后是 FIN（不是 RST），150 到 FIN 不超过 {@value #FIN_AFTER_END_MAX_MS} ms。</li>
     * </ul>
     */
    static String autoTailProblem(List<BattleFrame> tail, int turnResult, int battleEnd, int setAuto) {
        List<String> labels = BattleSupport.labels(tail);
        String turn = "push:" + turnResult;
        String end = "push:" + battleEnd;
        String reply = "reply:" + setAuto;
        if (labels.isEmpty() || !labels.get(0).equals(turn)) {
            return "翻转当场结算：第一条应是 " + turn + "（先于 162 的应答），实际 " + labels;
        }
        if (!labels.get(labels.size() - 1).equals(FIN)) {
            return "终局之后应是 FIN（不是 RST / 超时），实际 " + labels;
        }
        if (labels.stream().filter(reply::equals).count() != 1) {
            return "162 的应答应恰好一条，实际 " + labels;
        }
        if (labels.stream().filter(end::equals).count() != 1) {
            return "150 应恰好一条，实际 " + labels;
        }
        int endAt = labels.indexOf(end);
        int replyAt = labels.indexOf(reply);
        List<String> after = labels.subList(endAt, labels.size());
        if (replyAt > endAt) {
            if (!after.equals(List.of(end, reply, FIN))) {
                return "翻转那一回合打完：应是 … 139 → 150 → 应答 → FIN，实际 " + labels;
            }
            for (int i = 0; i < endAt; i++) {
                if (!labels.get(i).equals(turn)) {
                    return "150 之前只应有 139，实际 " + labels;
                }
            }
        } else {
            if (!after.equals(List.of(end, FIN))) {
                return "定时器结算的回合打完：150 之后应紧跟 FIN，实际 " + labels;
            }
            if (replyAt != 1) {
                return "翻转回合没打完：应答应紧跟第一条 139，实际 " + labels;
            }
            // 翻转那条 139 之后装填的已是 2 s 窗口：从它起，相邻 139 的间隔都应约 2 s
            List<BattleFrame> timerTurns = new ArrayList<>();
            timerTurns.add(tail.get(0));
            for (int i = replyAt + 1; i < endAt; i++) {
                if (!labels.get(i).equals(turn)) {
                    return "应答与 150 之间只应有 139，实际 " + labels;
                }
                timerTurns.add(tail.get(i));
            }
            if (timerTurns.size() < 2) {
                return "应答之后、150 之前至少还有一条定时器结算的 139，实际 " + labels;
            }
            for (int i = 1; i < timerTurns.size(); i++) {
                long gap = BattleSupport.millisBetween(timerTurns.get(i - 1), timerTurns.get(i));
                if (gap < AUTO_GAP_MIN_MS || gap > AUTO_GAP_MAX_MS) {
                    return "全自动节奏应约 2 s（" + AUTO_GAP_MIN_MS + "–" + AUTO_GAP_MAX_MS + " ms），相邻 139 间隔 " + gap + " ms";
                }
            }
        }
        long finGap = BattleSupport.millisBetween(tail.get(endAt), tail.get(tail.size() - 1));
        if (finGap > FIN_AFTER_END_MAX_MS) {
            return "150 之后 " + finGap + " ms 才 FIN（应 ≤ " + FIN_AFTER_END_MAX_MS + " ms）";
        }
        return null;
    }

    /** 观众的尾部：每回合一条 158（与参战者的 139 同样多），然后 166，然后 FIN。 */
    static String spectatorTailProblem(List<BattleFrame> tail, long turns, int spectateTurn, int spectateEnd) {
        List<String> expected = new ArrayList<>();
        for (long i = 0; i < turns; i++) {
            expected.add("push:" + spectateTurn);
        }
        expected.add("push:" + spectateEnd);
        expected.add(FIN);
        List<String> labels = BattleSupport.labels(tail);
        return labels.equals(expected) ? null : "观众应依次收到 " + expected + "，实际 " + labels;
    }

    /** 尾部恰好是给定的标签序列（终局 / 清退 / 165 等短序列）。 */
    static String exactly(List<BattleFrame> tail, List<String> expected) {
        List<String> labels = BattleSupport.labels(tail);
        return labels.equals(expected) ? null : "应依次收到 " + expected + "，实际 " + labels;
    }

    /** 某号推送的条数。 */
    static long count(List<BattleFrame> frames, String label) {
        return frames.stream().filter(f -> f.label().equals(label)).count();
    }
}
