package com.game.guild.activity;

import com.game.guild.activity.pb.*;
import com.game.pbmysql.PbMysql;
import java.sql.SQLException;
import javax.sql.DataSource;

/** 本版自有活动表，沿同一pbmysql建表/扩展接缝；不修改既有帮会表。 */
public final class ActivityTables {
    private ActivityTables() {}
    public static PbMysql registry() {
        var sql = new PbMysql();
        sql.register(ActivityProgressRow.getDefaultInstance());
        sql.register(ActivitySlotRow.getDefaultInstance());
        sql.register(TrialLobbyRow.getDefaultInstance());
        sql.register(TrialBattleRow.getDefaultInstance());
        sql.register(ActivityRewardRow.getDefaultInstance());
        sql.register(ActivityPoisonRow.getDefaultInstance());
        return sql;
    }
    public static PbMysql sync(DataSource dataSource) throws SQLException {
        var sql = registry();
        try (var connection = dataSource.getConnection()) {
            int old = connection.getNetworkTimeout();
            try {
                connection.setNetworkTimeout(Runnable::run, 120000);
                sql.syncAll(connection);
            } finally {
                connection.setNetworkTimeout(Runnable::run, old);
            }
        }
        return sql;
    }
}
