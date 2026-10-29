package com.game.data.consistency;

import com.game.common.deadline.Deadline;

/** 全轮、单项和单次往返共同收紧；不把失败异步查询遗留到后台。 */
public final class ConsistencyBudget {
    private final Deadline run;
    private final Deadline check;
    public ConsistencyBudget(Deadline run, long checkMillis) {
        this.run = run; this.check = Deadline.after(checkMillis);
    }
    public long remainingMillis() { return Math.min(run.remainingMillis(), check.remainingMillis()); }
    public int requestMillis() {
        long remaining = remainingMillis();
        if (remaining < 1 || Thread.currentThread().isInterrupted()) throw new Failure("budget_exhausted");
        return (int) Math.min(2_000, remaining);
    }
    public void check() { requestMillis(); }
    public void confirmationDelay() {
        if (remainingMillis() <= 2_000) throw new Failure("budget_exhausted");
        try { Thread.sleep(2_000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure("interrupted"); }
        check();
    }
    /** 只允许固定类别进入报告，绝不串接依赖异常原消息。 */
    public static final class Failure extends RuntimeException {
        public Failure(String reason) { super(reason, null, true, false); }
    }
}
