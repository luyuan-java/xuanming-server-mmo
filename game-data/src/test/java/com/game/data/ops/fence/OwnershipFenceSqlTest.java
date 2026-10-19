package com.game.data.ops.fence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.game.data.metrics.DataMetrics;
import com.game.data.ops.fence.AdminOwnership.Claim;
import com.game.data.testing.DataSqlFixture;
import com.game.player.store.OwnerState;
import com.game.player.store.OwnerLease;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerMapper;
import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerStore.ClaimResult;
import com.game.player.store.PlayerStore.HandOffResult;
import com.game.player.store.state.PlayerState;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.BagState;
import com.game.player.store.state.BagItemState;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 离线栅栏 = 归属夺权（T-F1 / T-F2 / T-F3，data-ops-spec §4.2）：用生产的 {@link PlayerStore} 与 SQL（H2 或真 MySQL）。
 * 与 5.2 交出互斥、续约丢失后不再写、墓碑先于释放。
 */
class OwnershipFenceSqlTest {

    private static final long P = 1001;

    private DataSqlFixture db;
    private PlayerStore store;
    private AdminOwnership ownership;
    private final List<String> calls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        db = DataSqlFixture.create();
        store = db.playerStore(System::currentTimeMillis);
        ownership = new AdminOwnership(store, db.tx(), (p, e) -> calls.add("takeover:" + p + ":" + e), (p, e) -> {
            calls.add("tombstone:" + p + ":" + e + ":released=" + owner().released());
            return CompletableFuture.completedFuture(true);
        }, new DataMetrics(new SimpleMeterRegistry()));
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private OwnerState owner() {
        return db.tx().execute(s -> db.playerMapper.selectOwnerForUpdate(P));
    }

    private void player(long epoch, boolean released, long leaseUntil) {
        long now = System.currentTimeMillis();
        db.insertPlayer(P, 1, 9, 1001, epoch, released, leaseUntil, now - 1000, now - 1000);
    }

    private static void noPause(Duration d) {
    }

    /** login 进游戏时的夺权（它自己的 PlayerStore，同一张表、同一条 SQL）。 */
    private ClaimResult loginClaim(PlayerStore login) {
        return db.tx().execute(s -> login.claimOwnership(P));
    }

    @Test
    void 持有中且租约有效_reject_Online_零变化_已释放_夺到_租约过期_夺到且旧写者被拒() {
        player(5, false, System.currentTimeMillis() + 60_000);
        assertThat(ownership.claim(P, false, Duration.ofSeconds(1), OwnershipFenceSqlTest::noPause))
                .isEqualTo(new Claim.Online(5));
        assertThat(owner()).isEqualTo(new OwnerState(5, false, owner().leaseUntil()));
        assertThat(calls).isEmpty();

        db.playerMapper.releaseOwner(P, 5, System.currentTimeMillis());
        assertThat(ownership.claim(P, false, Duration.ofSeconds(1), OwnershipFenceSqlTest::noPause))
                .isEqualTo(new Claim.Claimed(6, false));
        ownership.release(P);
        assertThat(owner().released()).isTrue();
        assertThat(calls).containsExactly("tombstone:1001:6:released=false");

        // 租约过期（写者死了）：夺到；旧写者（epoch 7）迟到的在线存盘被围栏拒绝
        db.jdbc().update("UPDATE player SET owner_epoch = 7, owner_released = 0, owner_lease_until = ? WHERE player_id = ?",
                System.currentTimeMillis() - 1, P);
        assertThat(ownership.claim(P, false, Duration.ofSeconds(1), OwnershipFenceSqlTest::noPause))
                .isEqualTo(new Claim.Claimed(8, false));
        PlayerRow late = new PlayerRow();
        late.setPlayerId(P);
        late.setOwnerEpoch(7);
        Boolean saved = db.tx().execute(s -> store.saveStateHeld(late, PlayerState.getDefaultInstance()));
        assertThat(saved).isFalse();
        ownership.releaseAll();
    }

    @Test
    void kick_每次重试都重发让出请求_持有者一直不放_Busy() {
        player(5, false, System.currentTimeMillis() + 60_000);
        Claim claim = ownership.claim(P, true, Duration.ofMillis(200), d -> {
            try {
                Thread.sleep(d.toMillis());
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(claim).isEqualTo(new Claim.Busy(5));
        assertThat(calls.size()).isGreaterThan(1);
        assertThat(calls).allMatch(c -> c.equals("takeover:1001:5"));
    }

    @Test
    void 不存在的玩家_NotFound() {
        assertThat(ownership.claim(42, true, Duration.ofSeconds(1), OwnershipFenceSqlTest::noPause))
                .isEqualTo(new Claim.NotFound());
    }

    @Test
    void SQL行锁拖过维护预算后不得迟到夺权_历史资产与归属原样() throws Exception {
        assertBlockedClaimRollsBack(() -> ownership.claim(P, false, Duration.ofMillis(100), OwnershipFenceSqlTest::noPause));
    }

    @Test
    void 历史恢复的SQL行锁拖过预算后回滚_不登记持有也不改资产() throws Exception {
        assertBlockedClaimRollsBack(() -> ownership.recoverAfterRestore(P, OptionalLong.of(8), Duration.ofMillis(100)));
    }

    private void assertBlockedClaimRollsBack(Supplier<Claim> action) throws Exception {
        player(5, true, 0);
        byte[] assets = PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(918)).build().toByteArray();
        db.putState(P, assets, 3, 1234);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch claiming = new CountDownLatch(1);
        // 只观察真实 SQL 的进入时刻，不替换结果。此处已进入 withinDeadline，主线程才开始计持锁故障时间。
        PlayerMapper observed = mock(PlayerMapper.class, delegatesTo(db.playerMapper));
        doAnswer(invocation -> {
            claiming.countDown();
            return db.playerMapper.claimOwnerEpoch(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
        }).when(observed).claimOwnerEpoch(anyLong(), anyLong(), anyLong());
        doAnswer(invocation -> {
            claiming.countDown();
            return db.playerMapper.selectOwnerForUpdate(invocation.getArgument(0));
        }).when(observed).selectOwnerForUpdate(anyLong());
        store = new PlayerStore(observed, System::currentTimeMillis, db.transactionManager);
        ownership = new AdminOwnership(store, db.tx(), (p, e) -> calls.add("takeover:" + p + ":" + e),
                (p, e) -> CompletableFuture.completedFuture(true), new DataMetrics(new SimpleMeterRegistry()));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> db.tx().execute(status -> {
                db.playerMapper.selectOwnerForUpdate(P);
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试行锁未释放");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return null;
            }));
            assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
            try {
                var claim = executor.submit(action::get);
                assertThat(claiming.await(3, TimeUnit.SECONDS)).isTrue();
                // 故障注入：已有写事务持续持锁，直到维护调用的纳秒预算明确过期。
                CompletableFuture.delayedExecutor(400, TimeUnit.MILLISECONDS).execute(release::countDown);
                assertThat(claim.get(4, TimeUnit.SECONDS)).isEqualTo(new Claim.Failed("运维夺权超时"));
                assertThat(owner()).isEqualTo(new OwnerState(5, true, 0));
                assertThat(ownership.heldCount()).isZero();
                assertStateBytes(assets, 3);
            } finally {
                release.countDown();
                holder.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void 历史零纪元恢复只推进SQL归属_资产原字节保持_复活旧写者所有入口被拒() {
        player(0, true, 0);
        var unknown = UnknownFieldSet.newBuilder().addField(1001, UnknownFieldSet.Field.newBuilder()
                .addLengthDelimited(ByteString.copyFromUtf8("历史实例成长与未来字段")).build()).build();
        var historical = PlayerState.newBuilder()
                .setCurrency(CurrencyState.newBuilder().addBalances(918).addBalances(727))
                .setBag(BagState.newBuilder().addItems(BagItemState.newBuilder().setItemUuid(700)
                        .setConfigId(1).setStackSize(1).setUnknownFields(unknown)))
                .setUnknownFields(unknown).build();
        byte[] original = historical.toByteArray();
        db.putState(P, original, 0, 1234);
        PlayerRow zombie = store.findPlayer(P).orElseThrow();

        assertThat(ownership.claim(P, false, Duration.ofSeconds(2), OwnershipFenceSqlTest::noPause))
                .isEqualTo(new Claim.Claimed(1, false));
        assertThat(ownership.claim(P, false, Duration.ofSeconds(2), OwnershipFenceSqlTest::noPause))
                .isEqualTo(new Claim.Online(1));
        assertThat(db.jdbc().queryForObject("SELECT data FROM player_state WHERE player_id=?", byte[].class, P))
                .containsExactly(original);
        assertThat(db.jdbc().queryForObject("SELECT saved_epoch FROM player_state WHERE player_id=?", Long.class, P)).isZero();
        assertThat(store.loadState(P)).isEqualTo(historical);

        Boolean heldSaved = db.tx().execute(s -> store.saveStateHeld(zombie, PlayerState.getDefaultInstance()));
        Boolean releasedSaved = db.tx().execute(s -> store.saveStateAndRelease(zombie, PlayerState.getDefaultInstance()));
        assertThat(heldSaved).isFalse();
        assertThat(releasedSaved).isFalse();
        assertThat(store.releaseOwnership(P, 0)).isFalse();
        assertThat(store.renewOwnerLeases(List.of(new OwnerLease(P, 0)))).containsExactly(new OwnerLease(P, 0));
        assertThat(store.handOffOwnership(zombie, PlayerState.getDefaultInstance(),
                System.currentTimeMillis() + 30_000, System.currentTimeMillis(), Duration.ofSeconds(2)))
                .isInstanceOf(HandOffResult.Fenced.class);
        assertThat(owner().ownerEpoch()).isEqualTo(1);
        assertThat(db.jdbc().queryForObject("SELECT data FROM player_state WHERE player_id=?", byte[].class, P))
                .containsExactly(original);
        ownership.release(P);
        assertThat(store.claimOwnership(P, Duration.ofSeconds(2))).isEqualTo(new ClaimResult.Claimed(2));
        assertThat(store.loadState(P)).isEqualTo(historical);
    }

    @Test
    void SQL归属PITR回退到零_独立审核旧纪元上界禁止复用仍存活实例的epoch() {
        // SQL 恢复到旧备份；epoch 1 的旧实例及其上界证据独立于该备份保留，不能从回退 SQL 猜出上界。
        player(0, true, 0);
        long auditedOldUpperBound = 1;
        PlayerRow zombie = new PlayerRow();
        zombie.setPlayerId(P);
        zombie.setOwnerEpoch(auditedOldUpperBound);
        byte[] assets = PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(918)).build().toByteArray();
        db.putState(P, assets, 0, 1234);

        Claim recovered = ownership.recoverAfterRestore(P, OptionalLong.of(auditedOldUpperBound), Duration.ofSeconds(2));
        assertThat(recovered).isEqualTo(new Claim.Claimed(2, false));
        assertThat(owner().ownerEpoch()).isGreaterThan(auditedOldUpperBound);
        Boolean heldSaved = db.tx().execute(s -> store.saveStateHeld(zombie, PlayerState.getDefaultInstance()));
        Boolean releasedSaved = db.tx().execute(s -> store.saveStateAndRelease(zombie, PlayerState.getDefaultInstance()));
        assertThat(heldSaved).isFalse();
        assertThat(releasedSaved).isFalse();
        assertThat(store.releaseOwnership(P, auditedOldUpperBound)).isFalse();
        assertThat(store.renewOwnerLeases(List.of(new OwnerLease(P, auditedOldUpperBound))))
                .containsExactly(new OwnerLease(P, auditedOldUpperBound));
        assertThat(store.handOffOwnership(zombie, PlayerState.getDefaultInstance(),
                System.currentTimeMillis() + 30_000, System.currentTimeMillis(), Duration.ofSeconds(2)))
                .isInstanceOf(HandOffResult.Fenced.class);
        assertThat(db.jdbc().queryForObject("SELECT data FROM player_state WHERE player_id=?", byte[].class, P))
                .containsExactly(assets);
        assertThat(db.jdbc().queryForObject("SELECT saved_epoch FROM player_state WHERE player_id=?", Long.class, P)).isZero();
        ownership.release(P);
    }

    @Test
    void 历史恢复缺少或非法审核上界拒绝_已知零上界有效且SQL当前值仍须递增() {
        player(5, true, 0);
        byte[] assets = PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(918)).build().toByteArray();
        db.putState(P, assets, 3, 1234);
        OwnerState before = owner();
        for (OptionalLong floor : new OptionalLong[]{null, OptionalLong.empty(), OptionalLong.of(-1), OptionalLong.of(Long.MAX_VALUE)}) {
            assertThat(ownership.recoverAfterRestore(P, floor, Duration.ofSeconds(2))).isInstanceOf(Claim.Failed.class);
            assertThat(owner()).isEqualTo(before);
            assertThat(ownership.heldCount()).isZero();
            assertStateBytes(assets, 3);
        }
        assertThat(ownership.recoverAfterRestore(42, OptionalLong.of(0), Duration.ofSeconds(2))).isEqualTo(new Claim.NotFound());
        assertThat(ownership.recoverAfterRestore(P, OptionalLong.of(0), Duration.ofSeconds(2))).isEqualTo(new Claim.Claimed(6, false));
        assertThat(ownership.epochOf(P)).hasValue(6);
        assertThat(ownership.recoverAfterRestore(P, OptionalLong.of(0), Duration.ofSeconds(2))).isEqualTo(new Claim.Online(6));
        assertStateBytes(assets, 3);
        ownership.release(P);
    }

    @Test
    void 历史恢复正long上界拒绝溢出_可安全抵达最大值且资产不变() {
        player(Long.MAX_VALUE, true, 0);
        byte[] assets = PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(918)).build().toByteArray();
        db.putState(P, assets, 3, 1234);
        OwnerState before = owner();
        assertThat(ownership.recoverAfterRestore(P, OptionalLong.of(1), Duration.ofSeconds(2))).isInstanceOf(Claim.Failed.class);
        assertThat(owner()).isEqualTo(before);
        assertThat(ownership.heldCount()).isZero();
        assertStateBytes(assets, 3);

        db.jdbc().update("UPDATE player SET owner_epoch=0 WHERE player_id=?", P);
        assertThat(ownership.recoverAfterRestore(P, OptionalLong.of(Long.MAX_VALUE - 1), Duration.ofSeconds(2)))
                .isEqualTo(new Claim.Claimed(Long.MAX_VALUE, false));
        assertThat(owner().ownerEpoch()).isEqualTo(Long.MAX_VALUE);
        assertStateBytes(assets, 3);
        ownership.release(P);
    }

    @Test
    void 历史恢复租约等于当前时刻仍持有_严格过期或已释放才可推进() {
        long now = 1_000_000;
        // 不用 StrictClock，精确验证 == now 的协议边界，避免真实墙钟经过导致测试偶然过期。
        store = new PlayerStore(db.playerMapper, () -> now, db.transactionManager);
        ownership = new AdminOwnership(store, db.tx(), (p, e) -> calls.add("takeover:" + p + ":" + e),
                (p, e) -> CompletableFuture.completedFuture(true), new DataMetrics(new SimpleMeterRegistry()));
        player(5, false, now);
        byte[] assets = PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(918)).build().toByteArray();
        db.putState(P, assets, 3, 1234);
        OwnerState before = owner();
        assertThat(ownership.recoverAfterRestore(P, OptionalLong.of(9), Duration.ofSeconds(2))).isEqualTo(new Claim.Online(5));
        assertThat(owner()).isEqualTo(before);
        assertThat(ownership.heldCount()).isZero();
        assertStateBytes(assets, 3);

        db.jdbc().update("UPDATE player SET owner_lease_until=? WHERE player_id=?", now - 1, P);
        assertThat(ownership.recoverAfterRestore(P, OptionalLong.of(9), Duration.ofSeconds(2))).isEqualTo(new Claim.Claimed(10, false));
        assertStateBytes(assets, 3);
        ownership.release(P);
        db.jdbc().update("UPDATE player SET owner_lease_until=? WHERE player_id=?", now + 60_000, P);
        assertThat(ownership.recoverAfterRestore(P, OptionalLong.of(9), Duration.ofSeconds(2))).isEqualTo(new Claim.Claimed(11, false));
        assertStateBytes(assets, 3);
        assertThat(calls).isEmpty();
        ownership.release(P);
    }

    @Test
    void 历史恢复禁止外层事务吞掉预算_归属和资产零变化() {
        player(5, true, 0);
        byte[] assets = PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(918)).build().toByteArray();
        db.putState(P, assets, 3, 1234);
        OwnerState before = owner();
        Claim refused = db.tx().execute(s -> ownership.recoverAfterRestore(P, OptionalLong.of(9), Duration.ofSeconds(2)));
        assertThat(refused).isInstanceOf(Claim.Failed.class);
        assertThat(owner()).isEqualTo(before);
        assertThat(ownership.heldCount()).isZero();
        assertStateBytes(assets, 3);
    }

    private void assertStateBytes(byte[] expected, long savedEpoch) {
        assertThat(db.jdbc().queryForObject("SELECT data FROM player_state WHERE player_id=?", byte[].class, P)).containsExactly(expected);
        assertThat(db.jdbc().queryForObject("SELECT saved_epoch FROM player_state WHERE player_id=?", Long.class, P)).isEqualTo(savedEpoch);
    }

    @Test
    void 与交出互斥_交出在途时夺不到_源已死夺权之后迟到的交出被围栏拒() {
        long now = System.currentTimeMillis();
        player(5, false, now + 30_000);
        // 交出提交：E+1 = 6 未释放、新租约 → 运维夺不到
        PlayerRow frozen = new PlayerRow();
        frozen.setPlayerId(P);
        frozen.setOwnerEpoch(5);
        HandOffResult handed = store.handOffOwnership(frozen, PlayerState.getDefaultInstance(), now + 30_000, now,
                Duration.ofSeconds(5));
        assertThat(handed).isEqualTo(new HandOffResult.HandedOff(6));
        assertThat(ownership.claim(P, false, Duration.ofSeconds(1), OwnershipFenceSqlTest::noPause))
                .isEqualTo(new Claim.Online(6));

        // 目标节点也死了、租约过期：运维夺到 7；源（持 6）迟到的交出被围栏拒
        db.jdbc().update("UPDATE player SET owner_lease_until = ? WHERE player_id = ?", now - 1, P);
        assertThat(ownership.claim(P, false, Duration.ofSeconds(1), OwnershipFenceSqlTest::noPause))
                .isEqualTo(new Claim.Claimed(7, false));
        PlayerRow late = new PlayerRow();
        late.setPlayerId(P);
        late.setOwnerEpoch(6);
        HandOffResult fenced = store.handOffOwnership(late, PlayerState.getDefaultInstance(),
                System.currentTimeMillis() + 30_000, System.currentTimeMillis(), Duration.ofSeconds(5));
        assertThat(fenced).isInstanceOf(HandOffResult.Fenced.class);
        assertThat(owner().ownerEpoch()).isEqualTo(7);
        ownership.releaseAll();
    }

    @Test
    void 续约发现失去归属_记为lost_之后epochOf为空_释放时不写墓碑() {
        player(5, true, 0);
        assertThat(ownership.claim(P, false, Duration.ofSeconds(1), OwnershipFenceSqlTest::noPause))
                .isEqualTo(new Claim.Claimed(6, false));
        ownership.renew();
        assertThat(ownership.epochOf(P)).hasValue(6);
        // 别人（租约过期后的新进场）夺走了
        db.jdbc().update("UPDATE player SET owner_epoch = 9 WHERE player_id = ?", P);
        ownership.renew();
        assertThat(ownership.lost(P)).isTrue();
        assertThat(ownership.epochOf(P)).isEmpty();
        ownership.release(P);
        assertThat(calls).isEmpty();
        assertThat(owner().ownerEpoch()).isEqualTo(9);
    }

    @Test
    void 收尾释放全部_墓碑并发发出_Redis不应答也只等一次上限_之后照样带围栏释放() {
        AdminOwnership hanging = new AdminOwnership(store, db.tx(), (p, e) -> calls.add("takeover"),
                (p, e) -> new CompletableFuture<>(), new DataMetrics(new SimpleMeterRegistry()));
        long now = System.currentTimeMillis();
        for (long p = 1; p <= 3; p++) {
            db.insertPlayer(p, 1, 9, 1001, 5, true, 0, now - 1000, now - 1000);
            assertThat(hanging.claim(p, false, Duration.ofSeconds(1), OwnershipFenceSqlTest::noPause))
                    .isInstanceOf(Claim.Claimed.class);
        }
        long start = System.nanoTime();
        hanging.releaseAll();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(AdminOwnership.TOMBSTONE_WAIT.toMillis() + 1500);
        assertThat(hanging.heldCount()).isZero();
        for (long p = 1; p <= 3; p++) {
            long player = p;
            OwnerState o = db.tx().execute(s -> db.playerMapper.selectOwnerForUpdate(player));
            assertThat(o.released()).isTrue();
            assertThat(o.ownerEpoch()).isEqualTo(6);
        }
    }

    @Test
    void 释放一批_只放给出的人_墓碑并发只等一次上限_其余继续持有_没持有的跳过() {
        List<String> order = new CopyOnWriteArrayList<>();
        AdminOwnership hanging = new AdminOwnership(store, db.tx(), (p, e) -> calls.add("takeover"), (p, e) -> {
            order.add("tombstone:" + p + ":" + e + ":released="
                    + db.tx().execute(s -> db.playerMapper.selectOwnerForUpdate(p)).released());
            return new CompletableFuture<>(); // Redis 不应答
        }, new DataMetrics(new SimpleMeterRegistry()));
        long now = System.currentTimeMillis();
        for (long p = 1; p <= 4; p++) {
            db.insertPlayer(p, 1, 9, 1001, 5, true, 0, now - 1000, now - 1000);
            assertThat(hanging.claim(p, false, Duration.ofSeconds(1), OwnershipFenceSqlTest::noPause))
                    .isInstanceOf(Claim.Claimed.class);
        }

        long start = System.nanoTime();
        hanging.releaseMany(List.of(1L, 3L, 4242L));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 两个墓碑都不应答：一起只等一个上限（逐个 release 是 2 × 上限）
        assertThat(elapsedMs).isLessThan(AdminOwnership.TOMBSTONE_WAIT.toMillis() + 1500);
        // 墓碑先于释放，只给这两个人写；不认识的 4242 跳过
        assertThat(order).containsExactly("tombstone:1:6:released=false", "tombstone:3:6:released=false");
        for (long p = 1; p <= 4; p++) {
            long player = p;
            OwnerState o = db.tx().execute(s -> db.playerMapper.selectOwnerForUpdate(player));
            assertThat(o.ownerEpoch()).isEqualTo(6);
            assertThat(o.released()).as("玩家 %d", p).isEqualTo(p == 1 || p == 3);
        }
        assertThat(hanging.heldCount()).isEqualTo(2);
        assertThat(hanging.epochOf(1)).isEmpty();
        assertThat(hanging.epochOf(2)).hasValue(6);
        assertThat(hanging.epochOf(4)).hasValue(6);
        // 重复释放同一批：什么也不做（不再写墓碑）
        hanging.releaseMany(List.of(1L, 3L));
        assertThat(order).hasSize(2);
        // 续约判失去的人：不写墓碑、不调释放
        db.jdbc().update("UPDATE player SET owner_epoch = 9 WHERE player_id = ?", 2L);
        hanging.renew();
        hanging.releaseMany(List.of(2L));
        assertThat(order).hasSize(2);
        assertThat(db.tx().execute(s -> db.playerMapper.selectOwnerForUpdate(2L)).released()).isFalse();
        assertThat(hanging.lost(2)).as("失去标记随释放清掉").isFalse();
        hanging.releaseAll();
        assertThat(hanging.heldCount()).isZero();
        assertThat(db.tx().execute(s -> db.playerMapper.selectOwnerForUpdate(4L)).released()).isTrue();
    }

    /**
     * T-F2（审计 OPS-13）：运维持有期间玩家登录——login 进游戏调 {@code claimOwnership}，撞上 Held(运维的 epoch)，它发的让出请求
     * {@code OwnerTakeover{E'}} 没有任何 scene 持有（game-data 不订阅频道），所以 3 s 后回 2005。这里用 login 自己的 PlayerStore 直接验判定。
     */
    @Test
    void 运维持有期间_login的夺权得到Held带运维的epoch_归属原样_续约后依旧_释放后才夺到_反过来运维看到的是在线() {
        player(5, true, 0);
        assertThat(ownership.claim(P, false, Duration.ofSeconds(1), OwnershipFenceSqlTest::noPause))
                .isEqualTo(new Claim.Claimed(6, false));
        PlayerStore login = db.playerStore(System::currentTimeMillis);
        long leaseBefore = owner().leaseUntil();

        assertThat(loginClaim(login)).isEqualTo(new ClaimResult.Held(6));
        // 没夺到就什么也没改：epoch、释放标记、租约都原样
        assertThat(owner()).isEqualTo(new OwnerState(6, false, leaseBefore));

        // 运维续约之后 login 再试，仍是 Held(6)；运维手里的 epoch 没丢
        ownership.renew();
        assertThat(ownership.epochOf(P)).hasValue(6);
        assertThat(owner().leaseUntil()).isGreaterThanOrEqualTo(leaseBefore);
        assertThat(loginClaim(login)).isEqualTo(new ClaimResult.Held(6));

        // 运维收尾释放（墓碑先于释放）之后 login 才夺到下一个 epoch
        ownership.release(P);
        assertThat(calls).containsExactly("tombstone:1001:6:released=false");
        assertThat(loginClaim(login)).isEqualTo(new ClaimResult.Claimed(7));
        // 反过来：玩家已经进了游戏（epoch 7 被持有），运维缺省 reject 看到的是在线、不发让出请求
        assertThat(ownership.claim(P, false, Duration.ofSeconds(1), OwnershipFenceSqlTest::noPause))
                .isEqualTo(new Claim.Online(7));
        assertThat(calls).hasSize(1);
        assertThat(ownership.heldCount()).isZero();
    }

    @Test
    void 严格递增时钟_同一毫秒也不重复() {
        StrictClock clock = new StrictClock(() -> 1000);
        assertThat(clock.getAsLong()).isEqualTo(1000);
        assertThat(clock.getAsLong()).isEqualTo(1001);
        assertThat(clock.getAsLong()).isEqualTo(1002);
    }
}
