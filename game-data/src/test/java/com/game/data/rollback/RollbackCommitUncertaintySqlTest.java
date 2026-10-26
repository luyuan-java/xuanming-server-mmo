package com.game.data.rollback;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ListAppliedAssetOpsSinceResponse;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.snapshot.SnapshotCauses;
import com.game.player.store.OwnerState;
import com.game.player.store.state.PlayerState;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

/** 真实 SQL 已提交后模拟 COMMIT 确认丢失：异常不能证明回滚，所有可能写入的人都必须在归属释放前复查。 */
class RollbackCommitUncertaintySqlTest {

    @ParameterizedTest
    @ValueSource(strings = {"clean", "unavailable", "late_op", "interrupted"})
    void 提交已生效但确认丢失仍复查_数据不撤销_未知与新增分歧各自明确报告(String recheck) throws Exception {
        RollbackHarness.Seams seams = new RollbackHarness.Seams();
        seams.writerTransactions = real -> new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                return real.getTransaction(definition);
            }
            @Override public void commit(TransactionStatus status) {
                real.commit(status);
                if (recheck.equals("interrupted")) Thread.currentThread().interrupt();
                throw new TransactionSystemException("注入：数据库已经 COMMIT，确认途中断连");
            }
            @Override public void rollback(TransactionStatus status) { real.rollback(status); }
        };
        try (RollbackHarness h = new RollbackHarness(Map.of(), true, seams)) {
            long player = 1001, snapshot = 6001;
            long now = System.currentTimeMillis();
            h.player(player, 1, RollbackJobSqlTest.currentState().toByteArray(), now - 5000, 3, false);
            h.logoutSnapshot(snapshot, player, now - 60_000, RollbackJobSqlTest.snapshotState().toByteArray());
            AtomicReference<OwnerState> ownerAtRecheck = new AtomicReference<>();
            h.guildAnswers.add(request -> RollbackHarness.ok());
            h.guildAnswers.add(request -> {
                ownerAtRecheck.set(h.db.tx().execute(status -> h.db.playerMapper.selectOwnerForUpdate(player)));
                return switch (recheck) {
                    case "unavailable" -> ListAppliedAssetOpsSinceResponse.getDefaultInstance();
                    case "late_op" -> RollbackHarness.ok(RollbackHarness.op(99, player, now));
                    default -> RollbackHarness.ok();
                };
            });
            var request = new RollbackRequest.Body("players", List.of(Long.toString(player)), null, null,
                    Long.toString(snapshot), null, null, null, false, false, "提交确认丢失回归", false);
            var job = h.await(h.submit(request, "commit-unknown"));

            assertThat(h.guildCalls).hasSize(2);
            assertThat(h.guildCalls.get(1).getPlayerIdsList()).containsExactly(player);
            assertThat(ownerAtRecheck.get().ownerEpoch()).isEqualTo(4);
            assertThat(ownerAtRecheck.get().released()).isFalse();
            assertThat(h.ownership.heldCount()).isZero();
            assertThat(h.tombstones).hasSize(1);
            assertThat(PlayerState.parseFrom(h.db.players.find(player).stateBytes()).getCurrency().getBalancesList())
                    .containsExactly(1000L, 0L);
            assertThat(h.count("SELECT COUNT(*) FROM player_snapshot WHERE cause = ? AND player_id = ?",
                    SnapshotCauses.PRE_ROLLBACK, player)).isEqualTo(1);
            assertThat(h.jobs.players(job.getJobId(), 0, 100)).singleElement().satisfies(row -> {
                assertThat(row.getOutcome()).isEqualTo("RESTORED");
                assertThat(row.getPreSnapshotId()).isNotZero();
            });
            var summary = h.summary(job);
            assertThat(summary.get("unconfirmedWrites").asInt()).isEqualTo(1);
            assertThat(summary.get("unconfirmedWritePlayers").get(0).asText()).isEqualTo("1001");
            switch (recheck) {
                case "late_op" -> {
                    assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_DIVERGED_AFTER_WRITE);
                    assertThat(job.getResultCode()).isEqualTo("rollback_guild_diverged_after_write");
                    assertThat(summary.get("recheck").get("newRows").asInt()).isEqualTo(1);
                }
                case "unavailable" -> {
                    assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_FAILED);
                    assertThat(job.getResultCode()).isEqualTo("post_write_unverified");
                    assertThat(summary.get("recheck").get("checkFailed").asBoolean()).isTrue();
                }
                default -> {
                    assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_FAILED);
                    assertThat(job.getResultCode()).isEqualTo("snapshot_db_error");
                    assertThat(summary.get("recheck").get("checkFailed").asBoolean()).isFalse();
                }
            }
            h.assertPlayerOutcomeLabelsWithinFixedSet();
        }
    }
}
