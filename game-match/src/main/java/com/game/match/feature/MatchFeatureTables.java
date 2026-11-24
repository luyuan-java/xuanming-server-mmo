package com.game.match.feature;

import com.game.match.feature.pb.MatchBattleReportRow;
import com.game.match.feature.pb.MatchBattleScriptLoadoutRow;
import com.game.match.feature.pb.MatchBattleScriptReviewRow;
import com.game.match.feature.pb.MatchBattleScriptRow;
import com.game.match.feature.pb.MatchPlayerBattleReportRow;
import com.game.match.feature.pb.MatchRankedBattleRow;
import com.game.match.feature.pb.MatchRankedPlayerPrefsRow;
import com.game.match.feature.pb.MatchRankedPlayerSeasonRow;
import com.game.match.feature.pb.MatchRankedRewardRow;
import com.game.match.feature.pb.MatchRankedSeasonRow;
import com.game.pbmysql.PbMysql;
import com.google.protobuf.Message;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;

/** 战报回放、战斗剧本与论剑排位 10 张权威表的 pbmysql 登记与同步。 */
public final class MatchFeatureTables {

    public static final String BATTLE_REPORT = "match_battle_report";
    public static final String PLAYER_BATTLE_REPORT = "match_player_battle_report";
    public static final String BATTLE_SCRIPT = "match_battle_script";
    public static final String BATTLE_SCRIPT_REVIEW = "match_battle_script_review";
    public static final String BATTLE_SCRIPT_LOADOUT = "match_battle_script_loadout";
    public static final String RANKED_SEASON = "match_ranked_season";
    public static final String RANKED_PLAYER_PREFS = "match_ranked_player_prefs";
    public static final String RANKED_PLAYER_SEASON = "match_ranked_player_season";
    public static final String RANKED_REWARD = "match_ranked_reward";
    public static final String RANKED_BATTLE = "match_ranked_battle";

    public static final List<Message> PROTOTYPES = List.of(
            MatchBattleReportRow.getDefaultInstance(),
            MatchPlayerBattleReportRow.getDefaultInstance(),
            MatchBattleScriptRow.getDefaultInstance(),
            MatchBattleScriptReviewRow.getDefaultInstance(),
            MatchBattleScriptLoadoutRow.getDefaultInstance(),
            MatchRankedSeasonRow.getDefaultInstance(),
            MatchRankedPlayerPrefsRow.getDefaultInstance(),
            MatchRankedPlayerSeasonRow.getDefaultInstance(),
            MatchRankedRewardRow.getDefaultInstance(),
            MatchRankedBattleRow.getDefaultInstance());

    @FunctionalInterface
    public interface SchemaSync {
        void sync(DataSource dataSource) throws SQLException;
    }

    static final Duration SCHEMA_SYNC_TIMEOUT = Duration.ofMinutes(2);
    public static final SchemaSync PBMYSQL = ds -> sync(ds, SCHEMA_SYNC_TIMEOUT);

    private MatchFeatureTables() {
    }

    public static PbMysql registry() {
        PbMysql db = new PbMysql();
        for (Message prototype : PROTOTYPES) {
            db.register(prototype);
        }
        return db;
    }

    public static PbMysql sync(DataSource dataSource, Duration networkTimeout) throws SQLException {
        PbMysql db = registry();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            int old = c.getNetworkTimeout();
            c.setNetworkTimeout(Runnable::run, (int) networkTimeout.toMillis());
            try {
                db.syncAll(c);
            } finally {
                if (!c.isClosed()) {
                    c.setNetworkTimeout(Runnable::run, old);
                }
            }
        }
        return db;
    }
}
