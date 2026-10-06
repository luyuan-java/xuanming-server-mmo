package com.game.data.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.game.api.proto.ListAppliedAssetOpsSinceResponse;
import com.game.api.proto.ListAppliedResult;
import com.game.audit.proto.TransactionReason;
import com.game.data.ops.OpsJobStore;
import com.game.data.ops.pb.OpsJobEventRow;
import com.game.data.ops.pb.OpsJobEventType;
import com.game.data.ops.pb.OpsJobPlayerRow;
import com.game.data.ops.pb.OpsJobRow;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.snapshot.SnapshotCauses;
import com.game.data.store.PlayerSnapshotEntry;
import com.game.data.store.TransactionLogMapper;
import com.game.data.txlog.TransactionLogRow;
import com.game.player.store.OwnerState;
import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerStore.ClaimResult;
import com.game.player.store.state.BattleLedgerEntry;
import com.game.player.store.state.BattleLedgerState;
import com.game.player.store.state.PlayerState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 回档作业里实现记录声明了、此前没有用例钉住的分支（审计 OPS-13，data-ops-spec §4.6–§4.8、§7.7、§13.1 第 6 / 7 / 10 条、§13.2 第 7 条）：
 * 写后复查问不到帮会、连续 3 人写失败 / 号源失效即停、写阶段超时、回收逆转与放行开关、写阶段失去围栏、整份回档修复损坏的现档、
 * 运维持有期间别人夺不到，以及战斗结算账本随资产组整段回退（审计 OPS-9）。生产的受理 / 作业框架 / 栅栏 / 写事务，
 * H2 或 {@code -Dxm.it.mysql} 的真 MySQL。
 *
 * <p>「在某一步出事」靠两种钩子定时：帮会替身的应答函数（它在 {@code data-ops} 线程上、沉降之后写之前被调到；第二次调用是写后复查），
 * 以及 {@link RollbackHarness.Seams} 包一层的流水 Mapper / 作业表访问（哪一次调用抛异常、拖时间由用例定，其余原样落库）。
 */
class RollbackJobBranchesSqlTest {

    private static final long P = 1001;
    private static final PlayerState CURRENT = RollbackJobSqlTest.currentState();
    private static final PlayerState SNAPSHOT = RollbackJobSqlTest.snapshotState();
    private static final byte[] CORRUPT = {(byte) 0xFF, 0x01};

    private RollbackHarness h;
    /** 写事务调了几次「插回档流水」（= 有几个人的写事务走到了第 5 步）。 */
    private final AtomicInteger txlogCalls = new AtomicInteger();

    @AfterEach
    void tearDown() throws Exception {
        if (h != null) {
            try {
                h.assertPlayerOutcomeLabelsWithinFixedSet();
            } finally {
                h.close();
            }
        }
    }

    // ------------------------------------------------------------------ 钩子

    /** 第 {@code call} 次插回档流水时怎么办：调 {@code real} 照常插、抛异常（这个人的写事务回滚）、或者插完再做点什么。 */
    @FunctionalInterface
    private interface TxlogHook {
        int insert(int call, List<TransactionLogRow> rows, long now, TransactionLogMapper real) throws Exception;
    }

    private RollbackHarness.Seams txlogHook(TxlogHook hook) {
        RollbackHarness.Seams seams = new RollbackHarness.Seams();
        seams.writerTxlog = real -> {
            TransactionLogMapper wrapped = mock(TransactionLogMapper.class, delegatesTo(real));
            doAnswer(inv -> hook.insert(txlogCalls.incrementAndGet(), inv.getArgument(0), inv.getArgument(1), real))
                    .when(wrapped).insertDirectAll(anyList(), anyLong());
            return wrapped;
        };
        return seams;
    }

    /** 每条作业事件真正插入之后被调到（可以拖时间）。 */
    @FunctionalInterface
    private interface AfterEvent {
        void on(OpsJobEventRow row) throws Exception;
    }

    private static RollbackHarness.Seams afterEvent(AfterEvent hook) {
        RollbackHarness.Seams seams = new RollbackHarness.Seams();
        seams.jobs = real -> {
            OpsJobStore wrapped = mock(OpsJobStore.class, delegatesTo(real));
            doAnswer(inv -> {
                OpsJobEventRow row = inv.getArgument(0);
                real.insertEvent(row);
                hook.on(row);
                return null;
            }).when(wrapped).insertEvent(any());
            return wrapped;
        };
        return seams;
    }

    // ------------------------------------------------------------------ 素材与观察

    /** 离线玩家（epoch 3、已释放、现档 = {@link #CURRENT}）+ 一份 60 s 前的 LOGOUT 快照（号 = 5000 + 玩家号，内容 = {@link #SNAPSHOT}）。 */
    private void player(long playerId) {
        player(playerId, CURRENT.toByteArray(), SNAPSHOT.toByteArray());
    }

    private void player(long playerId, byte[] current, byte[] snapshot) {
        long now = System.currentTimeMillis();
        h.player(playerId, 1, current, now - 5000, 3, false);
        h.logoutSnapshot(5000 + playerId, playerId, now - 60_000, snapshot);
    }

    private List<Long> players(long first, int count) {
        List<Long> ids = new ArrayList<>();
        for (long p = first; p < first + count; p++) {
            player(p);
            ids.add(p);
        }
        return ids;
    }

    /** 单人按号回档。 */
    private static RollbackRequest.Body single(long playerId, List<String> sections, boolean acceptDivergence,
                                               boolean acceptRecallReversal) {
        return new RollbackRequest.Body("players", List.of(Long.toString(playerId)), null, null,
                Long.toString(5000 + playerId), null, sections, null, acceptDivergence, acceptRecallReversal, "客诉补偿", false);
    }

    /** 多人按时刻回档（目标时刻 = 30 s 前：每人选中自己 60 s 前的那份快照）。 */
    private static RollbackRequest.Body many(List<Long> playerIds, boolean acceptDivergence) {
        return new RollbackRequest.Body("players", playerIds.stream().map(Long::toUnsignedString).toList(), null, null,
                null, System.currentTimeMillis() - 30_000, null, null, acceptDivergence, false, "客诉补偿", false);
    }

    private PlayerState persisted(long playerId) throws Exception {
        return PlayerState.parseFrom(h.db.players.find(playerId).stateBytes());
    }

    private OwnerState owner(long playerId) {
        return h.db.tx().execute(s -> h.db.playerMapper.selectOwnerForUpdate(playerId));
    }

    /** 作业明细：玩家号 → 结局（按玩家号升序）。 */
    private Map<String, String> outcomes(long jobId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (OpsJobPlayerRow row : h.jobs.players(jobId, 0, 100)) {
            out.put(Long.toUnsignedString(row.getPlayerId()), row.getOutcome());
        }
        return out;
    }

    private JsonNode event(long jobId, OpsJobEventType type) throws Exception {
        List<OpsJobEventRow> rows = h.jobs.events(jobId).stream().filter(e -> e.getType() == type).toList();
        assertThat(rows).as("%s 事件", type).hasSize(1);
        return h.json.readTree(rows.get(0).getPayloadJson());
    }

    private int preSnapshots(long playerId) {
        return h.count("SELECT COUNT(*) FROM player_snapshot WHERE cause = ? AND player_id = ?", SnapshotCauses.PRE_ROLLBACK,
                playerId);
    }

    private int restoreRows(long playerId) {
        return h.count("SELECT COUNT(*) FROM transaction_log WHERE reason = 16 AND (from_player = ? OR to_player = ?)", playerId,
                playerId);
    }

    /** 这些玩家什么也没被写：现档逐字节没变、没有安全快照、没有回档流水。 */
    private void assertUntouched(byte[] expectedState, long... playerIds) {
        for (long playerId : playerIds) {
            assertThat(h.db.players.find(playerId).stateBytes()).as("玩家 %d 的现档", playerId).isEqualTo(expectedState);
            assertThat(h.db.players.find(playerId).getStateSavedEpoch()).as("玩家 %d 的现档仍是 epoch 3 写的", playerId)
                    .isEqualTo(3L);
            assertThat(preSnapshots(playerId)).as("玩家 %d 的安全快照", playerId).isZero();
            assertThat(restoreRows(playerId)).as("玩家 %d 的回档流水", playerId).isZero();
        }
    }

    private void assertUntouched(long... playerIds) {
        assertUntouched(CURRENT.toByteArray(), playerIds);
    }

    /** 这些玩家被运维夺到过（epoch 3 → 4），收尾时已带围栏释放。 */
    private void assertReleased(long... playerIds) {
        for (long playerId : playerIds) {
            assertThat(owner(playerId)).as("玩家 %d 的归属", playerId).satisfies(o -> {
                assertThat(o.ownerEpoch()).isEqualTo(4);
                assertThat(o.released()).isTrue();
            });
        }
        assertThat(h.ownership.heldCount()).isZero();
    }

    private double divergenceChecks(String source, String result) {
        var counter = h.meters.find("xm.data.rollback.divergence.check").tag("source", source).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private static void sleepPast(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    // ------------------------------------------------------------------ 写后复查问不到帮会

    @Test
    void 写后复查问不到帮会_post_write_unverified_状态照写入结果定_数据不撤销_复查时归属还在手里() throws Exception {
        h = new RollbackHarness(Map.of());
        player(P);
        AtomicReference<OwnerState> ownerAtRecheck = new AtomicReference<>();
        h.guildAnswers.add(r -> RollbackHarness.ok());
        h.guildAnswers.add(r -> {
            ownerAtRecheck.set(owner(P));
            return ListAppliedAssetOpsSinceResponse.newBuilder().setResult(ListAppliedResult.LIST_APPLIED_RESULT_UNAVAILABLE)
                    .setDetail("帮会库不可达").build();
        });

        OpsJobRow job = h.await(h.submit(single(P, null, false, false), "k1"));

        // 状态按写入结果定（全写成 = SUCCEEDED），结果码单独报「写后没核对上」；不自动撤销
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(job.getResultCode()).isEqualTo("post_write_unverified");
        assertThat(job.getPlayersAffected()).isEqualTo(1);
        assertThat(outcomes(job.getJobId())).containsExactly(entry("1001", "RESTORED"));
        assertThat(persisted(P).getCurrency().getBalancesList()).containsExactly(1000L, 0L);
        assertThat(preSnapshots(P)).isEqualTo(1);
        // RECHECK 事件与摘要都记下「问不到」和原因
        JsonNode recheck = event(job.getJobId(), OpsJobEventType.OPS_JOB_EVENT_RECHECK);
        assertThat(recheck.get("checkFailed").asBoolean()).isTrue();
        assertThat(recheck.get("failure").asText()).contains("LIST_APPLIED_RESULT_UNAVAILABLE").contains("帮会库不可达");
        assertThat(h.summary(job).at("/recheck/checkFailed").asBoolean()).isTrue();
        assertThat(h.eventTypes(job.getJobId())).containsExactly("STARTED", "PLANNED", "CLAIMED", "CHECK", "WRITE", "RECHECK",
                "RESULT");
        // 复查在释放之前（I4）：问帮会的那一刻归属还是我们的 (4, 未释放)，之后才释放
        assertThat(h.guildCalls).hasSize(2);
        assertThat(ownerAtRecheck.get().ownerEpoch()).isEqualTo(4);
        assertThat(ownerAtRecheck.get().released()).isFalse();
        assertReleased(P);
        assertThat(divergenceChecks("guild", "post_write_failed")).isEqualTo(1);
        assertThat(divergenceChecks("guild", "post_write_clean")).isZero();
    }

    @Test
    void 写成一部分而写后复查问不到帮会_PARTIAL_结果码仍是post_write_unverified_只复查写成的人() throws Exception {
        h = new RollbackHarness(Map.of());
        player(2001);
        // 2002 在线、缺省 reject：夺不到
        h.player(2002, 1, CURRENT.toByteArray(), System.currentTimeMillis() - 5000, 3, true);
        h.logoutSnapshot(7002, 2002, System.currentTimeMillis() - 60_000, SNAPSHOT.toByteArray());
        h.guildAnswers.add(r -> RollbackHarness.ok());
        h.guildAnswers.add(r -> ListAppliedAssetOpsSinceResponse.getDefaultInstance());

        OpsJobRow job = h.await(h.submit(many(List.of(2001L, 2002L), false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_PARTIAL);
        assertThat(job.getResultCode()).isEqualTo("post_write_unverified");
        assertThat(outcomes(job.getJobId())).containsExactly(entry("2001", "RESTORED"), entry("2002", "player_online"));
        assertThat(h.guildCalls.get(1).getPlayerIdsList()).containsExactly(2001L);
        assertUntouched(2002);
    }

    // ------------------------------------------------------------------ 连续 3 人写失败 / 号源失效即停

    @Test
    void 连续3人写失败即停_剩下的not_executed_一个没写成_FAILED_snapshot_db_error_全部释放() throws Exception {
        h = new RollbackHarness(Map.of(), true, txlogHook((call, rows, now, real) -> {
            throw new DataAccessResourceFailureException("注入：流水表写不进 #" + call);
        }));
        List<Long> ids = players(2001, 5);

        OpsJobRow job = h.await(h.submit(many(ids, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_FAILED);
        assertThat(job.getResultCode()).isEqualTo("snapshot_db_error");
        assertThat(job.getPlayersPlanned()).isEqualTo(5);
        assertThat(job.getPlayersAffected()).isZero();
        assertThat(job.getPlayersFailed()).isEqualTo(5);
        assertThat(outcomes(job.getJobId())).containsExactly(entry("2001", "failed"), entry("2002", "failed"),
                entry("2003", "failed"), entry("2004", "not_executed"), entry("2005", "not_executed"));
        // 第 3 个人失败就停：第 4、5 个人的写事务根本没开
        assertThat(txlogCalls).hasValue(3);
        JsonNode write = event(job.getJobId(), OpsJobEventType.OPS_JOB_EVENT_WRITE);
        assertThat(write.toString()).isEqualTo("{\"restored\":0,\"failed\":3,\"stopped\":true,\"timedOut\":false}");
        // 每个失败的人的事务都整体回滚（安全快照、覆盖写都不在）；没人写成就不做写后复查
        assertUntouched(2001, 2002, 2003, 2004, 2005);
        assertThat(h.eventTypes(job.getJobId())).containsExactly("STARTED", "PLANNED", "CLAIMED", "CHECK", "WRITE", "RESULT");
        assertThat(h.guildCalls).hasSize(1);
        assertReleased(2001, 2002, 2003, 2004, 2005);
        assertThat(h.tombstones).hasSize(5);
        assertThat(h.playersCounted("failed")).isEqualTo(3);
        assertThat(h.playersCounted("not_executed")).isEqualTo(2);
        assertThat(h.playersCounted("restored")).isZero();
    }

    @Test
    void 失败不连续不算_中间有人写成就重新数_写成一部分_PARTIAL_partial() throws Exception {
        // 7 人：成、败、成、败、败、败 → 第 6 人是连续第 3 个失败，停；第 7 人 not_executed。总共败了 4 个，但第 2 人那次没让作业停下
        h = new RollbackHarness(Map.of(), true, txlogHook((call, rows, now, real) -> {
            if (call == 1 || call == 3) {
                return real.insertDirectAll(rows, now);
            }
            throw new DataAccessResourceFailureException("注入：流水表写不进 #" + call);
        }));
        List<Long> ids = players(2001, 7);

        OpsJobRow job = h.await(h.submit(many(ids, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_PARTIAL);
        assertThat(job.getResultCode()).isEqualTo("partial");
        assertThat(job.getPlayersAffected()).isEqualTo(2);
        assertThat(job.getPlayersFailed()).isEqualTo(5);
        assertThat(outcomes(job.getJobId())).containsExactly(entry("2001", "RESTORED"), entry("2002", "failed"),
                entry("2003", "RESTORED"), entry("2004", "failed"), entry("2005", "failed"), entry("2006", "failed"),
                entry("2007", "not_executed"));
        assertThat(txlogCalls).hasValue(6);
        assertThat(event(job.getJobId(), OpsJobEventType.OPS_JOB_EVENT_WRITE).toString())
                .isEqualTo("{\"restored\":2,\"failed\":4,\"stopped\":true,\"timedOut\":false}");
        // 写成的两个人四件事齐全，失败与没轮到的人什么也没留
        for (long p : new long[] {2001, 2003}) {
            assertThat(persisted(p).getCurrency().getBalancesList()).as("玩家 %d", p).containsExactly(1000L, 0L);
            assertThat(preSnapshots(p)).isEqualTo(1);
            assertThat(restoreRows(p)).isEqualTo(4);
        }
        assertUntouched(2002, 2004, 2005, 2006, 2007);
        // 写后复查只问写成的人
        assertThat(h.guildCalls).hasSize(2);
        assertThat(h.guildCalls.get(1).getPlayerIdsList()).containsExactly(2001L, 2003L);
        assertReleased(2001, 2002, 2003, 2004, 2005, 2006, 2007);
    }

    @Test
    void 号源失效立即停_不等够3人_发不出号的那个人id_unavailable_其余not_executed_零写入() throws Exception {
        h = new RollbackHarness(Map.of());
        List<Long> ids = players(2001, 3);
        // 帮会检查通过之后、第一笔写之前，发号租约失效
        h.guildAnswers.add(r -> {
            h.lease.valid.set(false);
            return RollbackHarness.ok();
        });

        OpsJobRow job = h.await(h.submit(many(ids, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_FAILED);
        assertThat(job.getResultCode()).isEqualTo("snapshot_db_error");
        assertThat(outcomes(job.getJobId())).containsExactly(entry("2001", "id_unavailable"), entry("2002", "not_executed"),
                entry("2003", "not_executed"));
        assertThat(event(job.getJobId(), OpsJobEventType.OPS_JOB_EVENT_WRITE).toString())
                .isEqualTo("{\"restored\":0,\"failed\":1,\"stopped\":true,\"timedOut\":false}");
        assertUntouched(2001, 2002, 2003);
        assertReleased(2001, 2002, 2003);
    }

    @Test
    void 写成一人之后号源失效_PARTIAL_之后的人不再写() throws Exception {
        h = new RollbackHarness(Map.of(), true, txlogHook((call, rows, now, real) -> {
            int inserted = real.insertDirectAll(rows, now);
            h.lease.valid.set(false); // 第 1 个人的流水号都发完了，他的事务照常提交；从第 2 个人起发不出号
            return inserted;
        }));
        List<Long> ids = players(2001, 3);

        OpsJobRow job = h.await(h.submit(many(ids, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_PARTIAL);
        assertThat(job.getResultCode()).isEqualTo("partial");
        assertThat(outcomes(job.getJobId())).containsExactly(entry("2001", "RESTORED"), entry("2002", "id_unavailable"),
                entry("2003", "not_executed"));
        assertThat(txlogCalls).hasValue(1);
        assertThat(persisted(2001).getCurrency().getBalancesList()).containsExactly(1000L, 0L);
        assertUntouched(2002, 2003);
        assertReleased(2001, 2002, 2003);
    }

    // ------------------------------------------------------------------ 作业时限

    @Test
    void 写阶段超时而一个也没写成_FAILED_job_timeout_不是拿明细结局当规则拒绝_全部not_executed() throws Exception {
        // 放行事件（ACCEPTED）写进去之后时限才到：之前的各道超时检查都过了，超时落在写循环的第一个人之前
        h = new RollbackHarness(Map.of("xm.data.ops.job-timeout", "2s"), true, afterEvent(row -> {
            if (row.getType() == OpsJobEventType.OPS_JOB_EVENT_ACCEPTED) {
                sleepPast(2400);
            }
        }));
        List<Long> ids = players(2001, 2);
        long after = System.currentTimeMillis();
        h.guildAnswers.add(r -> RollbackHarness.ok(RollbackHarness.op(70, 2001, after)));

        OpsJobRow job = h.await(h.submit(many(ids, true), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_FAILED);
        assertThat(job.getResultCode()).isEqualTo("job_timeout");
        assertThat(job.getPlayersAffected()).isZero();
        assertThat(job.getPlayersFailed()).isEqualTo(2);
        assertThat(outcomes(job.getJobId())).containsExactly(entry("2001", "not_executed"), entry("2002", "not_executed"));
        assertThat(h.eventTypes(job.getJobId())).containsExactly("STARTED", "PLANNED", "CLAIMED", "CHECK", "ACCEPTED", "WRITE",
                "RESULT");
        assertThat(event(job.getJobId(), OpsJobEventType.OPS_JOB_EVENT_WRITE).toString())
                .isEqualTo("{\"restored\":0,\"failed\":0,\"stopped\":true,\"timedOut\":true}");
        assertThat(h.summary(job).get("timedOut").asBoolean()).isTrue();
        // 放行事件已经写了，作业行如实记下（放行过、但一个人也没来得及写）
        assertThat(job.getAcceptedDivergence()).isTrue();
        assertThat(job.getDivergenceRows()).isEqualTo(1);
        assertUntouched(2001, 2002);
        assertReleased(2001, 2002);
        assertThat(h.guildCalls).as("没人写成：不做写后复查").hasSize(1);
        assertThat(h.playersCounted("not_executed")).isEqualTo(2);
    }

    @Test
    void 写阶段超时而已写成一部分_PARTIAL_job_timeout_写完当前玩家才停_摘要带timedOut() throws Exception {
        h = new RollbackHarness(Map.of("xm.data.ops.job-timeout", "2s"), true, txlogHook((call, rows, now, real) -> {
            int inserted = real.insertDirectAll(rows, now);
            if (call == 1) {
                sleepPast(2400); // 第 1 个人的写事务做到一半时限就到了：这笔照常提交，不半途放弃
            }
            return inserted;
        }));
        List<Long> ids = players(2001, 3);

        OpsJobRow job = h.await(h.submit(many(ids, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_PARTIAL);
        assertThat(job.getResultCode()).isEqualTo("job_timeout");
        assertThat(job.getPlayersAffected()).isEqualTo(1);
        assertThat(job.getPlayersFailed()).isEqualTo(2);
        assertThat(outcomes(job.getJobId())).containsExactly(entry("2001", "RESTORED"), entry("2002", "not_executed"),
                entry("2003", "not_executed"));
        assertThat(txlogCalls).hasValue(1);
        assertThat(event(job.getJobId(), OpsJobEventType.OPS_JOB_EVENT_WRITE).toString())
                .isEqualTo("{\"restored\":1,\"failed\":0,\"stopped\":true,\"timedOut\":true}");
        assertThat(h.summary(job).get("timedOut").asBoolean()).isTrue();
        assertThat(persisted(2001).getCurrency().getBalancesList()).containsExactly(1000L, 0L);
        assertThat(preSnapshots(2001)).isEqualTo(1);
        assertUntouched(2002, 2003);
        // 写成的那个人照样做写后复查
        assertThat(h.guildCalls).hasSize(2);
        assertThat(h.guildCalls.get(1).getPlayerIdsList()).containsExactly(2001L);
        assertReleased(2001, 2002, 2003);
    }

    @Test
    void 帮会检查做完才超时_不写ACCEPTED_FAILED_job_timeout_零写入() throws Exception {
        h = new RollbackHarness(Map.of("xm.data.ops.job-timeout", "2s"));
        player(P);
        long after = System.currentTimeMillis();
        h.guildAnswers.add(r -> {
            try {
                sleepPast(2400); // 帮会应答得很慢：沉降之后的那次超时检查已经过了，检查做完才发现超时
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return RollbackHarness.ok(RollbackHarness.op(70, P, after));
        });

        OpsJobRow job = h.await(h.submit(single(P, null, true, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_FAILED);
        assertThat(job.getResultCode()).isEqualTo("job_timeout");
        assertThat(h.summary(job).get("phase").asText()).isEqualTo("checked");
        // 带了 acceptDivergence 也不放行：没有 ACCEPTED 事件、作业行上没立「已放行」
        assertThat(h.eventTypes(job.getJobId())).containsExactly("STARTED", "PLANNED", "CLAIMED", "CHECK", "RESULT");
        assertThat(job.getAcceptedDivergence()).isFalse();
        assertThat(job.getDivergenceRows()).isEqualTo(1);
        assertThat(outcomes(job.getJobId())).containsExactly(entry("1001", "rejected"));
        assertUntouched(P);
        assertReleased(P);
    }

    // ------------------------------------------------------------------ 回收逆转

    private void txlogRow(long txId, int reason, long timeMs, long fromPlayer, long toPlayer) {
        h.db.jdbc().update("INSERT INTO transaction_log (tx_id, time_ms, reason, kind, from_player, to_player, currency_type, "
                + "currency_delta, extra, zone_id, ingested_at) VALUES (?, ?, ?, 1, ?, ?, 0, -100, '', 1, ?)", txId, timeMs,
                reason, fromPlayer, toPlayer, timeMs);
    }

    @Test
    void 快照之后有回收扣减_缺省拒绝_recall_reversal_零写入_快照之前的_别人的_别的原因的都不算() throws Exception {
        h = new RollbackHarness(Map.of());
        player(P);
        long snapshotTime = h.db.snapshots.findById(5000 + P).getTimeMs();
        txlogRow(9001, TransactionReason.TX_BATCH_RECALL_VALUE, snapshotTime + 1000, P, 0);  // 算：快照之后从他身上回收
        txlogRow(9002, TransactionReason.TX_CLAWBACK_VALUE, snapshotTime - 1000, P, 0);      // 快照之前：快照里已经是扣过的
        txlogRow(9003, TransactionReason.TX_BATCH_RECALL_VALUE, snapshotTime + 1000, 0, P);  // 他是获得方，不是被回收的
        txlogRow(9004, TransactionReason.TX_GM_GRANT_VALUE, snapshotTime + 1000, P, 0);      // 别的原因
        txlogRow(9005, TransactionReason.TX_CLAWBACK_VALUE, snapshotTime + 1000, 4242, 0);   // 别人的

        OpsJobRow job = h.await(h.submit(single(P, null, false, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(job.getResultCode()).isEqualTo("recall_reversal");
        assertThat(job.getAcceptedRecallReversal()).isFalse();
        assertThat(job.getDivergenceRows()).isZero();
        JsonNode check = h.summary(job);
        assertThat(check.get("recallReversalCount").asInt()).isEqualTo(1);
        assertThat(check.get("recallReversal")).hasSize(1);
        assertThat(check.at("/recallReversal/0/playerId").asText()).isEqualTo("1001");
        assertThat(check.at("/recallReversal/0/txId").asText()).isEqualTo("9001");
        assertThat(check.at("/recallReversal/0/reason").asInt()).isEqualTo(TransactionReason.TX_BATCH_RECALL_VALUE);
        assertThat(h.eventTypes(job.getJobId())).containsExactly("STARTED", "PLANNED", "CLAIMED", "CHECK", "RESULT");
        assertThat(outcomes(job.getJobId())).containsExactly(entry("1001", "rejected"));
        assertUntouched(P);
        assertReleased(P);
        assertThat(divergenceChecks("recall", "divergence")).isEqualTo(1);
        assertThat(divergenceChecks("guild", "clean")).isEqualTo(1);
    }

    @Test
    void 回收逆转要acceptRecallReversal才放行_acceptDivergence代替不了_放行先写ACCEPTED再写_作业行记下() throws Exception {
        h = new RollbackHarness(Map.of());
        player(P);
        long snapshotTime = h.db.snapshots.findById(5000 + P).getTimeMs();
        txlogRow(9001, TransactionReason.TX_CLAWBACK_VALUE, snapshotTime + 1000, P, 0);

        // 只带 acceptDivergence：那是给帮会 / 账本分歧的开关，管不了回收逆转
        OpsJobRow wrongSwitch = h.await(h.submit(single(P, null, true, false), "k1"));
        assertThat(wrongSwitch.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(wrongSwitch.getResultCode()).isEqualTo("recall_reversal");
        assertUntouched(P);

        OpsJobRow job = h.await(h.submit(single(P, null, false, true), "k2"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(job.getResultCode()).isEqualTo("ok");
        assertThat(job.getAcceptedRecallReversal()).isTrue();
        assertThat(job.getAcceptedDivergence()).isFalse();
        assertThat(h.eventTypes(job.getJobId())).containsExactly("STARTED", "PLANNED", "CLAIMED", "CHECK", "ACCEPTED", "WRITE",
                "RECHECK", "RESULT");
        // ACCEPTED 事件留下放行的是谁、为什么、放过了哪几条
        JsonNode accepted = event(job.getJobId(), OpsJobEventType.OPS_JOB_EVENT_ACCEPTED);
        assertThat(accepted.get("operator").asText()).isEqualTo("ops");
        assertThat(accepted.get("reason").asText()).isEqualTo("客诉补偿");
        assertThat(accepted.get("recallReversalCount").asInt()).isEqualTo(1);
        assertThat(accepted.at("/recallReversal/0/txId").asText()).isEqualTo("9001");
        assertThat(persisted(P).getCurrency().getBalancesList()).containsExactly(1000L, 0L);
        assertThat(preSnapshots(P)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 写阶段失去围栏

    @Test
    void 沉降期间归属被夺走而续约已发现_写阶段跳过这个人_fence_lost_不写墓碑不释放别人的归属_其余照写() throws Exception {
        h = new RollbackHarness(Map.of());
        List<Long> ids = players(2001, 2);
        h.guildAnswers.add(r -> {
            // 2001 的租约过期后被新的进场夺走（epoch 4 → 5，由对方持有）；栅栏线程这一拍续约时发现了
            h.db.jdbc().update("UPDATE player SET owner_epoch = 5, owner_released = 0, owner_lease_until = ? "
                    + "WHERE player_id = 2001", System.currentTimeMillis() + 30_000);
            h.ownership.renew();
            return RollbackHarness.ok();
        });
        h.guildAnswers.add(r -> RollbackHarness.ok());

        OpsJobRow job = h.await(h.submit(many(ids, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_PARTIAL);
        assertThat(job.getResultCode()).isEqualTo("partial");
        assertThat(outcomes(job.getJobId())).containsExactly(entry("2001", "fence_lost"), entry("2002", "RESTORED"));
        assertUntouched(2001);
        assertThat(persisted(2002).getCurrency().getBalancesList()).containsExactly(1000L, 0L);
        // 2001 已经不是我们的：收尾不给他写墓碑、也不去释放（对方的 (5, 未释放) 原样）
        assertThat(owner(2001).ownerEpoch()).isEqualTo(5);
        assertThat(owner(2001).released()).isFalse();
        assertThat(h.tombstones).hasSize(1);
        assertThat(h.tombstones.get(0)).containsExactly(2002, 4);
        assertReleased(2002);
        assertThat(h.ownership.lost(2001)).as("作业收尾后失去标记清掉").isFalse();
        assertThat(h.meters.get("xm.data.ops.fence.lost").counter().count()).isEqualTo(1);
        assertThat(h.guildCalls.get(1).getPlayerIdsList()).containsExactly(2002L);
        assertThat(h.playersCounted("fence_lost")).isEqualTo(1);
    }

    @Test
    void 归属被夺走而续约还没发现_写事务开头加锁核对不过_fence_lost_零写入_对方的归属不受影响() throws Exception {
        h = new RollbackHarness(Map.of());
        player(P);
        h.guildAnswers.add(r -> {
            h.db.jdbc().update("UPDATE player SET owner_epoch = 5, owner_released = 0, owner_lease_until = ? "
                    + "WHERE player_id = ?", System.currentTimeMillis() + 30_000, P);
            return RollbackHarness.ok();
        });

        OpsJobRow job = h.await(h.submit(single(P, null, false, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(job.getResultCode()).isEqualTo("fence_lost");
        assertThat(outcomes(job.getJobId())).containsExactly(entry("1001", "fence_lost"));
        assertUntouched(P);
        // 我们带着 epoch 4 去释放改不到行：对方的 (5, 未释放) 原样
        assertThat(owner(P).ownerEpoch()).isEqualTo(5);
        assertThat(owner(P).released()).isFalse();
        assertThat(h.ownership.heldCount()).isZero();
        assertThat(h.guildCalls).as("没人写成：不做写后复查").hasSize(1);
    }

    // ------------------------------------------------------------------ 整份回档修复损坏的现档

    @Test
    void 现档损坏_部分回档state_invalid_整份回档缺省因不可证明被拒_带原因放行后写成_安全快照留下损坏的原字节() throws Exception {
        h = new RollbackHarness(Map.of());
        player(P, CORRUPT, SNAPSHOT.toByteArray());

        // SECTIONS 要以现档为底：解析不了就不写，当场释放、不去问帮会
        OpsJobRow sections = h.await(h.submit(single(P, List.of("level"), true, false), "k-sections"));
        assertThat(sections.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(sections.getResultCode()).isEqualTo("state_invalid");
        assertThat(outcomes(sections.getJobId())).containsExactly(entry("1001", "state_invalid"));
        assertThat(h.guildCalls).isEmpty();
        assertUntouched(CORRUPT, P);

        // FULL 正是修复手段，但现档的账本读不出来 → 账本差集不可证明，缺省拒绝
        OpsJobRow rejected = h.await(h.submit(single(P, null, false, false), "k-full"));
        assertThat(rejected.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(rejected.getResultCode()).isEqualTo("ledger_divergence");
        assertThat(rejected.getUnprovablePlayers()).isEqualTo(1);
        assertThat(rejected.getDivergenceRows()).isZero();
        assertThat(h.summary(rejected).at("/ledger/unprovableCount").asInt()).isEqualTo(1);
        assertThat(h.summary(rejected).at("/ledger/unprovablePlayers/0").asText()).isEqualTo("1001");
        assertUntouched(CORRUPT, P);
        assertThat(divergenceChecks("ledger", "unprovable")).isEqualTo(1);

        OpsJobRow job = h.await(h.submit(single(P, null, true, false), "k-full-accepted"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(job.getAcceptedDivergence()).isTrue();
        assertThat(job.getUnprovablePlayers()).isEqualTo(1);
        assertThat(h.eventTypes(job.getJobId())).containsSubsequence("CHECK", "ACCEPTED", "WRITE");
        assertThat(persisted(P)).isEqualTo(SNAPSHOT);
        OpsJobPlayerRow detail = h.jobs.players(job.getJobId(), 0, 10).get(0);
        assertThat(detail.getOutcome()).isEqualTo("RESTORED");
        JsonNode d = h.json.readTree(detail.getDetailJson());
        assertThat(d.get("currentStateInvalid").asBoolean()).isTrue();
        assertThat(d.has("txlogSkipped")).isTrue();
        // 被覆盖之前的损坏字节原样留在安全快照里（事后还能取证）；前后余额无从得知，不写回档流水
        PlayerSnapshotEntry pre = h.db.snapshots.findById(detail.getPreSnapshotId());
        assertThat(pre.getCause()).isEqualTo(SnapshotCauses.PRE_ROLLBACK);
        assertThat(pre.getPlayerState()).isEqualTo(CORRUPT);
        assertThat(restoreRows(P)).isZero();
    }

    // ------------------------------------------------------------------ 运维持有期间别人夺不到

    @Test
    void 运维持有期间_login的夺权一直得到Held且带运维的epoch_写的仍是运维的epoch_收尾释放后才夺得到() throws Exception {
        h = new RollbackHarness(Map.of());
        player(P);
        // login 用它自己的 PlayerStore（同一张表、同一套 SQL）；进游戏时调 claimOwnership，Held → 发让出请求（xm-data 不订阅）→ 2005
        PlayerStore login = h.db.playerStore(System::currentTimeMillis);
        List<ClaimResult> seen = new CopyOnWriteArrayList<>();
        h.guildAnswers.add(r -> {
            seen.add(h.db.tx().execute(s -> login.claimOwnership(P)));   // 帮会检查时：写之前
            return RollbackHarness.ok();
        });
        h.guildAnswers.add(r -> {
            seen.add(h.db.tx().execute(s -> login.claimOwnership(P)));   // 写后复查时：写之后、释放之前
            return RollbackHarness.ok();
        });

        OpsJobRow job = h.await(h.submit(single(P, null, false, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(seen).containsExactly(new ClaimResult.Held(4), new ClaimResult.Held(4));
        // login 没夺到：epoch 没被它推到 5，回档写下的内容是 epoch 4 写的
        assertThat(h.db.players.find(P).getStateSavedEpoch()).isEqualTo(4L);
        assertThat(h.jobs.players(job.getJobId(), 0, 10).get(0).getClaimedEpoch()).isEqualTo(4);
        assertReleased(P);
        // 作业收尾释放之后，login 才拿到下一个 epoch
        ClaimResult afterRelease = h.db.tx().execute(s -> login.claimOwnership(P));
        assertThat(afterRelease).isEqualTo(new ClaimResult.Claimed(5));
    }

    // ------------------------------------------------------------------ 战斗结算账本随资产组整段回退（审计 OPS-9）

    private static BattleLedgerState battleLedger(long... battleIds) {
        BattleLedgerState.Builder b = BattleLedgerState.newBuilder();
        for (long id : battleIds) {
            b.addApplied(BattleLedgerEntry.newBuilder().setBattleId(id).setAppliedAtMs(1_700_000_000_000L + id));
        }
        return b.build();
    }

    @Test
    void 部分回档assets_现档有战斗结算账本而快照没有_落库后账本清空_资产回到快照_没选的段不动() throws Exception {
        h = new RollbackHarness(Map.of());
        // 快照之后打了两局、已应用未销账（账本里有 71、72）；回档后这两局由待结算记录重投，账本必须跟着资产一起退回去
        PlayerState current = CURRENT.toBuilder().setBattleLedger(battleLedger(71, 72)).build();
        player(P, current.toByteArray(), SNAPSHOT.toByteArray());

        OpsJobRow job = h.await(h.submit(single(P, List.of("assets"), false, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        PlayerState now = persisted(P);
        assertThat(now.hasBattleLedger()).as("战斗结算账本（字段 9）随资产组清掉").isFalse();
        assertThat(now.getCurrency().getBalancesList()).containsExactly(1000L, 0L);
        assertThat(now.getBag()).isEqualTo(SNAPSHOT.getBag());
        assertThat(now.getMission()).as("mission 没选：保留现档").isEqualTo(CURRENT.getMission());
        // 被覆盖之前的账本留在安全快照里
        long pre = h.jobs.players(job.getJobId(), 0, 10).get(0).getPreSnapshotId();
        assertThat(PlayerState.parseFrom(h.db.snapshots.findById(pre).getPlayerState()).getBattleLedger())
                .isEqualTo(battleLedger(71, 72));
    }

    @Test
    void 部分回档assets_快照里有战斗结算账本而现档已销账清空_落库后账本随资产带回() throws Exception {
        h = new RollbackHarness(Map.of());
        // 快照那一刻第 71 局已应用未销账（奖励已在快照的资产里）；之后销了账。资产回到快照而账本不带回，这一局会被再发一次
        PlayerState snapshot = SNAPSHOT.toBuilder().setBattleLedger(battleLedger(71)).build();
        player(P, CURRENT.toByteArray(), snapshot.toByteArray());

        OpsJobRow job = h.await(h.submit(single(P, List.of("assets"), false, false), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        PlayerState now = persisted(P);
        assertThat(now.getBattleLedger()).isEqualTo(battleLedger(71));
        assertThat(now.getCurrency().getBalancesList()).containsExactly(1000L, 0L);
        assertThat(now.getMission()).isEqualTo(CURRENT.getMission());
    }

    @Test
    void 部分回档不动资产组_战斗结算账本留现档_两边都有时assets整段取快照的不合并() throws Exception {
        h = new RollbackHarness(Map.of());
        PlayerState current = CURRENT.toBuilder().setBattleLedger(battleLedger(72, 73)).build();
        PlayerState snapshot = SNAPSHOT.toBuilder().setBattleLedger(battleLedger(71)).build();
        player(P, current.toByteArray(), snapshot.toByteArray());

        OpsJobRow levelOnly = h.await(h.submit(single(P, List.of("level"), false, false), "k1"));
        assertThat(levelOnly.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(persisted(P).getBattleLedger()).isEqualTo(battleLedger(72, 73));
        assertThat(persisted(P).getCurrency()).isEqualTo(CURRENT.getCurrency());

        OpsJobRow assets = h.await(h.submit(single(P, List.of("assets"), false, false), "k2"));
        assertThat(assets.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(persisted(P).getBattleLedger()).isEqualTo(battleLedger(71));
    }
}
