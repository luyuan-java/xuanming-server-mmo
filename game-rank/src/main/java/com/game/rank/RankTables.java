package com.game.rank;
import com.game.pbmysql.PbMysql;
import com.game.rank.store.pb.*;
import com.google.protobuf.Message;
import java.sql.SQLException;
import javax.sql.DataSource;
/** Java独立领域表的唯一登记，源为模块内proto。 */
public final class RankTables {
    public static PbMysql registry() {
        var db = new PbMysql();
        for (Message value : java.util.List.<Message>of(RankIdRow.getDefaultInstance(), RankBoardRow.getDefaultInstance(),
                     RankScoreRow.getDefaultInstance(), RankInputReceiptRow.getDefaultInstance(), RankSettlementRow.getDefaultInstance(),
                     RankSnapshotRow.getDefaultInstance(), RankMailRewardRow.getDefaultInstance(), RankMutationRow.getDefaultInstance()))
            db.register(value);
        return db;
    }
    public static PbMysql sync(DataSource dataSource) throws SQLException {
        var db = registry();
        try (var connection = dataSource.getConnection()) {
            int timeout = connection.getNetworkTimeout();
            try {
                connection.setNetworkTimeout(Runnable::run, 120000);
                connection.setAutoCommit(true);
                db.syncAll(connection);
            } finally {
                connection.setNetworkTimeout(Runnable::run, timeout);
            }
        }
        return db;
    }
}
