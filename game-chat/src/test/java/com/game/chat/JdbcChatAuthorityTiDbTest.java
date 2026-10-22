package com.game.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** root后续显式owned24000专项；缺环境的精确3项列未验，H2结果不替代这里的真实TiDB会话/锁。 */
class JdbcChatAuthorityTiDbTest {
    private static String endpoint() {
        String endpoint = System.getProperty("game.it.chat.tidb");
        if (Boolean.getBoolean("game.it.chat.require-tidb")) {
            assertNotNull(endpoint, "必需原生聊天专项缺game.it.chat.tidb");
            assertFalse(endpoint.isBlank(), "必需原生聊天专项缺owned endpoint");
        }
        assumeTrue(endpoint != null && !endpoint.isBlank(), "未配置本任务owned TiDB；原生聊天专项未验");
        return endpoint;
    }

    @Test void 真实TiDB同库原Session拒旧账号_合法换主保留角色历史与永久收据() throws Exception {
        try (var f = new ChatSqlFixture(JdbcChatAuthorityTest.rules(), endpoint())) {
            f.player(7, " A ", 7);
            var original = JdbcChatAuthorityTest.actor(" A ", 7);
            var request = JdbcChatAuthorityTest.request("native", "sql original", 99);
            assertEquals(0, JdbcChatAuthorityTest.send(f, original, request).tip());
            assertTrue(JdbcChatAuthorityTest.send(f, original, request).duplicate());
            f.sql.update("UPDATE player SET account='B',owner_revision=8 WHERE player_id=7");
            assertThrows(Exception.class, () -> JdbcChatAuthorityTest.send(f, original, request));
            assertThrows(Exception.class, () -> JdbcChatAuthorityTest.result(f.store.pull(original, 99, 20, Deadline.after(3500))));
            assertEquals("sql original", JdbcChatAuthorityTest.result(f.store.pull(JdbcChatAuthorityTest.actor("B", 8),
                    99, 20, Deadline.after(3500))).getFirst().getContent());
            assertThrows(Exception.class, () -> JdbcChatAuthorityTest.send(f, JdbcChatAuthorityTest.actor("B", 8), request));
            assertEquals(1, JdbcChatAuthorityTest.count(f));
        }
    }

    @Test void 真实TiDB同P等待换主_迟到发言在成功收据写入前拒绝() throws Exception {
        try (var f = new ChatSqlFixture(JdbcChatAuthorityTest.rules(), endpoint()); var connection = f.source.getConnection()) {
            f.player(7, "A", 7); connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT player_id FROM player WHERE player_id=7 FOR UPDATE")) {
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            var pending = f.store.send(JdbcChatAuthorityTest.actor("A", 7),
                    JdbcChatAuthorityTest.request("native", "late", 0), Deadline.after(3500));
            try (var statement = connection.prepareStatement("UPDATE player SET account='B',owner_revision=8 WHERE player_id=7")) {
                assertEquals(1, statement.executeUpdate());
            }
            connection.commit();
            assertThrows(Exception.class, () -> JdbcChatAuthorityTest.result(pending));
            assertEquals(0, JdbcChatAuthorityTest.count(f));
        }
    }

    @Test void 真实TiDB高uint64双向私聊并发_不依赖对端账号或Redis() throws Exception {
        try (var f = new ChatSqlFixture(JdbcChatAuthorityTest.rules(100, 200, 604800), endpoint())) {
            long high = Long.MIN_VALUE + 7;
            f.player(7, "A", 7); f.player(high, "H", 1);
            var lowSession = JdbcChatAuthorityTest.actor("A", 7);
            var highSession = SessionContext.newBuilder().setPlayerId(high).setAccount("H").setOwnerRevision(1).build();
            var pending = new ArrayList<CompletableFuture<?>>();
            for (int i = 0; i < 4; i++) {
                pending.add(f.store.send(lowSession, JdbcChatAuthorityTest.request("low" + i, "l" + i, high), Deadline.after(3500)));
                var highRequest = JdbcChatAuthorityTest.request("high" + i, "h" + i, 7).toBuilder()
                        .setMessage(JdbcChatAuthorityTest.request("high" + i, "h" + i, 7).getMessage().toBuilder().setSenderPlayerId(high)).build();
                pending.add(f.store.send(highSession, highRequest, Deadline.after(3500)));
            }
            for (var result : pending) JdbcChatAuthorityTest.result(result);
            assertEquals(8, JdbcChatAuthorityTest.count(f));
            assertEquals(8, JdbcChatAuthorityTest.result(f.store.pull(lowSession, high, 20, Deadline.after(3500))).size());
            assertEquals(8, JdbcChatAuthorityTest.result(f.store.pull(highSession, 7, 20, Deadline.after(3500))).size());
            f.sql.update("DELETE FROM player WHERE player_id=?", com.game.discovery.sql.AuthorityJdbc.u64(high));
            assertEquals(8, JdbcChatAuthorityTest.result(f.store.pull(lowSession, high, 20, Deadline.after(3500))).size());
        }
    }
}
