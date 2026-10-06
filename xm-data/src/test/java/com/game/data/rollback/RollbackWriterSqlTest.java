package com.game.data.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.data.ops.OpsIds;
import com.game.data.ops.OpsJobStore;
import com.game.data.ops.fence.StrictClock;
import com.game.data.ops.pb.OpsJobPlayerRow;
import com.game.data.snapshot.PlayerSnapshotRow;
import com.game.data.snapshot.SnapshotCauses;
import com.game.data.store.PersistedPlayer;
import com.game.data.store.PlayerSnapshotEntry;
import com.game.data.store.PlayerSnapshotMapper;
import com.game.data.store.TransactionLogMapper;
import com.game.data.testing.DataSqlFixture;
import com.game.data.testing.TestIds;
import com.game.data.testing.TestIds.FakeLease;
import com.game.data.txlog.TransactionLogRow;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerState;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 一个玩家的回档写事务（T-A2 / T-R1，data-ops-spec §4.8、不变量 I2）：「安全快照 + 覆盖写 + 流水 + 明细」同生共死。
 * 直接驱动生产的 {@link RollbackWriter}，库是 H2 或 {@code -Dxm.it.mysql} 的真 MySQL（生产连接串，{@code useAffectedRows=true}）。
 *
 * <p>失败注入只换注入点（Mockito {@code delegatesTo} 包真实对象，或继承 {@link PlayerStore}），SQL 与事务边界仍是生产代码。每个失败用例
 * 都比对<b>整库</b>前后的内容（{@link #world()}：player / player_state / player_snapshot / transaction_log / ops_job_player 全部行），
 * 并在注入点上记下「事务里此刻已经写了什么」——证明失败确实发生在前几步写完之后，回滚不是空话。
 */
class RollbackWriterSqlTest {

    private static final long P = 1001;
    private static final long SNAP = 5001;
    private static final long JOB = 9001;
    /** 运维夺到并持有的 epoch E'（现档是上一个写者 epoch 3 写的）。 */
    private static final long EPOCH = 4;
    private static final Set<RollbackSection> FULL = EnumSet.noneOf(RollbackSection.class);
    private static final byte[] CORRUPT = {(byte) 0xFF, 0x01};

    private final ObjectMapper json = new ObjectMapper();
    private DataSqlFixture db;
    private FakeLease lease;
    private OpsIds ids;
    private PlayerStore store;
    private OpsJobStore jobs;
    private long stateUpdatedAt;

    @BeforeEach
    void setUp() throws Exception {
        db = DataSqlFixture.create();
        lease = new FakeLease(17);
        ids = TestIds.ready(lease);
        store = db.playerStore(System::currentTimeMillis);
        jobs = db.jobs();
        long now = System.currentTimeMillis();
        stateUpdatedAt = now - 5000;
        // 玩家 P：归属已被运维夺到（epoch 4、未释放、租约有效）；现档是 epoch 3 的写者留下的
        db.insertPlayer(P, 1, 9, 1001, EPOCH, false, now + 30_000, now - 86_400_000L, now - 1000);
        db.putState(P, RollbackJobSqlTest.currentState().toByteArray(), 3, stateUpdatedAt);
        insertSnapshot(SNAP, P, RollbackJobSqlTest.snapshotState().toByteArray());
        planned(P);
    }

    @AfterEach
    void tearDown() throws Exception {
        ids.close();
        db.close();
    }

    // ------------------------------------------------------------------ 素材与观察

    private void insertSnapshot(long snapshotId, long playerId, byte[] state) {
        long t = System.currentTimeMillis() - 60_000;
        db.snapshots.insertDirect(new PlayerSnapshotRow(snapshotId, playerId, t, SnapshotCauses.LOGOUT, 1, 3, 5, 2002, 10.5, 0,
                -2.5, state), t + 50, "", "");
    }

    /** 计划阶段插的明细行（PLANNED，钉住快照 {@link #SNAP}）。 */
    private void planned(long playerId) {
        jobs.insertPlayer(OpsJobPlayerRow.newBuilder().setJobId(JOB).setPlayerId(playerId).setOutcome(OpsJobStore.PLANNED)
                .setPlannedSnapshotId(SNAP).setPlannedSnapshotMs(1).build());
    }

    private RollbackWriter writer(PlayerStore store, PlayerSnapshotMapper snapshots, TransactionLogMapper txlog,
                                  OpsJobStore jobs, OpsIds ids) {
        return new RollbackWriter(store, db.playerMapper, db.players, snapshots, txlog, jobs, ids, db.tx(), json,
                Clock.systemUTC());
    }

    private RollbackWriter writer() {
        return writer(store, db.snapshots, db.txlog, jobs, ids);
    }

    /** 包一层：没被打桩的方法原样转给真实对象。 */
    private static <T> T delegating(Class<T> type, T real) {
        return mock(type, delegatesTo(real));
    }

    private String dump(String sql) {
        StringBuilder out = new StringBuilder(sql).append('\n');
        for (Map<String, Object> row : db.jdbc().queryForList(sql)) {
            row.forEach((column, value) -> out.append(column.toLowerCase(Locale.ROOT)).append('=')
                    .append(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : String.valueOf(value))
                    .append("; "));
            out.append('\n');
        }
        return out.toString();
    }

    /** 回档写事务碰得到的五张表的全部行（列值逐个写出；字节按十六进制）。失败用例前后必须逐字相同。 */
    private String world() {
        return dump("SELECT * FROM player ORDER BY player_id")
                + dump("SELECT * FROM player_state ORDER BY player_id")
                + dump("SELECT * FROM player_snapshot ORDER BY snapshot_id")
                + dump("SELECT * FROM transaction_log ORDER BY tx_id")
                + dump("SELECT * FROM ops_job_player ORDER BY job_id, player_id");
    }

    private int count(String sql, Object... args) {
        Integer n = db.jdbc().queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private int preSnapshots() {
        return count("SELECT COUNT(*) FROM player_snapshot WHERE cause = ?", SnapshotCauses.PRE_ROLLBACK);
    }

    private int restoreRows() {
        return count("SELECT COUNT(*) FROM transaction_log WHERE reason = 16");
    }

    private long savedEpoch() {
        Long epoch = db.jdbc().queryForObject("SELECT saved_epoch FROM player_state WHERE player_id = ?", Long.class, P);
        return epoch == null ? -1 : epoch;
    }

    private String detailOutcome() {
        return db.jdbc().queryForObject("SELECT outcome FROM ops_job_player WHERE job_id = ? AND player_id = ?", String.class,
                JOB, P);
    }

    private OpsJobPlayerRow detail() {
        return jobs.players(JOB, 0, 10).stream().filter(r -> r.getPlayerId() == P).findFirst().orElseThrow();
    }

    /** 事务里此刻的进度（在注入点上、经事务绑定的连接读）：安全快照行数 / 现档是哪个 epoch 写的 / 回档流水行数 / 明细结局。 */
    private String progressInTx() {
        return "pre=" + preSnapshots() + " savedEpoch=" + savedEpoch() + " txlog=" + restoreRows() + " detail=" + detailOutcome();
    }

    private static final String NOTHING_YET = "pre=0 savedEpoch=3 txlog=0 detail=PLANNED";

    // ------------------------------------------------------------------ 对照：成功时四件事都在

    @Test
    void 对照_成功时安全快照_覆盖写_流水_明细四件事都在_并且互相对得上() throws Exception {
        assertThat(progressInTx()).isEqualTo(NOTHING_YET);

        String outcome = writer().write(JOB, "ops", P, SNAP, EPOCH, FULL);

        assertThat(outcome).isEqualTo(RollbackWriter.RESTORED);
        assertThat(progressInTx()).isEqualTo("pre=1 savedEpoch=4 txlog=4 detail=RESTORED");
        // 覆盖写：整份换成快照，封禁名单保留现档（D6）
        PersistedPlayer now = db.players.find(P);
        assertThat(PlayerState.parseFrom(now.stateBytes())).isEqualTo(RollbackJobSqlTest.snapshotState().toBuilder()
                .setCurrency(RollbackJobSqlTest.snapshotState().getCurrency().toBuilder().addBlockedTypes(0)).build());
        assertThat(now.getLevel()).isEqualTo(5);
        assertThat(now.getSceneConfigId()).isEqualTo(2002);
        // 安全快照：被覆盖之前的现档原字节，内容时刻 = 现档的 updated_at，写入它的 epoch = 3
        OpsJobPlayerRow detail = detail();
        PlayerSnapshotEntry pre = db.snapshots.findById(detail.getPreSnapshotId());
        assertThat(pre.getCause()).isEqualTo(SnapshotCauses.PRE_ROLLBACK);
        assertThat(pre.getPlayerState()).isEqualTo(RollbackJobSqlTest.currentState().toByteArray());
        assertThat(pre.getTimeMs()).isEqualTo(stateUpdatedAt);
        assertThat(pre.getOwnerEpoch()).isEqualTo(3);
        assertThat(pre.getNote()).isEqualTo("job:" + JOB);
        // 流水：关联号 = 作业号，extra 里带快照号与安全快照号
        assertThat(count("SELECT COUNT(*) FROM transaction_log WHERE reason = 16 AND correlation_id = ? AND from_player = ?",
                JOB, P)).isEqualTo(4);
        String extra = db.jdbc().queryForObject("SELECT MIN(extra) FROM transaction_log WHERE reason = 16", String.class);
        assertThat(json.readTree(extra).get("pre").asText()).isEqualTo(Long.toUnsignedString(detail.getPreSnapshotId()));
        assertThat(json.readTree(extra).get("snapshot").asText()).isEqualTo(Long.toString(SNAP));
        // 明细：夺到的 epoch、安全快照号、写入时刻、流水行数
        assertThat(detail.getClaimedEpoch()).isEqualTo(EPOCH);
        assertThat(detail.getWrittenMs()).isPositive();
        JsonNode d = json.readTree(detail.getDetailJson());
        assertThat(d.get("txlogRows").asInt()).isEqualTo(4);
        assertThat(d.get("mode").asText()).isEqualTo("FULL");
        // 归属原样：写事务不改 epoch、不释放
        assertThat(db.tx().execute(s -> db.playerMapper.selectOwnerForUpdate(P)).ownerEpoch()).isEqualTo(EPOCH);
        assertThat(db.tx().execute(s -> db.playerMapper.selectOwnerForUpdate(P)).released()).isFalse();
    }

    // ------------------------------------------------------------------ 第 1 步：加锁读归属

    @Test
    void 写之前归属已被别人夺走或已释放_fence_lost_零残留() {
        // epoch 被夺（租约过期后新进场把它加到 5）
        db.jdbc().update("UPDATE player SET owner_epoch = 5 WHERE player_id = ?", P);
        String before = world();
        assertThat(writer().write(JOB, "ops", P, SNAP, EPOCH, FULL)).isEqualTo(RollbackWriter.FENCE_LOST);
        assertThat(world()).isEqualTo(before);

        // epoch 还是我们的，但已释放（最终写回 / 收尾已经做过）
        db.jdbc().update("UPDATE player SET owner_epoch = ?, owner_released = 1 WHERE player_id = ?", EPOCH, P);
        before = world();
        assertThat(writer().write(JOB, "ops", P, SNAP, EPOCH, FULL)).isEqualTo(RollbackWriter.FENCE_LOST);
        assertThat(world()).isEqualTo(before);

        // 对照：归属恢复成 (E', 未释放) 之后同一个调用就写成了——上面两次被拒只因为围栏
        db.jdbc().update("UPDATE player SET owner_released = 0 WHERE player_id = ?", P);
        assertThat(writer().write(JOB, "ops", P, SNAP, EPOCH, FULL)).isEqualTo(RollbackWriter.RESTORED);
    }

    @Test
    void 玩家行不存在_fence_lost_什么也不写() {
        String before = world();
        assertThat(writer().write(JOB, "ops", 4242, SNAP, EPOCH, FULL)).isEqualTo(RollbackWriter.FENCE_LOST);
        assertThat(world()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 第 2 步：钉住的快照

    @Test
    void 钉住的快照在执行前被删_snapshot_gone_零残留_快照属于别人同样拒绝() {
        // 属于别的玩家的快照（号对得上、人对不上）：不能拿来回档
        insertSnapshot(5002, 2002, RollbackJobSqlTest.snapshotState().toByteArray());
        String before = world();
        assertThat(writer().write(JOB, "ops", P, 5002, EPOCH, FULL)).isEqualTo(RollbackWriter.SNAPSHOT_GONE);
        assertThat(world()).isEqualTo(before);

        // 保留期清理把钉住的那份删了
        assertThat(db.jdbc().update("DELETE FROM player_snapshot WHERE snapshot_id = ?", SNAP)).isEqualTo(1);
        before = world();
        assertThat(writer().write(JOB, "ops", P, SNAP, EPOCH, FULL)).isEqualTo(RollbackWriter.SNAPSHOT_GONE);
        assertThat(world()).isEqualTo(before);
        assertThat(detailOutcome()).isEqualTo(OpsJobStore.PLANNED);
    }

    @Test
    void 快照损坏_或部分回档而现档损坏_state_invalid_未知字段不同_unknown_sections_都不写() {
        insertSnapshot(5003, P, CORRUPT);
        String before = world();
        assertThat(writer().write(JOB, "ops", P, 5003, EPOCH, FULL)).isEqualTo(RollbackWriter.STATE_INVALID);
        assertThat(world()).isEqualTo(before);

        // SECTIONS 选了 assets，两边顶层未知字段不同（较新的 scene 写下的新段）
        db.putState(P, RollbackJobSqlTest.withUnknown(RollbackJobSqlTest.currentState(), 99, "cur").toByteArray(), 3,
                stateUpdatedAt);
        before = world();
        assertThat(writer().write(JOB, "ops", P, SNAP, EPOCH, EnumSet.of(RollbackSection.ASSETS)))
                .isEqualTo(RollbackWriter.UNKNOWN_SECTIONS);
        assertThat(world()).isEqualTo(before);

        // SECTIONS 要以现档为底：现档损坏就不写
        db.putState(P, CORRUPT, 3, stateUpdatedAt);
        before = world();
        assertThat(writer().write(JOB, "ops", P, SNAP, EPOCH, EnumSet.of(RollbackSection.LEVEL)))
                .isEqualTo(RollbackWriter.STATE_INVALID);
        assertThat(world()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 号源

    @Test
    void 号源失效_取安全快照号时就停_id_unavailable_零残留() {
        lease.valid.set(false);
        String before = world();

        assertThat(writer().write(JOB, "ops", P, SNAP, EPOCH, FULL)).isEqualTo(RollbackWriter.ID_UNAVAILABLE);

        assertThat(world()).isEqualTo(before);
        // 号源恢复后同一个调用写成：上面被拒只因为发不出号
        lease.valid.set(true);
        assertThat(writer().write(JOB, "ops", P, SNAP, EPOCH, FULL)).isEqualTo(RollbackWriter.RESTORED);
    }

    @Test
    void 号源在取流水号的中途失效_id_unavailable_零残留_不带着半套号去写() {
        // 第 1 个号 = 安全快照号、第 2 个 = 金币流水号都发出来了，第 3 个（钻石流水号）发不出
        OpsIds flaky = delegating(OpsIds.class, ids);
        AtomicInteger asked = new AtomicInteger();
        doAnswer(inv -> asked.incrementAndGet() >= 3 ? OptionalLong.empty() : ids.tryNext()).when(flaky).tryNext();
        String before = world();

        assertThat(writer(store, db.snapshots, db.txlog, jobs, flaky).write(JOB, "ops", P, SNAP, EPOCH, FULL))
                .isEqualTo(RollbackWriter.ID_UNAVAILABLE);

        assertThat(asked).hasValue(3);
        assertThat(world()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 第 3 步：安全快照

    @Test
    void 安全快照插入失败_号撞了主键_抛异常_整个事务回滚_零残留() {
        // 号源出了问题、发出一个已经被用过的快照号：直写是普通 INSERT，撞主键必须报错，不能按幂等吞掉
        OpsIds colliding = delegating(OpsIds.class, ids);
        doReturn(OptionalLong.of(SNAP)).when(colliding).tryNext();
        String before = world();

        assertThatThrownBy(() -> writer(store, db.snapshots, db.txlog, jobs, colliding).write(JOB, "ops", P, SNAP, EPOCH,
                FULL)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(world()).isEqualTo(before);
    }

    @Test
    void 安全快照插入没有恰好影响1行_抛异常_已插的那一行也回滚() {
        PlayerSnapshotMapper snapshots = delegating(PlayerSnapshotMapper.class, db.snapshots);
        AtomicReference<String> inTx = new AtomicReference<>();
        doAnswer(inv -> {
            int inserted = db.snapshots.insertDirect(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                    inv.getArgument(3));
            inTx.set(inserted + " " + progressInTx());
            return 0; // 驱动 / 代理报回的行数不对
        }).when(snapshots).insertDirect(org.mockito.ArgumentMatchers.any(), anyLong(), anyString(), anyString());
        String before = world();

        assertThatThrownBy(() -> writer(store, snapshots, db.txlog, jobs, ids).write(JOB, "ops", P, SNAP, EPOCH, FULL))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("安全快照");

        assertThat(inTx).hasValue("1 pre=1 savedEpoch=3 txlog=0 detail=PLANNED");
        assertThat(world()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 第 4 步：带围栏覆盖写

    @Test
    void 带围栏覆盖写影响0行_fence_lost_已插的安全快照一起回滚() {
        // 事务里围栏没过：用真实的 SQL 带一个不是我们的 epoch 去写，UPDATE 数出 0 行
        AtomicReference<String> inTx = new AtomicReference<>();
        PlayerStore fenced = new PlayerStore(db.playerMapper, new StrictClock(System::currentTimeMillis),
                db.transactionManager) {
            @Override
            public boolean saveStateHeld(PlayerRow row, PlayerState state) {
                inTx.set(progressInTx());
                row.setOwnerEpoch(row.getOwnerEpoch() + 1);
                return super.saveStateHeld(row, state);
            }
        };
        String before = world();

        assertThat(writer(fenced, db.snapshots, db.txlog, jobs, ids).write(JOB, "ops", P, SNAP, EPOCH, FULL))
                .isEqualTo(RollbackWriter.FENCE_LOST);

        // 覆盖写被调到时安全快照已经插进去了（同一事务里看得见）；回滚之后它不在
        assertThat(inTx).hasValue("pre=1 savedEpoch=3 txlog=0 detail=PLANNED");
        assertThat(world()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 第 5 步：回档流水

    @Test
    void 回档流水写不进_抛异常_安全快照与覆盖写一起回滚_之后重试能写成() {
        TransactionLogMapper txlog = delegating(TransactionLogMapper.class, db.txlog);
        AtomicReference<String> inTx = new AtomicReference<>();
        doAnswer(inv -> {
            inTx.set(progressInTx());
            throw new DataAccessResourceFailureException("注入：流水表写不进");
        }).when(txlog).insertDirectAll(anyList(), anyLong());
        String before = world();

        assertThatThrownBy(() -> writer(store, db.snapshots, txlog, jobs, ids).write(JOB, "ops", P, SNAP, EPOCH, FULL))
                .isInstanceOf(DataAccessResourceFailureException.class);

        // 注入点上：安全快照已插、现档已被 epoch 4 覆盖；回滚后两样都退回去
        assertThat(inTx).hasValue("pre=1 savedEpoch=4 txlog=0 detail=PLANNED");
        assertThat(world()).isEqualTo(before);
        // 没有残留挡路：换回正常的 Mapper，同一个明细行照样 PLANNED → RESTORED
        assertThat(writer().write(JOB, "ops", P, SNAP, EPOCH, FULL)).isEqualTo(RollbackWriter.RESTORED);
        assertThat(progressInTx()).isEqualTo("pre=1 savedEpoch=4 txlog=4 detail=RESTORED");
    }

    @Test
    void 回档流水插入行数不符_抛异常_已插的流水行也回滚() {
        TransactionLogMapper txlog = delegating(TransactionLogMapper.class, db.txlog);
        AtomicReference<String> inTx = new AtomicReference<>();
        doAnswer(inv -> {
            List<TransactionLogRow> rows = inv.getArgument(0);
            int inserted = db.txlog.insertDirectAll(rows, inv.getArgument(1));
            inTx.set(inserted + " " + progressInTx());
            return inserted - 1;
        }).when(txlog).insertDirectAll(anyList(), anyLong());
        String before = world();

        assertThatThrownBy(() -> writer(store, db.snapshots, txlog, jobs, ids).write(JOB, "ops", P, SNAP, EPOCH, FULL))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("流水");

        assertThat(inTx).hasValue("4 pre=1 savedEpoch=4 txlog=4 detail=PLANNED");
        assertThat(world()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 第 6 步：明细

    @Test
    void 明细已不是PLANNED_条件更新0行_抛异常_前面三样全部回滚() {
        // 这个玩家已经被别的执行定过结局（不该再被写一遍）：明细的条件更新改不到行
        assertThat(jobs.finishPlayer(JOB, P, "failed", 0, 0, "{}", 0)).isTrue();
        String before = world();

        assertThatThrownBy(() -> writer().write(JOB, "ops", P, SNAP, EPOCH, FULL))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("明细");

        assertThat(world()).isEqualTo(before);
        assertThat(detailOutcome()).isEqualTo("failed");
    }

    @Test
    void 明细行根本不存在_同样整体回滚() {
        db.jdbc().update("DELETE FROM ops_job_player WHERE job_id = ? AND player_id = ?", JOB, P);
        String before = world();

        assertThatThrownBy(() -> writer().write(JOB, "ops", P, SNAP, EPOCH, FULL))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("明细");

        assertThat(world()).isEqualTo(before);
    }

    @Test
    void 明细改成RESTORED之后事务才失败_pbmysql的这笔写也跟着回滚_它与MyBatis在同一条事务连接上() {
        OpsJobStore faulty = delegating(OpsJobStore.class, jobs);
        AtomicReference<String> inTx = new AtomicReference<>();
        doAnswer(inv -> {
            boolean updated = jobs.finishPlayer(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                    inv.getArgument(3), inv.getArgument(4), inv.getArgument(5), inv.getArgument(6));
            inTx.set(updated + " " + progressInTx());
            throw new DataAccessResourceFailureException("注入：明细改完之后连接断了");
        }).when(faulty).finishPlayer(anyLong(), anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyLong());
        String before = world();

        assertThatThrownBy(() -> writer(store, db.snapshots, db.txlog, faulty, ids).write(JOB, "ops", P, SNAP, EPOCH, FULL))
                .isInstanceOf(DataAccessResourceFailureException.class);

        // 注入点上四样都写了（明细已是 RESTORED）；pbmysql 要是自取连接自动提交，回滚之后明细会留在 RESTORED
        assertThat(inTx).hasValue("true pre=1 savedEpoch=4 txlog=4 detail=RESTORED");
        assertThat(world()).isEqualTo(before);
        assertThat(detailOutcome()).isEqualTo(OpsJobStore.PLANNED);
    }

    // ------------------------------------------------------------------ MyBatis 与 pbmysql 同一条事务连接

    private int detailRowsSeenByAnotherConnection(long playerId) throws Exception {
        try (Connection other = db.dataSource.getConnection();
             PreparedStatement ps = other.prepareStatement(
                     "SELECT COUNT(*) FROM ops_job_player WHERE job_id = ? AND player_id = ?")) {
            ps.setLong(1, JOB);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int snapshotRowsSeenByAnotherConnection(long snapshotId) throws Exception {
        try (Connection other = db.dataSource.getConnection();
             PreparedStatement ps = other.prepareStatement("SELECT COUNT(*) FROM player_snapshot WHERE snapshot_id = ?")) {
            ps.setLong(1, snapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void MyBatis与pbmysql同一条事务连接_互相看得见对方没提交的写_别的连接看不见_一边失败两边一起回滚_提交时两边都在() {
        OpsJobPlayerRow detail = OpsJobPlayerRow.newBuilder().setJobId(JOB).setPlayerId(2002).setOutcome(OpsJobStore.PLANNED)
                .build();
        PlayerSnapshotRow snapshot = new PlayerSnapshotRow(7777, P, 1, SnapshotCauses.GM_MANUAL, 1, 3, 5, 2002, 0, 0, 0,
                new byte[] {1});

        // pbmysql 先写、MyBatis 后写，然后抛异常
        assertThatThrownBy(() -> db.tx().executeWithoutResult(status -> {
            jobs.insertPlayer(detail);
            db.snapshots.insertDirect(snapshot, 2, "ops", "同连接");
            // 同一事务里：pbmysql 读得到自己刚写的、MyBatis 读得到自己刚写的；另开一条连接两样都看不见（都还没提交）
            assertThat(jobs.players(JOB, 0, 10)).extracting(OpsJobPlayerRow::getPlayerId).containsExactly(P, 2002L);
            assertThat(db.snapshots.findById(7777)).isNotNull();
            try {
                assertThat(detailRowsSeenByAnotherConnection(2002)).as("pbmysql 的写没有走自动提交的另一条连接").isZero();
                assertThat(snapshotRowsSeenByAnotherConnection(7777)).isZero();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            throw new IllegalStateException("注入：事务末尾失败");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("注入");
        assertThat(jobs.players(JOB, 0, 10)).extracting(OpsJobPlayerRow::getPlayerId).containsExactly(P);
        assertThat(db.snapshots.findById(7777)).isNull();

        // MyBatis 先写、pbmysql 后写，然后标记回滚
        db.tx().executeWithoutResult(status -> {
            db.snapshots.insertDirect(snapshot, 2, "ops", "同连接");
            jobs.insertPlayer(detail);
            status.setRollbackOnly();
        });
        assertThat(jobs.players(JOB, 0, 10)).extracting(OpsJobPlayerRow::getPlayerId).containsExactly(P);
        assertThat(db.snapshots.findById(7777)).isNull();

        // 对照：不失败就两边一起提交
        db.tx().executeWithoutResult(status -> {
            db.snapshots.insertDirect(snapshot, 2, "ops", "同连接");
            jobs.insertPlayer(detail);
        });
        assertThat(jobs.players(JOB, 0, 10)).extracting(OpsJobPlayerRow::getPlayerId).containsExactly(P, 2002L);
        assertThat(db.snapshots.findById(7777).getNote()).isEqualTo("同连接");
    }

    // ------------------------------------------------------------------ 允许写的边界情形（仍然是四件事一起）

    @Test
    void 整份回档而现档损坏_允许_安全快照存下损坏的原字节_不写回档流水_明细注明() throws Exception {
        db.putState(P, CORRUPT, 3, stateUpdatedAt);
        PlayerState snapshot = RollbackJobSqlTest.snapshotState().toBuilder()
                .setCurrency(CurrencyState.newBuilder().addBalances(1000).addBlockedTypes(1)).build();
        insertSnapshot(5004, P, snapshot.toByteArray());

        assertThat(writer().write(JOB, "ops", P, 5004, EPOCH, FULL)).isEqualTo(RollbackWriter.RESTORED);

        // 现档解析不了：封禁名单无从保留，取快照值；前后余额无从得知，不写回档流水
        assertThat(PlayerState.parseFrom(db.players.find(P).stateBytes())).isEqualTo(snapshot);
        assertThat(progressInTx()).isEqualTo("pre=1 savedEpoch=4 txlog=0 detail=RESTORED");
        OpsJobPlayerRow detail = detail();
        assertThat(db.snapshots.findById(detail.getPreSnapshotId()).getPlayerState()).isEqualTo(CORRUPT);
        JsonNode d = json.readTree(detail.getDetailJson());
        assertThat(d.get("currentStateInvalid").asBoolean()).isTrue();
        assertThat(d.get("txlogRows").asInt()).isZero();
        assertThat(d.has("txlogSkipped")).isTrue();
        assertThat(d.get("blockedTypesFromSnapshot").get(0).asInt()).isEqualTo(1);
    }

    @Test
    void 部分回档不动资产组_没有回档流水_安全快照与明细照样同事务() throws Exception {
        assertThat(writer().write(JOB, "ops", P, SNAP, EPOCH, EnumSet.of(RollbackSection.LEVEL, RollbackSection.FACING)))
                .isEqualTo(RollbackWriter.RESTORED);

        assertThat(progressInTx()).isEqualTo("pre=1 savedEpoch=4 txlog=0 detail=RESTORED");
        PersistedPlayer now = db.players.find(P);
        assertThat(now.getLevel()).isEqualTo(5);
        assertThat(now.getSceneConfigId()).as("position 没选：保留现档").isEqualTo(1001);
        PlayerState state = PlayerState.parseFrom(now.stateBytes());
        assertThat(state.getCurrency()).isEqualTo(RollbackJobSqlTest.currentState().getCurrency());
        assertThat(state.getFacing()).isEqualTo(RollbackJobSqlTest.snapshotState().getFacing());
        assertThat(json.readTree(detail().getDetailJson()).get("sections").toString()).isEqualTo("[\"level\",\"facing\"]");
    }
}
