package com.game.data.merge;

import com.game.data.merge.pb.ZoneMergeRunRow;
import com.game.data.merge.pb.ZoneMergeItemRow;
import com.game.pbmysql.PbMysql;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;

/** 只同步正规表结构；不建可写meta，不建立维护原件。 */
public final class ZoneMergeTables {
    private ZoneMergeTables(){}
    public static PbMysql registry(){var db=new PbMysql();db.register(ZoneMergeRunRow.getDefaultInstance());db.register(ZoneMergeItemRow.getDefaultInstance());return db;}
    public static PbMysql sync(DataSource source)throws SQLException{
        var db=registry();try(var c=source.getConnection()){com.game.data.ops.OpsTables.sync(db,c,Duration.ofMinutes(2));}return db;
    }
}
