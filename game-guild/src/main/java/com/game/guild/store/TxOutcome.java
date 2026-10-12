package com.game.guild.store;

import com.game.guild.rules.GuildReject;
import java.util.Objects;

/**
 * 一次帮会写事务的结局（guild-spec §7.6）：业务拒绝用结果返回，不用异常——避免框架把事务标成 rollback-only，也让审批通过的
 * 1062 子分支能在 InnoDB 只回滚那一条语句之后继续删申请并提交。
 *
 * <ul>
 *   <li>{@link Ok}：已提交；</li>
 *   <li>{@link Reject}：已回滚，没有任何写生效（含写冲突类 {@link GuildReject#WRITE_CONFLICT}）；</li>
 *   <li>{@link CommitThenReject}：已提交本次做过的写（删掉过期 / 跨区 / 已入他帮的申请行），对调用方仍是拒绝
 *       （基线 errCommitThen，guild_manage_repo.go:88-95）。这几条分支只动申请行，不需要失效缓存。</li>
 * </ul>
 *
 * <p>故障不在这里：SQL 错误与请求预算用完抛 {@link com.game.common.deadline.Deadline.DependencyException}，双存储矛盾与哨兵行缺失抛
 * {@link GuildStoreException}，入参违约抛 {@link IllegalArgumentException}——上层一律定性为信封 1003 并记 ERROR。
 * {@link GuildReject#LEADER_MISMATCH} 与 {@link GuildReject#LEVEL_CONFIG_MISSING} 以 {@link Reject} 返回（事务已回滚），
 * 由 {@code GuildTips.forReject} 映射成故障。
 */
public sealed interface TxOutcome<T> permits TxOutcome.Ok, TxOutcome.Reject, TxOutcome.CommitThenReject {

    /** 已提交；{@code value} 可以是 null（没有结果的写，如撤回申请）。 */
    record Ok<T>(T value) implements TxOutcome<T> {
    }

    /** 已回滚。 */
    record Reject<T>(GuildReject reason) implements TxOutcome<T> {
        public Reject {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** 先提交了本次做过的写，再回拒绝。 */
    record CommitThenReject<T>(GuildReject reason) implements TxOutcome<T> {
        public CommitThenReject {
            Objects.requireNonNull(reason, "reason");
        }
    }

    static <T> TxOutcome<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> TxOutcome<T> reject(GuildReject reason) {
        return new Reject<>(reason);
    }

    static <T> TxOutcome<T> commitThenReject(GuildReject reason) {
        return new CommitThenReject<>(reason);
    }

    /** 是否成功提交。 */
    default boolean isOk() {
        return this instanceof Ok;
    }

    /** 拒绝原因（{@link Reject} 与 {@link CommitThenReject}）；成功时为 null。 */
    default GuildReject rejection() {
        return switch (this) {
            case Ok<T> ok -> null;
            case Reject<T> r -> r.reason();
            case CommitThenReject<T> r -> r.reason();
        };
    }

    /** 成功时的结果；不是 {@link Ok} 时抛 {@link IllegalStateException}（调用方应先判 {@link #isOk}）。 */
    default T orThrow() {
        if (this instanceof Ok<T> ok) {
            return ok.value();
        }
        throw new IllegalStateException("事务没有成功: " + rejection());
    }

    /** 把拒绝原样转成另一结果类型的拒绝（保留 Reject / CommitThenReject 的区别）；对 {@link Ok} 调用抛 {@link IllegalStateException}。 */
    default <U> TxOutcome<U> rejectAs() {
        return switch (this) {
            case Ok<T> ok -> throw new IllegalStateException("成功的结局不能转成拒绝");
            case Reject<T> r -> new Reject<>(r.reason());
            case CommitThenReject<T> r -> new CommitThenReject<>(r.reason());
        };
    }
}
