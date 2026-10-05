package com.game.data.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.game.data.admin.AuditQueryController;
import com.game.data.query.TransactionLogQueryService;
import com.game.data.snapshot.PlayerSnapshotRow;
import com.game.data.snapshot.PlayerSnapshotSink;
import com.game.data.snapshot.SnapshotCauses;
import com.game.data.testing.DataSqlFixture;
import com.game.data.txlog.TransactionLogRow;
import com.game.data.txlog.TransactionLogSink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * 生产建表脚本 + 生产 Mapper 的真实 SQL（缺省 H2 的 MySQL 兼容模式；{@code -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306}
 * 时连真 MySQL，口令取 XM_MYSQL_PASSWORD）。受影响行数的「重复计 0」语义只在真 MySQL（useAffectedRows=true）上断言。
 */
class AuditStoreSqlTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC);
    private static final boolean MYSQL = DataSqlFixture.mysql();

    private DataSqlFixture db;
    private TransactionLogMapper mapper;
    private TransactionLogSink sink;
    private PlayerSnapshotMapper snapshotMapper;
    private PlayerSnapshotSink snapshotSink;
    private AuditQueryController query;

    @BeforeEach
    void setUp() throws Exception {
        db = DataSqlFixture.create();
        mapper = db.txlog;
        sink = new TransactionLogSink(mapper, db.tx(), 2, CLOCK);
        snapshotMapper = db.snapshots;
        snapshotSink = new PlayerSnapshotSink(snapshotMapper, db.tx(), 2, CLOCK);
        query = new AuditQueryController(new TransactionLogQueryService(mapper, Duration.ofDays(7)), snapshotMapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private static TransactionLogRow row(long txId, long timeMs, long from, long to, long delta) {
        return new TransactionLogRow(txId, timeMs, 9, 1, from, to, 1, delta, 100, 100 + delta, 0, 0, 0, 0, "", 1);
    }

    private List<Map<String, Object>> byPlayer(String player, long since, int limit) {
        return query.transactionLog(player, null, null, null, null, null, since, Long.MAX_VALUE, limit, null).getBody();
    }

    private TransactionLogEntry single(TransactionLogQuery q) {
        return mapper.query(q).get(0);
    }

    @Test
    void 分块多行插入_重放幂等_按玩家双向查询按时间升序() {
        List<TransactionLogRow> rows = List.of(row(3, 300, 0, 1001, 5), row(1, 100, 1001, 0, -7), row(2, 200, 0, 2002, 9));

        int inserted = sink.insert(rows);
        int replayed = sink.insert(rows);

        assertThat(inserted).isEqualTo(3);
        if (MYSQL) {
            assertThat(replayed).as("useAffectedRows=true：重复行计 0").isZero();
        }
        List<Map<String, Object>> found = byPlayer("1001", 0, 100);
        assertThat(found).extracting(m -> m.get("txId")).containsExactly("1", "3");
        assertThat(found.get(0)).containsEntry("currencyDelta", -7L).containsEntry("fromPlayer", "1001")
                .containsEntry("ingestedAt", 1_800_000_000_000L);
        assertThat(byPlayer("1001", 150, 100)).extracting(m -> m.get("txId")).containsExactly("3");
        assertThat(byPlayer("1001", 0, 1)).hasSize(1);
    }

    @Test
    void 无符号列越过2的63次方_原样落库原样读回() {
        // H2 的 BIGINT 没有无符号，≥ 2^63 的值只在真 MySQL 上验
        assumeTrue(MYSQL, "需要 -Dxm.it.mysql");
        long hugeBalance = -1L;               // 2^64 − 1：补缴抵扣链的中间余额最多到 2^64 − 2
        long hugeCorrelation = Long.MIN_VALUE; // 2^63：调用方给的单号
        TransactionLogRow row = new TransactionLogRow(-2L, 100, 25, 1, 0, 1001, 0, 110, Long.MAX_VALUE - 10,
                hugeBalance, -3L, -4, -5, hugeCorrelation, "", -6);

        assertThat(sink.insert(List.of(row))).isEqualTo(1);

        TransactionLogEntry e = single(TransactionLogQuery.builder().toPlayer(1001L).build());
        assertThat(e.getTxId()).isEqualTo(-2L);
        assertThat(e.getBalanceBefore()).isEqualTo(Long.MAX_VALUE - 10);
        assertThat(e.getBalanceAfter()).isEqualTo(hugeBalance);
        assertThat(e.getItemUuid()).isEqualTo(-3L);
        assertThat(e.getItemConfigId()).isEqualTo(-4);
        assertThat(e.getItemQuantity()).isEqualTo(-5);
        assertThat(e.getCorrelationId()).isEqualTo(hugeCorrelation);
        assertThat(e.getZoneId()).isEqualTo(-6);
        Map<String, Object> view = byPlayer("1001", 0, 10).get(0);
        assertThat(view).containsEntry("balanceAfter", "18446744073709551615")
                .containsEntry("correlationId", "9223372036854775808").containsEntry("itemQuantity", 4294967291L);
        // 无符号的 uuid / 游标值同样能作查询条件（≥ 2^63 的 uuid 按无符号绑定）
        assertThat(single(TransactionLogQuery.builder().itemUuid(-3L).build()).getTxId()).isEqualTo(-2L);
        assertThat(mapper.query(TransactionLogQuery.builder().toPlayer(1001L).after(100L, -3L).build())).hasSize(1);
        assertThat(mapper.query(TransactionLogQuery.builder().toPlayer(1001L).after(100L, -2L).build())).isEmpty();
    }

    @Test
    void 保留期清理分批删掉更早的行() {
        sink.insert(List.of(row(1, 100, 0, 1, 1), row(2, 200, 0, 1, 1), row(3, 300, 0, 1, 1), row(4, 400, 0, 1, 1)));

        assertThat(mapper.deleteOlderThan(350, 2)).isEqualTo(2);
        assertThat(mapper.deleteOlderThan(350, 2)).isEqualTo(1);
        assertThat(mapper.deleteOlderThan(350, 2)).isZero();
        assertThat(mapper.query(TransactionLogQuery.builder().toPlayer(1L).build()))
                .extracting(TransactionLogEntry::getTxId).containsExactly(4L);
    }

    // ------------------------------------------------------------------ 流水筛选（T-Q1 的 SQL 面）

    private static TransactionLogRow currency(long txId, long timeMs, long from, long to, int type, long delta, int reason) {
        return new TransactionLogRow(txId, timeMs, reason, 1, from, to, type, delta, 0, 0, 0, 0, 0, 0, "", 1);
    }

    private static TransactionLogRow item(long txId, long timeMs, long from, long to, long uuid, int config, int qty,
                                          int reason) {
        return new TransactionLogRow(txId, timeMs, reason, 2, from, to, 0, 0, 0, 0, uuid, config, qty, 0, "", 1);
    }

    private List<Long> ids(TransactionLogQuery q) {
        return mapper.query(q).stream().map(TransactionLogEntry::getTxId).toList();
    }

    @Test
    void 筛选组合_金币币种0可以选中_物品与uuid_原因多值_半开窗口() {
        sink.insert(List.of(
                currency(1, 100, 0, 7, 0, 50, 9),     // 金币获得
                currency(2, 110, 7, 0, 0, -20, 10),   // 金币扣减
                currency(3, 120, 0, 7, 1, 30, 9),     // 钻石
                item(4, 130, 0, 7, 900, 501, 3, 8),   // 物品入包
                item(5, 140, 7, 0, 900, 501, 3, 13),  // 同一实例销毁
                item(6, 150, 0, 8, 901, 502, 1, 9)));

        assertThat(ids(TransactionLogQuery.builder().kind(1).currencyType(0).build()))
                .as("币种 0 = 金币，是有效条件（基线 B2 只在 > 0 时生效）").containsExactly(1L, 2L);
        assertThat(ids(TransactionLogQuery.builder().kind(1).currencyType(0).acquisitionsOnly(true).build()))
                .as("回收只认获得行：扣减行不算候选").containsExactly(1L);
        assertThat(ids(TransactionLogQuery.builder().kind(2).itemConfigId(501).build())).containsExactly(4L, 5L);
        assertThat(ids(TransactionLogQuery.builder().kind(2).itemConfigId(501).acquisitionsOnly(true).build()))
                .containsExactly(4L);
        assertThat(ids(TransactionLogQuery.builder().itemUuid(900L).build())).containsExactly(4L, 5L);
        assertThat(ids(TransactionLogQuery.builder().reasons(List.of(9, 13)).window(0, 1000).build()))
                .containsExactly(1L, 3L, 5L, 6L);
        assertThat(ids(TransactionLogQuery.builder().reasons(List.of(9)).window(100, 150).build()))
                .as("半开窗口 [100, 150)").containsExactly(1L, 3L);
    }

    @Test
    void 游标续翻按时间与流水号升序_同毫秒按号_limit加1判截断() {
        sink.insert(List.of(currency(30, 100, 0, 7, 1, 1, 9), currency(10, 100, 0, 7, 1, 1, 9),
                currency(20, 100, 7, 0, 1, -1, 10), currency(5, 200, 0, 7, 1, 1, 9), currency(1, 300, 7, 0, 1, -1, 10)));
        TransactionLogQueryService service = new TransactionLogQueryService(mapper, Duration.ofDays(7));

        TransactionLogQueryService.Page first = service.query(new TransactionLogQueryService.Filter(7L, null, null, null,
                null, List.of(), 0, Long.MAX_VALUE, 2, null));
        assertThat(first.rows()).extracting(TransactionLogEntry::getTxId).containsExactly(10L, 20L);
        assertThat(first.next().format()).isEqualTo("100:20");
        TransactionLogQueryService.Page second = service.query(new TransactionLogQueryService.Filter(7L, null, null,
                null, null, List.of(), 0, Long.MAX_VALUE, 2, first.next()));
        assertThat(second.rows()).extracting(TransactionLogEntry::getTxId).containsExactly(30L, 5L);
        TransactionLogQueryService.Page third = service.query(new TransactionLogQueryService.Filter(7L, null, null,
                null, null, List.of(), 0, Long.MAX_VALUE, 2, second.next()));
        assertThat(third.rows()).extracting(TransactionLogEntry::getTxId).containsExactly(1L);
        assertThat(third.next()).as("取尽了就没有下一页").isNull();
        assertThat(service.scan(TransactionLogQuery.builder().toPlayer(7L).build(), 2))
                .as("scan 取满 maxRows + 1 行即停").extracting(TransactionLogEntry::getTxId).containsExactly(10L, 30L, 5L);
    }

    // ------------------------------------------------------------------ 快照

    private static PlayerSnapshotRow snapshot(long snapshotId, long playerId, long timeMs, int stateBytes) {
        return snapshot(snapshotId, playerId, timeMs, 2, stateBytes);
    }

    private static PlayerSnapshotRow snapshot(long snapshotId, long playerId, long timeMs, int cause, int stateBytes) {
        byte[] state = new byte[stateBytes];
        for (int i = 0; i < state.length; i++) {
            state[i] = (byte) i;
        }
        return new PlayerSnapshotRow(snapshotId, playerId, timeMs, cause, 1, 7, 12, 3, 1.5, 0, -2.25, state);
    }

    @Test
    void 快照分块插入_重放幂等_按玩家查元数据不取本体_字节数准确() {
        List<PlayerSnapshotRow> rows = List.of(snapshot(3, 1001, 300, 5), snapshot(1, 1001, 100, 0),
                snapshot(2, 2002, 200, 1), snapshot(4, 1001, 300, 700_000));

        int inserted = snapshotSink.insert(rows);
        int replayed = snapshotSink.insert(rows);

        assertThat(inserted).isEqualTo(4);
        if (MYSQL) {
            assertThat(replayed).as("useAffectedRows=true：重复行计 0").isZero();
        }
        List<Map<String, Object>> found = query.playerSnapshots("1001", 0, Long.MAX_VALUE, 100, null, "asc");
        assertThat(found).extracting(m -> m.get("snapshotId")).containsExactly("1", "3", "4");
        assertThat(found).extracting(m -> m.get("stateBytes")).containsExactly(0L, 5L, 700_000L);
        assertThat(found.get(1)).containsEntry("cause", 2).containsEntry("causeName", "LOGOUT")
                .containsEntry("ownerEpoch", "7").containsEntry("level", 12L)
                .containsEntry("sceneConfigId", 3L).containsEntry("posX", 1.5).containsEntry("posZ", -2.25)
                .containsEntry("ingestedAt", 1_800_000_000_000L).containsEntry("operator", "").containsEntry("note", "");
        assertThat(query.playerSnapshots("1001", 150, 300, 100, null, "asc")).isEmpty();
        assertThat(query.playerSnapshots("1001", 0, Long.MAX_VALUE, 2, null, "asc")).hasSize(2);
        assertThat(query.playerSnapshots("1001", 0, Long.MAX_VALUE, 100, null, "desc"))
                .extracting(m -> m.get("snapshotId")).containsExactly("4", "3", "1");
        byte[] stored = db.jdbc().queryForObject("SELECT player_state FROM player_snapshot WHERE snapshot_id = 3",
                byte[].class);
        assertThat(stored).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void 直写快照带操作人与备注_按号取本体_撞号报错不吞行() {
        PlayerSnapshotRow row = snapshot(9, 1001, 500, SnapshotCauses.GM_MANUAL, 3);
        assertThat(snapshotMapper.insertDirect(row, 777, "运维甲", "事件:补偿")).isEqualTo(1);

        PlayerSnapshotEntry e = snapshotMapper.findById(9);
        assertThat(e.getOperator()).isEqualTo("运维甲");
        assertThat(e.getNote()).isEqualTo("事件:补偿");
        assertThat(e.getIngestedAt()).isEqualTo(777);
        assertThat(e.getPlayerState()).containsExactly(0, 1, 2);
        assertThat(e.getStateBytes()).isEqualTo(3);
        assertThat(snapshotMapper.findById(10)).isNull();
        assertThatThrownBy(() -> snapshotMapper.insertDirect(row, 778, "运维乙", ""))
                .as("号是新发的：撞主键说明号源出错，必须报错，不能像 Kafka 落库那样幂等吞掉")
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(query.playerSnapshots("1001", 0, Long.MAX_VALUE, 10, List.of("GM_MANUAL"), "asc"))
                .singleElement().satisfies(m -> assertThat(m).containsEntry("operator", "运维甲")
                        .containsEntry("causeName", "GM_MANUAL"));
    }

    @Test
    void 按时刻选源_含等号_同毫秒取号大的_白名单排除安全快照() {
        snapshotSink.insert(List.of(snapshot(1, 1001, 100, SnapshotCauses.LOGIN, 1),
                snapshot(2, 1001, 200, SnapshotCauses.LOGOUT, 1),
                snapshot(5, 1001, 200, SnapshotCauses.LOGIN, 1),
                snapshot(9, 1001, 250, SnapshotCauses.PRE_ROLLBACK, 1),
                snapshot(8, 1001, 260, SnapshotCauses.PRE_GM_EDIT, 1),
                snapshot(7, 2002, 250, SnapshotCauses.LOGIN, 1)));

        assertThat(snapshotMapper.findLatestAtOrBefore(1001, 200, SnapshotCauses.POINT_IN_TIME_SOURCES).getSnapshotId())
                .as("time_ms ≤ atMs（含等号），同毫秒取号大的").isEqualTo(5);
        assertThat(snapshotMapper.findLatestAtOrBefore(1001, 199, SnapshotCauses.POINT_IN_TIME_SOURCES).getSnapshotId())
                .isEqualTo(1);
        assertThat(snapshotMapper.findLatestAtOrBefore(1001, 1000, SnapshotCauses.POINT_IN_TIME_SOURCES).getSnapshotId())
                .as("安全快照（PRE_ROLLBACK / PRE_GM_EDIT）不当时间点源").isEqualTo(5);
        assertThat(snapshotMapper.findLatestAtOrBefore(1001, 99, SnapshotCauses.POINT_IN_TIME_SOURCES)).isNull();
        assertThat(snapshotMapper.findLatestAtOrBefore(1001, 1000, List.of(SnapshotCauses.PRE_ROLLBACK)).getSnapshotId())
                .isEqualTo(9);
    }

    @Test
    void 快照保留期按原因分类清理_内容时刻与拍摄时刻都过期才删() {
        snapshotSink.insert(List.of(snapshot(1, 1, 100, SnapshotCauses.LOGIN, 1),
                snapshot(2, 1, 200, SnapshotCauses.LOGOUT, 1), snapshot(3, 1, 400, SnapshotCauses.LOGIN, 1),
                snapshot(4, 1, 100, SnapshotCauses.PRE_TRADE, 1), snapshot(5, 1, 100, 4242, 1)));
        // 直写的 GM 快照：内容时刻很早（玩家很久没上线），拍摄时刻（ingested_at）在后
        snapshotMapper.insertDirect(snapshot(6, 1, 100, SnapshotCauses.GM_MANUAL, 1), 100, "ops", "");
        snapshotMapper.insertDirect(snapshot(7, 1, 100, SnapshotCauses.PRE_ROLLBACK, 1), 2_000_000_000_000L, "ops", "");

        assertThat(snapshotMapper.deleteOlderThan(350, SnapshotCauses.ROUTINE_RETENTION, 1))
                .as("CLOCK 落库时刻远晚于 350：上下线快照按 ingested_at 还没过期，一行都不删").isZero();
        db.jdbc().update("UPDATE player_snapshot SET ingested_at = time_ms WHERE operator = ''");
        assertThat(snapshotMapper.deleteOlderThan(350, SnapshotCauses.ROUTINE_RETENTION, 1)).isEqualTo(1);
        assertThat(snapshotMapper.deleteOlderThan(350, SnapshotCauses.ROUTINE_RETENTION, 5)).isEqualTo(1);
        assertThat(snapshotMapper.deleteOlderThan(350, SnapshotCauses.GM_RETENTION, 5))
                .as("GM_MANUAL 拍于 100 删；PRE_ROLLBACK 内容早但刚拍，不删").isEqualTo(1);
        assertThat(query.playerSnapshots("1", 0, Long.MAX_VALUE, 10, null, "asc")).extracting(m -> m.get("snapshotId"))
                .as("PRE_TRADE 与不认识的原因从不清理").containsExactlyInAnyOrder("3", "4", "5", "7");
    }
}
