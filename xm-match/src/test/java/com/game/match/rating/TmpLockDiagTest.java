package com.game.match.rating;

import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.support.MatchModes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** 临时诊断（不提交）。 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
class TmpLockDiagTest {

    private static final String LOCK_WAITERS = "SELECT COUNT(*) FROM information_schema.innodb_trx t JOIN information_schema.processlist p"
            + " ON p.id = t.trx_mysql_thread_id WHERE t.trx_state = 'LOCK WAIT' AND p.db = DATABASE()";

    @Test
    void 看一眼() throws Exception {
        try (RatingTestDatabase db = RatingTestDatabase.mysql(10)) {
            db.update("TRUNCATE TABLE match_rating");
            db.update("TRUNCATE TABLE match_rating_applied");
            db.putRating(101, 160_000, 0);
            db.putRating(102, 140_000, 0);
            FaultyDataSource ds = new FaultyDataSource(db.dataSource);
            RatingStore store = new RatingStore(RatingStore.connections(ds), c -> 30,
                    new MatchMetrics(new SimpleMeterRegistry(), new MetricLabels(id -> false)), () -> 1_800_000_000_000L);
            try (Connection rival = db.dataSource.getConnection(); ExecutorService pool = Executors.newSingleThreadExecutor()) {
                rival.setAutoCommit(false);
                for (long junk = 1000; junk < 1300; junk++) {
                    db.putRating(junk, 150_000, 0);
                }
                try (java.sql.Statement st = rival.createStatement()) {
                    System.out.println("DIAG heavy=" + st.executeUpdate("UPDATE match_rating SET games = games + 1 WHERE player_id >= 1000"));
                }
                try (PreparedStatement ps = rival.prepareStatement("SELECT rating_centi FROM match_rating WHERE player_id = ? FOR UPDATE")) {
                    ps.setLong(1, 102);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                    }
                }
                long t0 = System.nanoTime();
                Future<?> waiter = pool.submit(() -> {
                    try {
                        return store.apply(RatingStoreCases.event(9931, MatchModes.ONE_V_ONE, 1, List.of(101L), List.of(102L)).build());
                    } catch (Throwable e) {
                        System.out.println("DIAG apply threw at " + (System.nanoTime() - t0) / 1_000_000 + " ms: " + e + " cause=" + e.getCause());
                        throw e;
                    }
                });
                for (int i = 0; i < 25; i++) {
                    long count = db.query(LOCK_WAITERS, rs -> {
                        rs.next();
                        return rs.getLong(1);
                    });
                    String trx = db.query("SELECT GROUP_CONCAT(CONCAT(trx_state, '#', trx_mysql_thread_id) SEPARATOR ',') FROM information_schema.innodb_trx",
                            rs -> {
                                rs.next();
                                return rs.getString(1);
                            });
                    String procs = db.query("SELECT GROUP_CONCAT(CONCAT(id, '#', IFNULL(db, 'NULL'), '#', command) SEPARATOR ',') FROM information_schema.processlist",
                            rs -> {
                                rs.next();
                                return rs.getString(1);
                            });
                    System.out.println("DIAG t=" + (System.nanoTime() - t0) / 1_000_000 + " ms joined=" + count + " calls=" + ds.calls.size()
                            + " done=" + waiter.isDone() + " trx=" + trx + " procs=" + procs);
                    Thread.sleep(200);
                }
                rival.rollback();
                try {
                    System.out.println("DIAG result " + waiter.get());
                } catch (Exception e) {
                    System.out.println("DIAG future failed " + e);
                }
            }
        }
    }
}
