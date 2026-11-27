package com.game.match.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.match.global.GlobalRankProperties;
import com.game.match.global.RatingRankOutbox;
import com.game.match.script.BattleScriptStore;
import com.game.proto.TipInfoMessage;
import com.game.proto.rank.RankAdminResponse;
import com.game.proto.rank.RemoveRankEntryRequest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * 账号注销 match 域收尾（L2752）：删评分、删战斗剧本与选用（保留审核流水）、同事务写待摘评级榜发件箱并异步调 RemoveRankEntry 摘除。
 */
class MatchRolePurgeH2Test {

    private static JdbcDataSource newH2() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:match_purge_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE match_rating (player_id BIGINT UNSIGNED PRIMARY KEY, rating_centi BIGINT NOT NULL, games BIGINT NOT NULL, updated_at_ms BIGINT NOT NULL)");
            st.execute("CREATE TABLE match_ranked_player_season (season_id INT NOT NULL, player_id BIGINT UNSIGNED NOT NULL, PRIMARY KEY (season_id, player_id))");
            st.execute("CREATE TABLE match_global_rank_source (source_epoch BIGINT UNSIGNED PRIMARY KEY, produced_cursor BIGINT UNSIGNED NOT NULL, committed_cursor BIGINT UNSIGNED NOT NULL, board_id BIGINT UNSIGNED NOT NULL, stopped BOOLEAN NOT NULL)");
            st.execute("CREATE TABLE match_battle_script (script_id BIGINT UNSIGNED PRIMARY KEY, owner_player_id BIGINT UNSIGNED NOT NULL, revision INT NOT NULL, content BLOB, review_state INT NOT NULL, approved_revision INT NOT NULL, approved_content BLOB, reject_reason VARCHAR(64), submitted_ms BIGINT UNSIGNED NOT NULL, reviewed_ms BIGINT UNSIGNED NOT NULL)");
            st.execute("CREATE TABLE match_battle_script_loadout (player_id BIGINT UNSIGNED NOT NULL, match_mode INT NOT NULL, battle_config_id INT NOT NULL, origin INT NOT NULL, official_script_id INT NOT NULL, player_script_id BIGINT UNSIGNED NOT NULL, updated_ms BIGINT UNSIGNED NOT NULL, PRIMARY KEY (player_id, match_mode, battle_config_id))");
            st.execute("CREATE TABLE match_battle_script_review (script_id BIGINT UNSIGNED NOT NULL, revision INT NOT NULL, owner_player_id BIGINT UNSIGNED NOT NULL, approved BOOLEAN NOT NULL, reviewer VARCHAR(32) NOT NULL, reason VARCHAR(64) NOT NULL, reviewed_ms BIGINT UNSIGNED NOT NULL, content BLOB, PRIMARY KEY (script_id, revision))");
            st.execute("CREATE TABLE route_meta (id INT PRIMARY KEY, generation VARCHAR(64) NOT NULL, writable INT NOT NULL, fence_shards INT NOT NULL)");
            st.execute("INSERT INTO route_meta VALUES (1, '" + UUID.randomUUID() + "', 1, "
                    + com.game.common.player.PlayerRouteAuthority.FENCE_SHARDS + ")");
            RatingRankOutbox.ensureRankRemovalTable(c);
        }
        return ds;
    }

    @Test
    void 注销删评分与剧本及选用_保留审核流水_登记待摘评级榜并经RemoveRankEntry异步摘除() throws Exception {
        JdbcDataSource ds = newH2();
        long victim = 9001L;
        long other = 9002L;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO match_rating VALUES (9001, 165000, 10, 1000), (9002, 150000, 1, 1000)");
            st.execute("INSERT INTO match_ranked_player_season VALUES (3, 9001), (3, 9002)");
            st.execute("INSERT INTO match_global_rank_source VALUES (1, 5, 5, 777, FALSE), (10003, 2, 2, 888, FALSE)");
            st.execute("INSERT INTO match_battle_script VALUES (501, 9001, 1, X'01', 1, 1, X'01', '', 1000, 1000), (502, 9002, 1, X'02', 1, 1, X'02', '', 1000, 1000)");
            st.execute("INSERT INTO match_battle_script_loadout VALUES (9001, 1, 10, 2, 0, 501, 1000), (9002, 1, 10, 2, 0, 502, 1000)");
            st.execute("INSERT INTO match_battle_script_review VALUES (501, 1, 9001, TRUE, 'ops', '', 1000, X'01')");
        }

        // 执行删角清理事务
        MatchInternalServiceImpl.purgeRemovedRoleInTx(ds, victim, 5_000L);

        try (Connection c = ds.getConnection()) {
            // 1. 评分、剧本、选用已清，其他玩家无影响
            assertThat(count(c, "SELECT COUNT(*) FROM match_rating WHERE player_id=?", victim)).isZero();
            assertThat(count(c, "SELECT COUNT(*) FROM match_rating WHERE player_id=?", other)).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM match_battle_script WHERE owner_player_id=?", victim)).isZero();
            assertThat(count(c, "SELECT COUNT(*) FROM match_battle_script WHERE owner_player_id=?", other)).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM match_battle_script_loadout WHERE player_id=?", victim)).isZero();
            assertThat(count(c, "SELECT COUNT(*) FROM match_battle_script_loadout WHERE player_id=?", other)).isEqualTo(1);
            // 2. 审核流水按审计保留，绝不随删角删除
            assertThat(count(c, "SELECT COUNT(*) FROM match_battle_script_review WHERE owner_player_id=?", victim)).isEqualTo(1);
            // 3. 待摘评级榜表写入了永久榜(1)与赛季3(10003)两行
            assertThat(count(c, "SELECT COUNT(*) FROM match_rank_removal WHERE player_id=?", victim)).isEqualTo(2);
        }

        // 4. 投榜循环失败时保留发件箱行，成功时发 RemoveRankEntryRequest 并清理发件箱行
        try (RatingRankOutbox outbox = new RatingRankOutbox(ds,
                new GlobalRankProperties("127.0.0.1", 20880, "0123456789abcdef0123456789abcdef"))) {
            assertThatThrownBy(() -> outbox.publishPendingRankRemovals(req -> RankAdminResponse.newBuilder()
                    .setErrorMessage(TipInfoMessage.newBuilder().setId(1003)).build()))
                    .isInstanceOf(IllegalStateException.class);
            try (Connection c = ds.getConnection()) {
                assertThat(count(c, "SELECT COUNT(*) FROM match_rank_removal WHERE player_id=?", victim)).isEqualTo(2);
            }

            List<RemoveRankEntryRequest> sent = new ArrayList<>();
            int processed = outbox.publishPendingRankRemovals(req -> {
                sent.add(req);
                return RankAdminResponse.getDefaultInstance();
            });
            assertThat(processed).isEqualTo(2);
            assertThat(sent).extracting(RemoveRankEntryRequest::getBoardId).containsExactlyInAnyOrder(777L, 888L);
            assertThat(sent).allSatisfy(r -> {
                assertThat(r.getEntityId()).isEqualTo(victim);
                assertThat(r.getRequestId()).isNotZero();
            });
            try (Connection c = ds.getConnection()) {
                assertThat(count(c, "SELECT COUNT(*) FROM match_rank_removal WHERE player_id=?", victim)).isZero();
            }
        }
    }

    private static int count(Connection c, String sql, long param) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
