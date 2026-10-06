package com.game.match.gather;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 配表指纹比对（match-spec §3.2 第 7 步、§9.6 第 3.6 步；基线 {@code checkTableFingerprints}，{@code gather.go:462-507}）。纯函数。
 * 各 scene 节点在备战应答里回报本节点战斗表的内容指纹；全员备战成功之后比对一次：
 *
 * <ul>
 *   <li><b>全员非空且两两一致</b> → 把这个指纹透传给 battle（{@code CreateBattleRequest.table_fingerprint}），battle 再与自己的比。</li>
 *   <li><b>不一致或部分为空</b>（旧版本 scene 不回报 = 空，本身就是可疑方）：
 *     <ul>
 *       <li>{@link FingerprintMode#WARN}（缺省）：照常开局，<b>不透传任何一方的指纹</b>，调用方记 ERROR 与指标；</li>
 *       <li>{@link FingerprintMode#ENFORCE}：不开局，给出肇事者，调用方按备战失败的路径补偿。</li>
 *     </ul></li>
 *   <li>{@link FingerprintMode#OFF}：不比对、不透传（一致也不透传）、不计数。</li>
 * </ul>
 *
 * <p><b>多数派与肇事者</b>（逐字照搬基线）：多数派只在<b>非空</b>指纹里选——按名单顺序逐个计数，谁的票数先严格超过当前多数派谁就成为多数派
 * （所以平票时保持先到者：两人各一票时是名单靠前的那位）；空指纹不能靠「空」凑成多数。肇事者 = 名单里第一个与多数派不一致的人；
 * 全员为空时没有多数派，肇事者是名单第一位（enforce 下这等于拒绝一切不回报指纹的 scene，是开 enforce 的前提）。
 */
public final class FingerprintCheck {

    /** 一名已备战成员回报的指纹（{@code PrepareBattleResponse.table_fingerprint}；没回报为空串）。 */
    public record Member(long playerId, String fingerprint) {

        public Member {
            fingerprint = fingerprint == null ? "" : fingerprint;
        }
    }

    /**
     * 比对结论。
     *
     * @param proceed     true = 照常开局；false = 不开局（只有 enforce 且不一致）
     * @param passThrough 透传给 battle 的指纹；不透传为空串
     * @param offender    肇事者的玩家号（只有 {@code proceed = false} 时非 0）
     * @param mismatch    是否不一致（含部分为空）：为真时调用方计一次 {@code xm_match_table_fingerprint_mismatches_total}。off 恒为假
     * @param majority    多数派指纹（只进日志；全员为空或 off 时为空串）
     * @param suspect     名单里第一个与多数派不一致的人（只进日志；一致或 off 时为 0）。warn 下它不是肇事者，只是排障线索
     */
    public record Result(boolean proceed, String passThrough, long offender, boolean mismatch, String majority, long suspect) {
    }

    private static final Result SKIPPED = new Result(true, "", 0, false, "", 0);

    private FingerprintCheck() {
    }

    /**
     * @param mode    比对策略
     * @param members 已备战的全体成员，名单顺序
     */
    public static Result check(FingerprintMode mode, List<Member> members) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(members, "members");
        if (mode == FingerprintMode.OFF || members.isEmpty()) {
            return SKIPPED;
        }
        Map<String, Integer> counts = new HashMap<>();
        String majority = "";
        for (Member member : members) {
            String fingerprint = member.fingerprint();
            if (fingerprint.isEmpty()) {
                continue;
            }
            int count = counts.merge(fingerprint, 1, Integer::sum);
            if (majority.isEmpty() || count > counts.get(majority)) {
                majority = fingerprint;
            }
        }
        long suspect = 0;
        for (Member member : members) {
            if (majority.isEmpty() || !member.fingerprint().equals(majority)) {
                suspect = member.playerId();
                break;
            }
        }
        if (suspect == 0) {
            return new Result(true, majority, 0, false, majority, 0);
        }
        if (mode == FingerprintMode.ENFORCE) {
            return new Result(false, "", suspect, true, majority, suspect);
        }
        return new Result(true, "", 0, true, majority, suspect);
    }
}
