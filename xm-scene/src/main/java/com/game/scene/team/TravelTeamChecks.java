package com.game.scene.team;

import com.game.discovery.team.TeamMembership;
import com.game.scene.world.TeamChecks;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 跨 zone 传送（226）受理前的在队检查（{@link TeamChecks}，批次 5.4，zone-travel-spec §5.5）：读一次组队成员关系
 * （与组队跟随共用同一个 {@code TeamMembershipReader}），结果投递回场景逻辑线程。
 *
 * <p><b>先行件阶段的占位</b>：不读 Redis，经逻辑执行器回 {@link TeamCheck#UNKNOWN}（恰好一次、不在调用栈内，线程纪律与真实现相同）。
 * UNKNOWN 是 fail-closed 的结果——调用方回 3027、不放行，所以占位期间 226 即使被接上也传不出去。
 * 真实现：{@code reads.readAsync(playerId)} 套上 {@code timeout}，{@code inTeam()} → IN_TEAM，无队 → NOT_IN_TEAM，
 * 读失败 / 超时 / 数据损坏 → UNKNOWN，只做投递。
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
     * @param reads 成员关系的读口（占位阶段还没用上）
     * @param logic 投递回场景逻辑线程（已停止时抛拒绝异常，结果丢弃）
     */
    public TravelTeamChecks(MembershipReads reads, Executor logic) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.logic = Objects.requireNonNull(logic, "logic");
    }

    @Override
    public void check(long playerId, Duration timeout, Consumer<TeamCheck> onDone) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("在队检查的上限必须为正: " + timeout);
        }
        try {
            logic.execute(() -> onDone.accept(TeamCheck.UNKNOWN));
        } catch (RejectedExecutionException e) {
            log.debug("场景逻辑线程已停止，丢弃在队检查的结果 player={}", Long.toUnsignedString(playerId));
        }
    }
}
