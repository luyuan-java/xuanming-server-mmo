package com.game.mail;
import com.game.mail.store.pb.*;
import com.game.pbmysql.PbMysql;
import com.google.protobuf.Message;
import java.sql.SQLException;
import javax.sql.DataSource;
/** Java独立领域表的唯一登记，源为模块内proto。 */
public final class MailTables {
    public static PbMysql registry() {
        var db = new PbMysql();
        for (Message value : java.util.List.<Message>of(MailIdRow.getDefaultInstance(), MailRow.getDefaultInstance(),
                     MailCampaignRow.getDefaultInstance(), MailCampaignShardRow.getDefaultInstance(),
                     MailCampaignRecipientRow.getDefaultInstance(), MailBoxRow.getDefaultInstance(), MailStateRow.getDefaultInstance(),
                     MailPlayerOpSeqRow.getDefaultInstance(), MailAssetOpRow.getDefaultInstance()))
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
