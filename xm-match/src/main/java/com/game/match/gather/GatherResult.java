package com.game.match.gather;

import java.util.Objects;

/**
 * 一次 gather 的结果（{@link GatherLauncher#launch} 的 future 的值）。
 *
 * @param ok       true = 已建房（{@code outcome} 是 {@link GatherOutcome#SUCCESS}）；false = 没开成，补偿已做完（解冻、票据按入口的 {@link FailPolicy} 处置）
 * @param outcome  结局标签（指标、日志、{@code TeamGatherReply.outcome}）
 * @param battleId 这一局的 battle_id：成功时非 0；失败时是已经发出的号（发号之前就失败为 0），只进日志——
 *                 活动入口的号是预发的，失败时它不会产生结果事件
 */
public record GatherResult(boolean ok, GatherOutcome outcome, long battleId) {

    public GatherResult {
        Objects.requireNonNull(outcome, "outcome");
        if (ok != (outcome == GatherOutcome.SUCCESS)) {
            throw new IllegalArgumentException("ok 与 outcome 不一致: ok=" + ok + " outcome=" + outcome);
        }
        if (ok && battleId == 0) {
            throw new IllegalArgumentException("成功的 gather 必须带 battle_id");
        }
    }

    public static GatherResult success(long battleId) {
        return new GatherResult(true, GatherOutcome.SUCCESS, battleId);
    }

    /** @param outcome 不能是 {@link GatherOutcome#SUCCESS} */
    public static GatherResult failed(GatherOutcome outcome, long battleId) {
        return new GatherResult(false, outcome, battleId);
    }
}
