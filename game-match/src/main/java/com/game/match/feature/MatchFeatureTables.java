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
import com.game.match.feature.pb.MatchTournamentCheerRow;
import com.game.match.feature.pb.MatchTournamentInviteRow;
import com.game.match.feature.pb.MatchTournamentMatchRow;
import com.game.match.feature.pb.MatchTournamentMemberRow;
import com.game.match.feature.pb.MatchTournamentRewardRow;
import com.game.match.feature.pb.MatchTournamentSeasonRow;
import com.game.match.feature.pb.MatchTournamentSquadRow;
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
    public static final String TOURNAMENT_SEASON = "match_tournament_season";
    public static final String TOURNAMENT_SQUAD = "match_tournament_squad";
    public static final String TOURNAMENT_MEMBER = "match_tournament_member";
    public static final String TOURNAMENT_INVITE = "match_tournament_invite";
    public static final String TOURNAMENT_MATCH = "match_tournament_match";
    public static final String TOURNAMENT_CHEER = "match_tournament_cheer";
    public static final String TOURNAMENT_REWARD = "match_tournament_reward";

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
            MatchRankedBattleRow.getDefaultInstance(),
            MatchTournamentSeasonRow.getDefaultInstance(),
            MatchTournamentSquadRow.getDefaultInstance(),
            MatchTournamentMemberRow.getDefaultInstance(),
            MatchTournamentInviteRow.getDefaultInstance(),
            MatchTournamentMatchRow.getDefaultInstance(),
            MatchTournamentCheerRow.getDefaultInstance(),
            MatchTournamentRewardRow.getDefaultInstance());

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
