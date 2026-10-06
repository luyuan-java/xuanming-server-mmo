package com.game.data.rollback;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.api.proto.GuildAssetOpBrief;
import com.game.api.proto.ListAppliedAssetOpsSinceRequest;
import com.game.api.proto.ListAppliedAssetOpsSinceResponse;
import com.game.api.proto.ListAppliedResult;
import com.game.data.DataProperties;
import com.game.data.metrics.DataMetrics;
import com.game.data.ops.OpsIds;
import com.game.data.ops.OpsJobRunner;
import com.game.data.ops.OpsJobService;
import com.game.data.ops.OpsJobStore;
import com.game.data.ops.fence.AdminOwnership;
import com.game.data.ops.pb.OpsJobRow;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.query.TransactionLogQueryService;
import com.game.data.snapshot.PlayerSnapshotRow;
import com.game.data.snapshot.SnapshotCauses;
import com.game.data.testing.DataSqlFixture;
import com.game.data.testing.TestIds;
import com.game.gateway.store.GatewayStore;
import com.game.gateway.store.GatewayStoreMapper;
import com.game.gateway.store.ZoneRow;
import com.game.player.store.PlayerStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 回档测试的装配：生产的作业框架 / 栅栏 / 回档代码 + H2（或 {@code -Dxm.it.mysql} 的真 MySQL）+ 替身（让出请求、位置墓碑、帮会客户端、区服目录）。
 * 时钟用真实墙钟（归属租约按墙钟判过期）；沉降 / 复查等待缺省 0。
 */
final class RollbackHarness implements AutoCloseable {

    final DataSqlFixture db;
    final OpsIds ids;
    final DataMetrics metrics;
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final ObjectMapper json = new ObjectMapper();
    final PlayerStore store;
    final OpsJobStore jobs;
    final ScheduledExecutorService fence;
    final OpsJobRunner runner;
    final OpsJobService service;
    final AdminOwnership ownership;
    final RollbackService rollbacks;
    final RollbackJob.Deps deps;
    final DataProperties props;
    final GatewayStoreMapper zoneMapper = mock(GatewayStoreMapper.class);
    final GatewayStore zones;

    /** 让出请求记录；{@link #onTakeover} 模拟 scene：收到就带围栏释放。 */
    final List<long[]> takeovers = new CopyOnWriteArrayList<>();
    volatile boolean sceneReleasesOnTakeover = true;
    /** 位置墓碑记录。 */
    final List<long[]> tombstones = new CopyOnWriteArrayList<>();
    /** 帮会客户端的应答：按调用序号给出（超出取最后一个）；为空表示没装配。 */
    final List<Function<ListAppliedAssetOpsSinceRequest, ListAppliedAssetOpsSinceResponse>> guildAnswers =
            new CopyOnWriteArrayList<>();
    final List<ListAppliedAssetOpsSinceRequest> guildCalls = new CopyOnWriteArrayList<>();

    RollbackHarness(Map<String, String> overrides) throws Exception {
        this(overrides, true);
    }

    RollbackHarness(Map<String, String> overrides, boolean guildConfigured) throws Exception {
        db = DataSqlFixture.create();
        ids = TestIds.ready(31);
        metrics = new DataMetrics(meters);
        Map<String, String> settings = new HashMap<>();
        settings.put("xm.data.ops.enabled", "true");
        settings.put("xm.data.ops.claim-wait", "2s");
        settings.put("xm.data.ops.min-target-age", "0s");
        settings.put("xm.data.rollback.guild.settle", "0s");
        settings.put("xm.data.rollback.guild.recheck-delay", "0s");
        settings.putAll(overrides);
        props = new Binder(new MapConfigurationPropertySource(settings)).bindOrCreate("xm.data", DataProperties.class);
        store = db.playerStore(System::currentTimeMillis);
        jobs = db.jobs();
        fence = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("test-ops-fence").daemon(true).factory());
        runner = new OpsJobRunner(jobs, db.tx(), json, metrics, Clock.systemUTC(), "test-runner", props.ops().heartbeat(),
                props.ops().staleAfter(), props.ops().jobTimeout(), fence);
        service = new OpsJobService(jobs, ids, runner, db.tx(), json, Clock.systemUTC());
        ownership = new AdminOwnership(store, db.tx(), this::onTakeover, (p, e) -> {
            tombstones.add(new long[] {p, e});
            return CompletableFuture.completedFuture(true);
        }, metrics);
        GuildDivergenceGate guild = new GuildDivergenceGate(guildConfigured ? this::guild : null, Duration.ofSeconds(2));
        RollbackWriter writer = new RollbackWriter(store, db.playerMapper, db.players, db.snapshots, db.txlog, jobs, ids,
                db.tx(), json, Clock.systemUTC());
        deps = new RollbackJob.Deps(new RollbackPlanner(db.players, db.snapshots), writer, ownership, guild, db.players,
                db.snapshots, new TransactionLogQueryService(db.txlog, Duration.ofDays(7)), jobs, props, metrics, json);
        zones = new GatewayStore(zoneMapper, TransactionOperations.withoutTransaction(), System::currentTimeMillis);
        when(zoneMapper.selectZones()).thenReturn(List.of());
        rollbacks = new RollbackService(deps, service, zones, props, Clock.systemUTC());
    }

    private void onTakeover(long playerId, long heldEpoch) {
        takeovers.add(new long[] {playerId, heldEpoch});
        if (sceneReleasesOnTakeover) {
            // scene 带围栏写回并释放（这里只释放；写回内容不变）
            db.playerMapper.releaseOwner(playerId, heldEpoch, System.currentTimeMillis());
        }
    }

    private CompletableFuture<ListAppliedAssetOpsSinceResponse> guild(ListAppliedAssetOpsSinceRequest request,
                                                                     Duration timeout) {
        int n = guildCalls.size();
        guildCalls.add(request);
        if (guildAnswers.isEmpty()) {
            return CompletableFuture.completedFuture(ok());
        }
        return CompletableFuture.completedFuture(guildAnswers.get(Math.min(n, guildAnswers.size() - 1)).apply(request));
    }

    static ListAppliedAssetOpsSinceResponse ok(GuildAssetOpBrief... ops) {
        return ListAppliedAssetOpsSinceResponse.newBuilder().setResult(ListAppliedResult.LIST_APPLIED_RESULT_OK)
                .addAllOps(List.of(ops)).build();
    }

    static GuildAssetOpBrief op(long opId, long player, long updatedMs) {
        return GuildAssetOpBrief.newBuilder().setOpId(opId).setPlayerId(player).setGuildId(77).setStream(1).setKind(1)
                .setStatus(2).setUpdatedMs(updatedMs).build();
    }

    void zone(int zoneId, int status) {
        List<ZoneRow> rows = new ArrayList<>(zones.zones());
        rows.add(new ZoneRow(zoneId, "z" + zoneId, status, 5000, "", null, false, zoneId, 0, 0));
        when(zoneMapper.selectZones()).thenReturn(rows);
    }

    /** 造一名玩家：player 行 + 现档；{@code online} = 被别的写者持有（租约 1 小时）。返回 owner_epoch。 */
    void player(long playerId, int zoneId, byte[] state, long stateUpdatedAt, long epoch, boolean online) {
        long now = System.currentTimeMillis();
        db.insertPlayer(playerId, zoneId, 9, 1001, epoch, !online, online ? now + 3_600_000 : 0, now - 86_400_000L,
                now - 1000);
        if (state != null) {
            db.putState(playerId, state, epoch, stateUpdatedAt);
        }
    }

    /** 插一份 scene 快照（经 Kafka 的口径：operator / note 为空）。 */
    void snapshot(long snapshotId, long playerId, long timeMs, int cause, int zoneId, int level, int sceneConfigId,
                  byte[] state) {
        db.snapshots.insertDirect(new PlayerSnapshotRow(snapshotId, playerId, timeMs, cause, zoneId, 3, level, sceneConfigId,
                10.5, 0, -2.5, state), timeMs + 50, "", "");
    }

    void logoutSnapshot(long snapshotId, long playerId, long timeMs, byte[] state) {
        snapshot(snapshotId, playerId, timeMs, SnapshotCauses.LOGOUT, 1, 5, 2002, state);
    }

    /** 提交一个回档请求（非 dry-run），返回作业号。 */
    long submit(RollbackRequest.Body body, String key) {
        Map<String, Object> out = rollbacks.handle(body, "ops", key);
        return Long.parseUnsignedLong((String) out.get("jobId"));
    }

    /** 等作业终结（至多 20 s）。 */
    OpsJobRow await(long jobId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            OpsJobRow job = jobs.findJob(jobId).orElseThrow();
            if (job.getStatus() != OpsJobStatus.OPS_JOB_QUEUED && job.getStatus() != OpsJobStatus.OPS_JOB_RUNNING
                    && jobs.active().isEmpty()) {
                return job;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("作业 " + jobId + " 20 s 内没有终结：" + jobs.findJob(jobId));
    }

    List<String> eventTypes(long jobId) {
        return jobs.events(jobId).stream().map(e -> e.getType().name().substring("OPS_JOB_EVENT_".length())).toList();
    }

    JsonNode summary(OpsJobRow job) throws Exception {
        return json.readTree(job.getSummaryJson());
    }

    int count(String sql, Object... args) {
        Integer n = db.jdbc().queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Override
    public void close() throws Exception {
        runner.stop();
        fence.shutdownNow();
        ids.close();
        db.close();
    }

    static RollbackRequest.Body players(List<String> players, String snapshotId, Long targetTimeMs, List<String> sections,
                                        String ifOnline, boolean acceptDivergence) {
        return new RollbackRequest.Body("players", players, null, null, snapshotId, targetTimeMs, sections, ifOnline,
                acceptDivergence, false, "客诉补偿", false);
    }
}
