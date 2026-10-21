package com.game.guild.activity;

import com.game.guild.activity.pb.*;
import com.game.pbmysql.PbMysql;
import com.game.pbmysql.TableOption;
import com.game.proto.guild.GuildTrialLaunchRecord;
import com.game.proto.guild.GuildTrialLaunchRoleRecord;
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
        registerTrialAuthority(sql);
        return sql;
    }
    /** 主契约私有消息不依赖本版表proto；显式代码选项复用既有PB映射，不手写并行表结构。 */
    public static void registerTrialAuthority(PbMysql sql) {
        sql.register(GuildTrialLaunchRecord.getDefaultInstance(),TableOption.withTableName("guild_trial_launch"),
                TableOption.withPrimaryKey("request_id"),TableOption.withIndexes("state,updated_ms","battle_id"),
                TableOption.withTiDBNonclusteredPK(),TableOption.withTiDBShardRowIDBits(4),TableOption.withTiDBPreSplitRegions(4));
        sql.register(GuildTrialLaunchRoleRecord.getDefaultInstance(),TableOption.withTableName("guild_trial_launch_role"),
                TableOption.withPrimaryKey("player_id","request_id"),TableOption.withIndexes("request_id,player_id"),
                TableOption.withTiDBNonclusteredPK(),TableOption.withTiDBShardRowIDBits(4),TableOption.withTiDBPreSplitRegions(4));
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
