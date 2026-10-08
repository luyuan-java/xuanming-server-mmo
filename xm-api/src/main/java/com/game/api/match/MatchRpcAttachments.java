package com.game.api.match;

import com.game.common.deadline.Deadline;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.rpc.RpcContext;

/**
 * match 内部接口（{@code MatchTeamService}、{@code MatchInternalService}）的调用附件：把调用方的<b>剩余预算</b>带给提供方（match-spec §7.2「截止」）。
 * 调用方与提供方共用这一个类，免得两边各写一份字符串。这里的「剩余预算」指调用方<b>在这一次调用上</b>还肯等多久：一跳的 Dubbo 超时
 * 比整请求的剩余预算短时（xm-team：每跳封顶 3 s、请求预算 3.5 s）带的是前者——带大了，提供方会在调用方已经放弃之后继续写。
 *
 * <p>为什么传相对预算而不是绝对的 Unix 毫秒：跨主机比墙钟会把时钟偏差算进截止（提前判过期，或在调用方已放弃之后仍然建票开局）。提供方以
 * 「收到时刻 + 预算」作本地截止（单调时钟）；传输耗时让它略晚于调用方的真实截止，这个窄窗口由调用方的补偿兜住
 * （xm-team 的 {@code releaseTeamTickets}、4.6 的结算补登记）。
 *
 * <p>依赖 Dubbo 的 {@link RpcContext}（线程局部）：调用方的三个方法必须在<b>发起调用的那条线程</b>上用；提供方的 {@link #deadlineFromCall}
 * 必须在<b>服务方法入口、Dubbo 线程上同步</b>调用（切到工作线程之后就读不到了）。线程安全（只碰线程局部状态）。
 */
public final class MatchRpcAttachments {

    /** 附件名：剩余预算，十进制毫秒。Triple 把附件当 HTTP/2 头传，名字必须全小写。 */
    public static final String BUDGET_MS = "xm-budget-ms";

    private MatchRpcAttachments() {
    }

    // ---------------------------------------------------------------- 调用方

    /**
     * 带着剩余预算发一次调用：在本线程上挂上附件 → 调 {@code invocation}（它必须<b>同步地</b>在本线程上发起恰好一次 Dubbo 调用并返回其 future）→
     * 无论成败都摘掉附件。{@code invocation} 抛出的 RuntimeException 变成异常完成的 future（与传输失败同一条处理路径）。
     *
     * @param remainingMs 剩余预算（毫秒）；≤ 0 也照发（提供方据此判「到达时已过期」，不写任何东西）
     */
    public static <R> CompletableFuture<R> callWithBudget(long remainingMs, Supplier<CompletableFuture<R>> invocation) {
        attachBudget(remainingMs);
        try {
            CompletableFuture<R> future = invocation.get();
            return future != null ? future : CompletableFuture.failedFuture(new IllegalStateException("Dubbo 返回了空的 future"));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            clearBudget();
        }
    }

    /**
     * 同 {@link #callWithBudget(long, Supplier)}，另把这一次调用的 Dubbo 超时设成 {@code timeoutMs}（调用级 {@code timeout} 附件，比引用上的缺省值优先，
     * 调完即清；先例 {@code NodeRpcClients}）。xm-team 的前三个方法用 {@code remainingMs = timeoutMs = min(3 s, 剩余请求预算)}。
     *
     * @param timeoutMs 这一次调用的 Dubbo 超时（毫秒），必须 ≥ 1
     */
    public static <R> CompletableFuture<R> callWithBudget(long remainingMs, long timeoutMs, Supplier<CompletableFuture<R>> invocation) {
        return callWithTimeout(timeoutMs, () -> callWithBudget(remainingMs, invocation));
    }

    /**
     * 只设这一次调用的 Dubbo 超时、不带预算附件（{@code runTeamGather} 用：调用级超时 = 开战锁时长，gather 不受调用方预算约束）。
     *
     * @param timeoutMs 这一次调用的 Dubbo 超时（毫秒），必须 ≥ 1
     */
    public static <R> CompletableFuture<R> callWithTimeout(long timeoutMs, Supplier<CompletableFuture<R>> invocation) {
        if (timeoutMs < 1 || timeoutMs > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("调用级超时非法: " + timeoutMs);
        }
        RpcContext.getClientAttachment().setObjectAttachment(CommonConstants.TIMEOUT_KEY, timeoutMs);
        try {
            CompletableFuture<R> future = invocation.get();
            return future != null ? future : CompletableFuture.failedFuture(new IllegalStateException("Dubbo 返回了空的 future"));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            RpcContext.getClientAttachment().removeAttachment(CommonConstants.TIMEOUT_KEY);
        }
    }

    /**
     * 在本线程上挂上预算附件：作用于本线程上<b>下一次</b> Dubbo 调用。之后必须在 {@code finally} 里 {@link #clearBudget()}——
     * 不清的话它会漏到这条线程上之后的调用里。优先用 {@link #callWithBudget}。
     */
    public static void attachBudget(long remainingMs) {
        RpcContext.getClientAttachment().setAttachment(BUDGET_MS, Long.toString(Math.max(0, remainingMs)));
    }

    /** 摘掉本线程上的预算附件（幂等）。 */
    public static void clearBudget() {
        RpcContext.getClientAttachment().removeAttachment(BUDGET_MS);
    }

    // ---------------------------------------------------------------- 提供方

    /**
     * 这次调用的本地截止 = 现在 + 预算。预算取调用方附件里的值；缺附件、不是非负十进制整数时取 {@code ownBudgetMs}；
     * 附件比 {@code ownBudgetMs} 大时也按 {@code ownBudgetMs} 收口（提供方不会为一次调用工作得比自己的整请求预算更久）。
     * 附件为 0 时返回的截止<b>一开始就是过期的</b>（{@code expired() == true}）：调用方发出时就没有预算了。
     *
     * <p>必须在服务方法入口、Dubbo 线程上同步调用。
     *
     * @param ownBudgetMs 提供方自己的整请求预算（毫秒，如 {@link MatchBudgets#DEFAULT_REQUEST_BUDGET_MS}），必须 ≥ 0
     */
    public static Deadline deadlineFromCall(long ownBudgetMs) {
        return Deadline.after(budgetOf(RpcContext.getServerAttachment().getAttachment(BUDGET_MS), ownBudgetMs));
    }

    /** {@link #deadlineFromCall} 的取值规则（纯函数，包内可见供测试）。 */
    static long budgetOf(String attachment, long ownBudgetMs) {
        if (ownBudgetMs < 0) {
            throw new IllegalArgumentException("提供方预算不能为负: " + ownBudgetMs);
        }
        if (attachment == null || attachment.isEmpty() || attachment.length() > 18) {
            return ownBudgetMs;
        }
        for (int i = 0; i < attachment.length(); i++) {
            char c = attachment.charAt(i);
            if (c < '0' || c > '9') {
                return ownBudgetMs;
            }
        }
        return Math.min(Long.parseLong(attachment), ownBudgetMs);
    }
}
