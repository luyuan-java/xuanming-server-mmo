package com.game.data.ops.fence;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.data.metrics.DataMetrics;
import com.game.data.ops.fence.AdminOwnership.Claim;
import com.game.data.testing.DataSqlFixture;
import com.game.player.store.OwnerState;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerStore.HandOffResult;
import com.game.player.store.state.PlayerState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
    void 严格递增时钟_同一毫秒也不重复() {
        StrictClock clock = new StrictClock(() -> 1000);
        assertThat(clock.getAsLong()).isEqualTo(1000);
        assertThat(clock.getAsLong()).isEqualTo(1001);
        assertThat(clock.getAsLong()).isEqualTo(1002);
    }
}
