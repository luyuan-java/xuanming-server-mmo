package com.game.data.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.game.data.ops.OpsException;
import com.game.data.ops.OpsIds;
import com.game.data.ops.pb.OpsJobKind;
import com.game.data.ops.pb.OpsJobRow;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.snapshot.SnapshotCauses;
import com.game.data.snapshot.ZoneSnapshotService;
import com.game.data.store.PersistedPlayerMapper;
import com.game.data.store.PlayerSnapshotEntry;
import com.game.data.testing.TestIds;
import com.game.data.testing.TestIds.FakeLease;
import com.game.gateway.store.ZoneManualStatus;
import com.game.player.store.OwnerState;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerState;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

/**
 * 整区维护前快照（data-ops-spec §3.4，{@code POST /admin/zone-snapshots}）：{@link ZoneSnapshotService} 的受理校验与作业体，
 * 用生产的受理 / 作业框架 / SQL（H2 或 {@code -Dxm.it.mysql} 的真 MySQL）。放在回档的测试包里是因为它与回档共用单飞槽、
 * 拍出的快照又是回档按时刻选源的输入——这两条要与真实的回档受理一起验，装配借 {@link RollbackHarness}。
 *
 * <p>分页 / 取消 / 超时 / 号源失效靠包一层的 {@link PersistedPlayerMapper}（Mockito {@code delegatesTo}）在确定的那一次读上记账或拖住，
 * 其余调用原样落到真实 Mapper。
 */
class ZoneSnapshotSqlTest {

    private static final String OPERATOR = "ops-zs";
    private static final PlayerState STATE = RollbackJobSqlTest.currentState();

    private RollbackHarness h;
    private OpsIds extraIds;

    @AfterEach
    void tearDown() throws Exception {
        if (extraIds != null) {
            extraIds.close();
        }
        if (h != null) {
            h.close();
        }
    }

    // ------------------------------------------------------------------ 装配与素材

    private ZoneSnapshotService service(PersistedPlayerMapper players, OpsIds ids) {
        return new ZoneSnapshotService(players, h.db.snapshots, ids, h.service, h.zones, h.db.tx(), Clock.systemUTC());
    }

    private ZoneSnapshotService service() {
        return service(h.db.players, h.ids);
    }

    private PersistedPlayerMapper delegatingPlayers() {
        return mock(PersistedPlayerMapper.class, delegatesTo(h.db.players));
    }

    private static ZoneSnapshotService.Body body(List<Long> zones, Boolean allZones, String note) {
        return new ZoneSnapshotService.Body(zones, allZones, note, "停服维护前留底");
    }

    private static long jobId(Map<String, Object> accepted) {
        return Long.parseUnsignedLong((String) accepted.get("jobId"));
    }

    private long submit(ZoneSnapshotService service, List<Long> zones, String key) {
        return jobId(service.submit(body(zones, null, "维护窗口"), OPERATOR, key));
    }

    /** 一个区里批量造 {@code count} 个离线玩家（没有 player_state 行），玩家号从 {@code firstId} 起每次加 {@code step}。一个事务里批量插。 */
    private List<Long> bulkPlayers(int zone, long firstId, int count, int step) {
        long now = System.currentTimeMillis();
        List<Long> ids = new ArrayList<>(count);
        List<Object[]> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long id = firstId + (long) i * step;
            ids.add(id);
            rows.add(new Object[] {id, "acc" + id, zone, "name" + id, "name" + id, now - 86_400_000L, now - 1000});
        }
        h.db.tx().executeWithoutResult(status -> h.db.jdbc().batchUpdate(
                "INSERT INTO player (player_id, account, zone_id, name, name_key, class_id, gender, level, scene_config_id, "
                        + "pos_x, pos_y, pos_z, owner_epoch, owner_released, owner_lease_until, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 1, 1, 7, 1001, 1.5, 2.5, -3.5, 0, 1, 0, ?, ?)", rows));
        return ids;
    }

    private List<Long> snapshotPlayerIds() {
        return h.db.jdbc().queryForList("SELECT player_id FROM player_snapshot WHERE cause = ? ORDER BY player_id",
                Long.class, SnapshotCauses.PRE_MAINTENANCE);
    }

    private PlayerSnapshotEntry onlySnapshotOf(long playerId) {
        List<PlayerSnapshotEntry> list = h.db.snapshots.listByPlayer(playerId, 0, Long.MAX_VALUE, null, false, 10);
        assertThat(list).as("玩家 %d 的快照", playerId).hasSize(1);
        return h.db.snapshots.findById(list.get(0).getSnapshotId());
    }

    private OwnerState owner(long playerId) {
        return h.db.tx().execute(s -> h.db.playerMapper.selectOwnerForUpdate(playerId));
    }

    private static void assertBadRequest(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(OpsException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.code()).isEqualTo("invalid_request");
        });
    }

    // ------------------------------------------------------------------ 受理

    @Test
    void 受理校验_zones与allZones二选一_区号越界_缺幂等键或原因_备注非法_一律400_什么也不留() throws Exception {
        h = new RollbackHarness(Map.of());
        h.zone(1, ZoneManualStatus.MAINTENANCE.code());
        h.player(11, 1, STATE.toByteArray(), System.currentTimeMillis() - 1000, 2, false);
        ZoneSnapshotService service = service();

        assertBadRequest(() -> service.submit(null, OPERATOR, "k"));
        // zones 非空与 allZones=true 二选一：都给、都不给、给了空列表
        assertBadRequest(() -> service.submit(body(List.of(1L), true, null), OPERATOR, "k"));
        assertBadRequest(() -> service.submit(body(null, null, null), OPERATOR, "k"));
        assertBadRequest(() -> service.submit(body(List.of(), false, null), OPERATOR, "k"));
        // 区号必须是 1–4294967295
        assertBadRequest(() -> service.submit(body(List.of(0L), null, null), OPERATOR, "k"));
        assertBadRequest(() -> service.submit(body(List.of(-1L), null, null), OPERATOR, "k"));
        assertBadRequest(() -> service.submit(body(List.of(4_294_967_296L), null, null), OPERATOR, "k"));
        assertBadRequest(() -> service.submit(body(Arrays.asList(1L, null), null, null), OPERATOR, "k"));
        // 幂等键：必填，1–64 个可见 ASCII
        assertBadRequest(() -> service.submit(body(List.of(1L), null, null), OPERATOR, null));
        assertBadRequest(() -> service.submit(body(List.of(1L), null, null), OPERATOR, ""));
        assertBadRequest(() -> service.submit(body(List.of(1L), null, null), OPERATOR, "带 空格"));
        assertBadRequest(() -> service.submit(body(List.of(1L), null, null), OPERATOR, "k".repeat(65)));
        // 原因必填；备注至多 256 字符、不含控制字符
        assertBadRequest(() -> service.submit(new ZoneSnapshotService.Body(List.of(1L), null, null, null), OPERATOR, "k"));
        assertBadRequest(() -> service.submit(new ZoneSnapshotService.Body(List.of(1L), null, null, "  "), OPERATOR, "k"));
        assertBadRequest(() -> service.submit(body(List.of(1L), null, "x".repeat(257)), OPERATOR, "k"));
        assertBadRequest(() -> service.submit(body(List.of(1L), null, "第一行\n第二行"), OPERATOR, "k"));

        assertThat(h.db.count("ops_job")).isZero();
        assertThat(h.db.count("ops_active")).isZero();
        assertThat(h.db.count("ops_job_event")).isZero();
        assertThat(h.db.count("player_snapshot")).isZero();

        // 对照：上面用过的键 k 没有被任何一次失败占掉；边界区号 4294967295 合法（区里没人 → zone_empty）
        OpsJobRow job = h.await(jobId(service.submit(body(List.of(4_294_967_295L), null, null), OPERATOR, "k")));
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(job.getResultCode()).isEqualTo("zone_empty");
        assertThat(job.getRequestJson()).contains("\"zones\":[4294967295]");
    }

    @Test
    void allZones_区服目录为空404_zone_not_found_不留作业_有目录时按区号升序展开() throws Exception {
        h = new RollbackHarness(Map.of());
        ZoneSnapshotService service = service();
        assertThatThrownBy(() -> service.submit(body(null, true, null), OPERATOR, "k1"))
                .isInstanceOfSatisfying(OpsException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.code()).isEqualTo("zone_not_found");
                });
        assertThat(h.db.count("ops_job")).isZero();

        h.zone(6, ZoneManualStatus.OPEN.code());
        h.zone(5, ZoneManualStatus.MAINTENANCE.code());
        h.player(51, 5, STATE.toByteArray(), System.currentTimeMillis() - 1000, 2, false);
        h.player(61, 6, STATE.toByteArray(), System.currentTimeMillis() - 1000, 2, false);
        h.player(71, 7, STATE.toByteArray(), System.currentTimeMillis() - 1000, 2, false); // 目录外的区

        OpsJobRow job = h.await(jobId(service.submit(body(null, true, null), OPERATOR, "k1")));

        // 不要求维护态（快照不改玩家数据）：开放中的区 6 也拍；目录外的区 7 不在 allZones 里
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(job.getRequestJson()).contains("\"zones\":[5,6]").contains("\"allZones\":true");
        assertThat(snapshotPlayerIds()).containsExactly(51L, 61L);
        assertThat(h.summary(job).get("byZone").toString()).isEqualTo("{\"5\":1,\"6\":1}");
    }

    @Test
    void 幂等键_同键同参回原作业不重拍_区号去重排序后相同也算同参_异参或别的作业种类409() throws Exception {
        h = new RollbackHarness(Map.of());
        h.player(11, 1, STATE.toByteArray(), System.currentTimeMillis() - 1000, 2, false);
        h.player(21, 2, STATE.toByteArray(), System.currentTimeMillis() - 1000, 2, false);
        ZoneSnapshotService service = service();

        Map<String, Object> first = service.submit(body(List.of(2L, 1L, 1L), null, "维护窗口"), OPERATOR, "same");
        assertThat(first.get("replayed")).isEqualTo(false);
        assertThat(first.get("kind")).isEqualTo("zone_snapshot");
        OpsJobRow job = h.await(jobId(first));
        assertThat(job.getKind()).isEqualTo(OpsJobKind.OPS_JOB_ZONE_SNAPSHOT);
        assertThat(snapshotPlayerIds()).containsExactly(11L, 21L);

        Map<String, Object> replay = service.submit(body(List.of(1L, 2L), null, "维护窗口"), OPERATOR, "same");
        assertThat(replay.get("jobId")).isEqualTo(first.get("jobId"));
        assertThat(replay.get("replayed")).isEqualTo(true);
        assertThat(replay.get("status")).isEqualTo("succeeded");
        assertThat(h.db.count("ops_job")).isEqualTo(1);
        assertThat(snapshotPlayerIds()).as("重放不再拍一遍").containsExactly(11L, 21L);

        // 同键不同参数：区不同 / 备注不同 / allZones
        for (ZoneSnapshotService.Body other : List.of(body(List.of(1L), null, "维护窗口"),
                body(List.of(1L, 2L), null, "别的备注"), body(null, true, "维护窗口"))) {
            assertThatThrownBy(() -> service.submit(other, OPERATOR, "same"))
                    .isInstanceOfSatisfying(OpsException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("idempotency_conflict");
                        assertThat(e.details().get("jobId")).isEqualTo(first.get("jobId"));
                    });
        }
        // 同一个键拿去提交回档（别的作业种类）同样冲突
        h.logoutSnapshot(5001, 11, System.currentTimeMillis() - 60_000, STATE.toByteArray());
        assertThatThrownBy(() -> h.submit(RollbackHarness.players(List.of("11"), "5001", null, null, null, false), "same"))
                .isInstanceOfSatisfying(OpsException.class, e -> assertThat(e.code()).isEqualTo("idempotency_conflict"));
        assertThat(h.db.count("ops_job")).isEqualTo(1);
    }

    @Test
    void 写开关关着_回档503_ops_disabled_整区快照照常受理并执行() throws Exception {
        h = new RollbackHarness(Map.of("xm.data.ops.enabled", "false"));
        h.player(11, 1, STATE.toByteArray(), System.currentTimeMillis() - 1000, 2, false);
        h.logoutSnapshot(5001, 11, System.currentTimeMillis() - 60_000, STATE.toByteArray());
        assertThatThrownBy(() -> h.submit(RollbackHarness.players(List.of("11"), "5001", null, null, null, false), "k-rb"))
                .isInstanceOfSatisfying(OpsException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo("ops_disabled");
                });

        OpsJobRow job = h.await(submit(service(), List.of(1L), "k-zs"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(job.getResultCode()).isEqualTo("ok");
        assertThat(snapshotPlayerIds()).containsExactly(11L);
    }

    // ------------------------------------------------------------------ 作业体

    @Test
    void 只拍命中区_每人恰好一份PRE_MAINTENANCE_内容取已落盘_在线玩家照拍_不夺权不踢人不动归属() throws Exception {
        h = new RollbackHarness(Map.of());
        long now = System.currentTimeMillis();
        long offlineSavedAt = now - 50_000;
        long onlineSavedAt = now - 7_000;
        byte[] onlineState = PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(42)).build()
                .toByteArray();
        h.player(11, 1, STATE.toByteArray(), offlineSavedAt, 3, false);   // 离线：最终写回是 epoch 3 写的
        h.player(12, 1, onlineState, onlineSavedAt, 6, true);            // 在线：scene 持有 epoch 6，库里是它上次存盘的内容
        h.player(13, 1, null, 0, 0, false);                               // 建了角没进过场景：没有 player_state 行
        h.player(21, 2, STATE.toByteArray(), now - 1000, 2, false);       // 别的区
        long neverEnteredUpdatedAt = h.db.jdbc().queryForObject("SELECT updated_at FROM player WHERE player_id = 13",
                Long.class);
        String ownersBefore = h.db.jdbc().queryForList("SELECT player_id, owner_epoch, owner_released, owner_lease_until, "
                + "updated_at FROM player ORDER BY player_id").toString();
        String statesBefore = h.db.jdbc().queryForList("SELECT player_id, saved_epoch, updated_at FROM player_state "
                + "ORDER BY player_id").toString();

        long jobId = jobId(service().submit(body(List.of(1L), null, "维护窗口"), OPERATOR, "k1"));
        OpsJobRow job = h.await(jobId);

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(job.getResultCode()).isEqualTo("ok");
        assertThat(job.getKind()).isEqualTo(OpsJobKind.OPS_JOB_ZONE_SNAPSHOT);
        assertThat(job.getOperator()).isEqualTo(OPERATOR);
        assertThat(job.getPlayersPlanned()).isEqualTo(3);
        assertThat(job.getPlayersAffected()).isEqualTo(3);
        assertThat(job.getPlayersFailed()).isZero();
        JsonNode summary = h.summary(job);
        assertThat(summary.get("total").asInt()).isEqualTo(3);
        assertThat(summary.get("byZone").toString()).isEqualTo("{\"1\":3}");
        assertThat(h.eventTypes(jobId)).containsExactly("STARTED", "RESULT");
        // 只有区 1 的三个人各一份；区 2 不动；库里没有别的原因的快照
        assertThat(snapshotPlayerIds()).containsExactly(11L, 12L, 13L);
        assertThat(h.db.count("player_snapshot")).isEqualTo(3);

        String note = "维护窗口 job:" + Long.toUnsignedString(jobId);
        PlayerSnapshotEntry offline = onlySnapshotOf(11);
        assertThat(offline.getCause()).isEqualTo(SnapshotCauses.PRE_MAINTENANCE);
        assertThat(offline.getTimeMs()).as("内容时刻 = player_state.updated_at").isEqualTo(offlineSavedAt);
        assertThat(offline.getOwnerEpoch()).as("写入这份内容的 epoch").isEqualTo(3);
        assertThat(offline.getPlayerState()).isEqualTo(STATE.toByteArray());
        assertThat(offline.getZoneId()).isEqualTo(1);
        assertThat(offline.getLevel()).isEqualTo(9);
        assertThat(offline.getSceneConfigId()).isEqualTo(1001);
        assertThat(List.of(offline.getPosX(), offline.getPosY(), offline.getPosZ())).containsExactly(1.5, 2.5, -3.5);
        assertThat(offline.getOperator()).isEqualTo(OPERATOR);
        assertThat(offline.getNote()).isEqualTo(note);
        assertThat(offline.getIngestedAt()).isBetween(now, System.currentTimeMillis());

        PlayerSnapshotEntry online = onlySnapshotOf(12);
        assertThat(online.getTimeMs()).isEqualTo(onlineSavedAt);
        assertThat(online.getOwnerEpoch()).isEqualTo(6);
        assertThat(online.getPlayerState()).isEqualTo(onlineState);
        assertThat(online.getNote()).isEqualTo(note);

        PlayerSnapshotEntry neverEntered = onlySnapshotOf(13);
        assertThat(neverEntered.getTimeMs()).as("没有 player_state 行：取 player.updated_at").isEqualTo(neverEnteredUpdatedAt);
        assertThat(neverEntered.getOwnerEpoch()).as("内容不来自任何一次写回").isZero();
        assertThat(neverEntered.getPlayerState()).isEmpty();

        // 不需要栅栏：归属三列、player 行与 player_state 一个字节都没动；没发让出请求、没写墓碑
        assertThat(h.db.jdbc().queryForList("SELECT player_id, owner_epoch, owner_released, owner_lease_until, updated_at "
                + "FROM player ORDER BY player_id").toString()).isEqualTo(ownersBefore);
        assertThat(h.db.jdbc().queryForList("SELECT player_id, saved_epoch, updated_at FROM player_state ORDER BY player_id")
                .toString()).isEqualTo(statesBefore);
        assertThat(owner(12).ownerEpoch()).isEqualTo(6);
        assertThat(owner(12).released()).isFalse();
        assertThat(h.takeovers).isEmpty();
        assertThat(h.tombstones).isEmpty();
        assertThat(h.ownership.heldCount()).isZero();
        assertThat(h.guildCalls).isEmpty();
    }

    @Test
    void 备注末尾总带作业号_没给备注时只有作业号_备注到上限时截备注不截作业号() throws Exception {
        h = new RollbackHarness(Map.of());
        h.player(11, 1, STATE.toByteArray(), System.currentTimeMillis() - 1000, 2, false);
        ZoneSnapshotService service = service();

        long bare = jobId(service.submit(body(List.of(1L), null, null), OPERATOR, "k-bare"));
        h.await(bare);
        assertThat(onlySnapshotOf(11).getNote()).isEqualTo("job:" + Long.toUnsignedString(bare));

        // 256 个字符的备注（受理允许的上限）再拍一份：快照表的 note 列也是 256，作业号是从快照找回作业的唯一线索，不能被截掉
        h.db.jdbc().update("DELETE FROM player_snapshot");
        String longNote = "备".repeat(256);
        long full = jobId(service.submit(body(List.of(1L), null, longNote), OPERATOR, "k-full"));
        OpsJobRow job = h.await(full);
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        String suffix = " job:" + Long.toUnsignedString(full);
        String note = onlySnapshotOf(11).getNote();
        assertThat(note).endsWith(suffix);
        assertThat(note).hasSize(256);
        assertThat(note).isEqualTo("备".repeat(256 - suffix.length()) + suffix);
    }

    @Test
    void 分页_每批500人_按玩家号续翻_三页不漏不重_逐区计数() throws Exception {
        h = new RollbackHarness(Map.of());
        // 玩家号不连续（步长 3），区 8 的号夹在区 7 的号段中间：续翻只认「本区、号更大」
        List<Long> zone7 = bulkPlayers(7, 100_000, 1203, 3);
        List<Long> zone8 = bulkPlayers(8, 100_001, 2, 3);
        PersistedPlayerMapper players = delegatingPlayers();

        OpsJobRow job = h.await(submit(service(players, h.ids), List.of(8L, 7L), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(job.getPlayersPlanned()).isEqualTo(1205);
        assertThat(job.getPlayersAffected()).isEqualTo(1205);
        JsonNode summary = h.summary(job);
        assertThat(summary.get("total").asInt()).isEqualTo(1205);
        assertThat(summary.get("byZone").toString()).isEqualTo("{\"7\":1203,\"8\":2}");
        // 区按号升序；每页 500，游标 = 上一页最后一个玩家号；不足一页就不再多读一次
        InOrder order = inOrder(players);
        order.verify(players).listInZone(7, 0L, 500);
        order.verify(players).listInZone(7, zone7.get(499), 500);
        order.verify(players).listInZone(7, zone7.get(999), 500);
        order.verify(players).listInZone(8, 0L, 500);
        verify(players, times(4)).listInZone(anyInt(), anyLong(), anyInt());
        // 每人恰好一份：不漏、不重
        List<Long> expected = new ArrayList<>(zone7);
        expected.addAll(zone8);
        expected.sort(Long::compare);
        assertThat(snapshotPlayerIds()).isEqualTo(expected);
    }

    @Test
    void 恰好整页的区_多读一页发现没人了才结束_不漏最后一人() throws Exception {
        h = new RollbackHarness(Map.of());
        List<Long> zone7 = bulkPlayers(7, 200_000, 500, 1);
        PersistedPlayerMapper players = delegatingPlayers();

        OpsJobRow job = h.await(submit(service(players, h.ids), List.of(7L), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(job.getPlayersAffected()).isEqualTo(500);
        InOrder order = inOrder(players);
        order.verify(players).listInZone(7, 0L, 500);
        order.verify(players).listInZone(7, zone7.get(499), 500);
        verify(players, times(2)).listInZone(anyInt(), anyLong(), anyInt());
        assertThat(snapshotPlayerIds()).isEqualTo(zone7);
    }

    @Test
    void 空区_SUCCEEDED_zone_empty_不写任何快照() throws Exception {
        h = new RollbackHarness(Map.of());
        h.player(21, 2, STATE.toByteArray(), System.currentTimeMillis() - 1000, 2, false);

        OpsJobRow job = h.await(submit(service(), List.of(9L), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(job.getResultCode()).isEqualTo("zone_empty");
        assertThat(job.getPlayersPlanned()).isZero();
        assertThat(job.getPlayersAffected()).isZero();
        assertThat(h.summary(job).toString()).isEqualTo("{\"byZone\":{\"9\":0},\"total\":0}");
        assertThat(h.db.count("player_snapshot")).isZero();
        assertThat(h.jobs.active()).isEmpty();
    }

    @Test
    void 号源中途失效_当批回滚_之前的批保留_FAILED_id_unavailable_摘要带停在哪个区() throws Exception {
        h = new RollbackHarness(Map.of());
        List<Long> zone7 = bulkPlayers(7, 300_000, 503, 1);
        bulkPlayers(8, 400_000, 2, 1);
        // 快照号从单独一份租约发（作业号仍由 h.ids 发）：第 2 批读到第 2 个人时租约丢失——这一批的第 1 个人已经在事务里插了快照
        FakeLease snapshotLease = new FakeLease(77);
        extraIds = TestIds.ready(snapshotLease);
        PersistedPlayerMapper players = delegatingPlayers();
        AtomicInteger finds = new AtomicInteger();
        doAnswer(inv -> {
            if (finds.incrementAndGet() == 502) {
                snapshotLease.lose();
            }
            return h.db.players.find(inv.getArgument(0));
        }).when(players).find(anyLong());

        OpsJobRow job = h.await(submit(service(players, extraIds), List.of(7L, 8L), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_FAILED);
        assertThat(job.getResultCode()).isEqualTo("id_unavailable");
        assertThat(job.getPlayersAffected()).isEqualTo(500);
        JsonNode summary = h.summary(job);
        assertThat(summary.get("total").asInt()).isEqualTo(500);
        assertThat(summary.get("byZone").toString()).isEqualTo("{\"7\":500}");
        assertThat(summary.get("stoppedAt").asInt()).isEqualTo(7);
        // 第 1 批的 500 人留着（各自的事务已提交）；第 2 批已插的第 501 人随当批回滚；之后的人与区 8 没拍
        assertThat(finds).hasValue(502);
        assertThat(snapshotPlayerIds()).isEqualTo(zone7.subList(0, 500));
        assertThat(h.jobs.active()).as("失败也让出单飞槽").isEmpty();
    }

    @Test
    void 取消_批与批之间生效_CANCELLED_已拍的留着_之后的区不拍() throws Exception {
        h = new RollbackHarness(Map.of());
        long now = System.currentTimeMillis();
        h.player(11, 1, STATE.toByteArray(), now - 1000, 2, false);
        h.player(12, 1, STATE.toByteArray(), now - 1000, 2, false);
        h.player(21, 2, STATE.toByteArray(), now - 1000, 2, false);
        PersistedPlayerMapper players = delegatingPlayers();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        doAnswer(inv -> {
            // 区 1 的这一页正在读的时候运维点了取消
            reading.countDown();
            assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue();
            return h.db.players.listInZone(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
        }).when(players).listInZone(anyInt(), anyLong(), anyInt());

        long jobId = submit(service(players, h.ids), List.of(1L, 2L), "k1");
        assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(h.service.cancel(jobId).get("cancelRequested")).isEqualTo(true);
        proceed.countDown();
        OpsJobRow job = h.await(jobId);

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_CANCELLED);
        assertThat(job.getResultCode()).isEqualTo("cancelled");
        assertThat(job.getCancelRequested()).isTrue();
        assertThat(job.getPlayersAffected()).isEqualTo(2);
        JsonNode summary = h.summary(job);
        assertThat(summary.get("byZone").toString()).isEqualTo("{\"1\":2,\"2\":0}");
        assertThat(summary.get("total").asInt()).isEqualTo(2);
        assertThat(summary.get("stoppedAt").asInt()).isEqualTo(2);
        assertThat(snapshotPlayerIds()).containsExactly(11L, 12L);
        verify(players, times(1)).listInZone(anyInt(), anyLong(), anyInt());
        assertThat(h.jobs.active()).isEmpty();
    }

    @Test
    void 超时_批与批之间生效_FAILED_job_timeout_已拍的留着() throws Exception {
        h = new RollbackHarness(Map.of("xm.data.ops.job-timeout", "1s"));
        long now = System.currentTimeMillis();
        h.player(11, 1, STATE.toByteArray(), now - 1000, 2, false);
        h.player(21, 2, STATE.toByteArray(), now - 1000, 2, false);
        PersistedPlayerMapper players = delegatingPlayers();
        AtomicInteger pages = new AtomicInteger();
        doAnswer(inv -> {
            if (pages.incrementAndGet() == 1) {
                Thread.sleep(1600); // 区 1 这一页读得很慢，读完已经过了作业时限
            }
            return h.db.players.listInZone(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
        }).when(players).listInZone(anyInt(), anyLong(), anyInt());

        OpsJobRow job = h.await(submit(service(players, h.ids), List.of(1L, 2L), "k1"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_FAILED);
        assertThat(job.getResultCode()).isEqualTo("job_timeout");
        assertThat(job.getCancelRequested()).isFalse();
        JsonNode summary = h.summary(job);
        assertThat(summary.get("byZone").toString()).isEqualTo("{\"1\":1,\"2\":0}");
        assertThat(summary.get("stoppedAt").asInt()).isEqualTo(2);
        assertThat(snapshotPlayerIds()).containsExactly(11L);
        assertThat(pages).hasValue(1);
    }

    // ------------------------------------------------------------------ 与回档的关系

    @Test
    void 与回档共用单飞槽_回档在跑时提交409_ops_busy带回档作业号_反过来也一样() throws Exception {
        h = new RollbackHarness(Map.of("xm.data.rollback.guild.settle", "1500ms"));
        long now = System.currentTimeMillis();
        h.player(11, 1, STATE.toByteArray(), now - 5000, 3, false);
        h.logoutSnapshot(5001, 11, now - 60_000, RollbackJobSqlTest.snapshotState().toByteArray());

        // 回档在沉降里等着的时候提交整区快照
        long rollback = h.submit(RollbackHarness.players(List.of("11"), "5001", null, null, null, false), "k-rb");
        assertThatThrownBy(() -> submit(service(), List.of(1L), "k-zs"))
                .isInstanceOfSatisfying(OpsException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("ops_busy");
                    assertThat(e.details().get("runningJobId")).isEqualTo(Long.toUnsignedString(rollback));
                });
        assertThat(h.db.count("ops_job")).as("被挡的整区快照没有留下作业行").isEqualTo(1);
        assertThat(snapshotPlayerIds()).isEmpty();
        assertThat(h.await(rollback).getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);

        // 反过来：整区快照在跑的时候提交回档
        PersistedPlayerMapper players = delegatingPlayers();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        doAnswer(inv -> {
            reading.countDown();
            assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue();
            return h.db.players.listInZone(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
        }).when(players).listInZone(anyInt(), anyLong(), anyInt());
        long zoneSnapshot = submit(service(players, h.ids), List.of(1L), "k-zs");
        assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> h.submit(RollbackHarness.players(List.of("11"), "5001", null, List.of("level"), null,
                    false), "k-rb2")).isInstanceOfSatisfying(OpsException.class, e -> {
                        assertThat(e.code()).isEqualTo("ops_busy");
                        assertThat(e.details().get("runningJobId")).isEqualTo(Long.toUnsignedString(zoneSnapshot));
                    });
        } finally {
            proceed.countDown();
        }
        assertThat(h.await(zoneSnapshot).getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(snapshotPlayerIds()).containsExactly(11L);
        assertThat(h.db.count("ops_job")).isEqualTo(2);
    }

    @Test
    void 拍出的维护前快照能被回档按时刻选中_回到维护前的状态() throws Exception {
        h = new RollbackHarness(Map.of());
        long now = System.currentTimeMillis();
        long beforeMaintenance = now - 120_000;
        PlayerState good = RollbackJobSqlTest.snapshotState();
        h.player(11, 1, good.toByteArray(), beforeMaintenance, 3, false);
        h.player(12, 1, good.toByteArray(), beforeMaintenance, 3, false);

        long snapshotJob = submit(service(), List.of(1L), "k-zs");
        assertThat(h.await(snapshotJob).getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        long snapshotOf11 = onlySnapshotOf(11).getSnapshotId();

        // 维护里把玩家 11 的数据改坏了（内容时刻晚于快照）
        PlayerState broken = PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(7)).build();
        h.db.putState(11, broken.toByteArray(), 3, now - 10_000);

        // 按「维护前」的时刻回档：没有别的快照，选中的只能是刚才那份 PRE_MAINTENANCE
        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of("11"), null, now - 60_000, null, null, false),
                "k-rb"));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(h.jobs.players(job.getJobId(), 0, 10).get(0).getPlannedSnapshotId()).isEqualTo(snapshotOf11);
        assertThat(h.jobs.players(job.getJobId(), 0, 10).get(0).getPlannedSnapshotMs()).isEqualTo(beforeMaintenance);
        assertThat(PlayerState.parseFrom(h.db.players.find(11).stateBytes())).isEqualTo(good);
        // 目标时刻早于这份快照的内容时刻就选不中它（no_snapshot）：选源看的是内容时刻，不是拍摄时刻
        OpsJobRow tooEarly = h.await(h.submit(RollbackHarness.players(List.of("12"), null, beforeMaintenance - 1, null, null,
                false), "k-rb-early"));
        assertThat(tooEarly.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_REJECTED);
        assertThat(tooEarly.getResultCode()).isEqualTo("no_snapshot");
    }
}
