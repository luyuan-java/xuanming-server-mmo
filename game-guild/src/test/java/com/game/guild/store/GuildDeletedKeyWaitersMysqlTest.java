package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** 解散删除已锁帮会后，已开始的等待者都应观察到帮会不存在。 */
@EnabledIfSystemProperty(named = "game.it.mysql", matches = ".+")
class GuildDeletedKeyWaitersMysqlTest {
    private static GuildMysqlFixture db;

    @BeforeAll static void create() throws SQLException { db = GuildMysqlFixture.create(); }
    @AfterAll static void close() throws SQLException { if (db != null) db.close(); }

    private record Result(long started, long ended, int rows, int code, String state, String phase) { }

    @Test void 删除已锁帮会后两个等待者都应看到不存在() throws Exception {
        for (int round = 0; round < 3; round++) {
            long guild = 990_000L + round;
            db.seedNamedGuild(guild, 991_000L + round, "deleted-waiter-" + round, 2);
            var pool = Executors.newFixedThreadPool(2);
            CountDownLatch entered = new CountDownLatch(2);
            try (Connection holder = db.begin()) {
                GuildJdbc holding = new GuildJdbc(holder, Deadline.after(2500), 10);
                assertThat(holding.exists(JdbcGuildStore.LOCK_GUILD, guild)).isTrue();
                List<Future<Result>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) futures.add(pool.submit(() -> waiter(guild, entered)));
                assertThat(entered.await(500, TimeUnit.MILLISECONDS)).isTrue();
                Thread.sleep(100); // 沿用原诊断编排；不是服务端排队证据。
                assertThat(holding.update(JdbcGuildStore.DELETE_GUILD, guild)).isEqualTo(1);
                long commitStart = System.nanoTime();
                holder.commit();
                for (Future<Result> future : futures) {
                    Result r = future.get(3, TimeUnit.SECONDS);
                    assertThat(r.started()).as("调用必须在 holder 提交前发起").isLessThan(commitStart);
                    assertThat(r.ended()).as("等待调用必须跨越 holder 开始提交").isGreaterThanOrEqualTo(commitStart);
                    assertThat(r.code()).as("阶段 %s SQLState %s", r.phase(), r.state()).isZero();
                    assertThat(r.rows()).isZero();
                }
                assertThat(db.guildRows(guild)).isZero();
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private static Result waiter(long guild, CountDownLatch entered) {
        Connection c = null;
        long start = 0, end = 0;
        int rows = -1, code = 0;
        String state = "-", phase = "-";
        try {
            c = db.begin();
            GuildJdbc sql = new GuildJdbc(c, Deadline.after(1500), 10);
            start = System.nanoTime();
            entered.countDown();
            rows = sql.exists(JdbcGuildStore.LOCK_GUILD, guild) ? 1 : 0;
            end = System.nanoTime();
        } catch (SQLException failure) {
            end = System.nanoTime();
            code = failure.getErrorCode();
            state = safeState(failure);
            phase = "begin_or_G1";
            // 零错误码的 SQLException 仍必须失败。
            if (code == 0) code = -1;
        } finally {
            if (c != null) {
                try { c.rollback(); }
                catch (SQLException failure) {
                    if (code == 0) { code = failure.getErrorCode(); state = safeState(failure); phase = "rollback"; if (code == 0) code = -1; }
                }
                try { c.close(); }
                catch (SQLException failure) {
                    if (code == 0) { code = failure.getErrorCode(); state = safeState(failure); phase = "close"; if (code == 0) code = -1; }
                }
            }
        }
        return new Result(start, end, rows, code, state, phase);
    }

    private static String safeState(SQLException failure) {
        String state = failure.getSQLState();
        return state != null && state.matches("[A-Za-z0-9]{1,8}") ? state : "-";
    }
}
