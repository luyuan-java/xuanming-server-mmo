package com.game.scene.team;

import com.game.discovery.team.TeamMembership;
import com.game.scene.world.TeamChecks;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 跨 zone 传送（226）受理前的在队检查（{@link TeamChecks}，批次 5.4，zone-travel-spec §5.5 第 5 步、X4）：读一次组队成员关系
 * （与组队跟随共用同一个 {@code TeamMembershipReader}），结果投递回场景逻辑线程。scene 不缓存队伍号，每次 226 都现读。
 *
 * <p>判定：{@link TeamMembership#inTeam()}（tid ≠ 0，投影缺失也算在队）→ {@link TeamCheck#IN_TEAM}（226 回 3026）；无队 / 索引键缺失 →
 * {@link TeamCheck#NOT_IN_TEAM}；读失败、超时、数据损坏（读口以异常完成）→ {@link TeamCheck#UNKNOWN}（226 回 3027，fail-closed）。
 *
 * <p>时限：读口本身没有超时（只靠 Redisson 单命令的超时与重试，最坏约 4.2 s），这里套上调用方给的上限
 * （{@code game.scene.travel.team-check-timeout}，缺省 2 s）。到时由 JDK 的延迟线程完成成 UNKNOWN；之后才回来的读结果被丢弃
 * （等待的是读口 future 的一个副本，读口自己的 future 不被改动）。
 *
 * <p>线程：{@link #check} 在场景逻辑线程上调，不阻塞；读结果在 Redisson 的回调线程（或超时的延迟线程）上出来，那里只做投递——
 * 回调<b>恰好一次、经逻辑执行器、不在 {@code check} 的调用栈内</b>。逻辑执行器已停止时结果丢弃。
 */
public final class TravelTeamChecks implements TeamChecks {

    private static final Logger log = LoggerFactory.getLogger(TravelTeamChecks.class);

    /** 读一个玩家的成员关系（生产 = {@code TeamMembershipReader::readAsync}）。不得阻塞；失败以异常完成。 */
    @FunctionalInterface
    public interface MembershipReads {
        CompletionStage<TeamMembership> readAsync(long playerId);
    }

    private final MembershipReads reads;
    private final Executor logic;

    /**
     * @param reads 成员关系的读口
     * @param logic 投递回场景逻辑线程（已停止时抛拒绝异常，结果丢弃）
     */
    public TravelTeamChecks(MembershipReads reads, Executor logic) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.logic = Objects.requireNonNull(logic, "logic");
    }

    /**
     * @throws IllegalArgumentException {@code timeout} 不为正（编程错误，在读之前抛）
     */
    @Override
    public void check(long playerId, Duration timeout, Consumer<TeamCheck> onDone) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("在队检查的上限必须为正: " + timeout);
        }
        Objects.requireNonNull(onDone, "onDone");
        // 等的是副本：到时只把副本完成成超时，读口自己的 future 不受影响；读口同步抛出 / 给空 future 也按读失败
        CompletableFuture<TeamMembership> read = new CompletableFuture<>();
        try {
            CompletionStage<TeamMembership> stage = reads.readAsync(playerId);
            if (stage == null) {
                read.completeExceptionally(new IllegalStateException("成员关系读口返回了空的 future"));
            } else {
                stage.whenComplete((membership, failure) -> {
                    if (failure != null) {
                        read.completeExceptionally(failure);
                    } else {
                        read.complete(membership);
                    }
                });
            }
        } catch (RuntimeException e) {
            read.completeExceptionally(e);
        }
        read.orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS)
                .whenComplete((membership, failure) -> deliver(playerId, onDone, toCheck(playerId, membership, failure)));
    }

    /** 读结果 → 检查结果（在完成读的线程上执行，只看读结果，不碰场景状态）。 */
    static TeamCheck toCheck(long playerId, TeamMembership membership, Throwable failure) {
        if (failure != null) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            if (cause instanceof TimeoutException) {
                log.warn("226 在队检查超时，按读失败处理（回 3027） player={}", Long.toUnsignedString(playerId));
            } else {
                log.warn("226 在队检查读失败或数据损坏，按读失败处理（回 3027） player={}: {}", Long.toUnsignedString(playerId),
                        cause.toString());
            }
            return TeamCheck.UNKNOWN;
        }
        if (membership == null) {
            log.warn("226 在队检查读到空结果，按读失败处理（回 3027） player={}", Long.toUnsignedString(playerId));
            return TeamCheck.UNKNOWN;
        }
        return membership.inTeam() ? TeamCheck.IN_TEAM : TeamCheck.NOT_IN_TEAM;
    }

    private void deliver(long playerId, Consumer<TeamCheck> onDone, TeamCheck result) {
        try {
            logic.execute(() -> onDone.accept(result));
        } catch (RejectedExecutionException e) {
            log.debug("场景逻辑线程已停止，丢弃在队检查的结果 player={}", Long.toUnsignedString(playerId));
        }
    }
}
