package com.game.guild.store;

import com.game.guild.store.pb.GuildApplicationRow;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildDailyCounterRow;
import com.game.guild.store.pb.GuildMemberRow;
import com.game.guild.store.pb.GuildPlayerOpSeqRow;
import com.game.guild.store.pb.GuildPlayerStateRow;
import com.game.guild.store.pb.GuildRow;
import com.game.pbmysql.PbMysql;
import com.google.protobuf.Message;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;

/**
 * 帮会四张核心表与 4.5 资产三表的登记与建表（guild-spec §1.2、§7.5、§7.11 第 2 步；guild-economy-spec §7.7 第 2 步）。表定义是 Java 自有的 {@code xm/guild/guild_tables.proto}，
 * 不依赖同步来的 guild_db.proto；DDL 与 Go proto2mysql 逐字节相同（GuildTablesTest 对拍）。
 *
 * <p>与基线的差异（D2 的一部分）：pbmysql 的同步只扩不缩、会<b>补建</b>缺失的普通索引，基线 schemamigrate 遇到缺索引拒启；
 * 结构漂移（需要 MODIFY / DROP 才能对齐）同样拒启。
 */
public final class GuildTables {

    /** guild_player_op_seq（Q，锁序位置 5；guild-economy-spec §1.1）。 */
    public static final String PLAYER_OP_SEQ = "guild_player_op_seq";
    /** guild_asset_op（O，锁序位置 6）。 */
    public static final String ASSET_OP = "guild_asset_op";
    /** guild_daily_counter（C，锁序位置 7）。 */
    public static final String DAILY_COUNTER = "guild_daily_counter";

    /**
     * 表名，顺序即全库表间锁序 G &lt; S &lt; M &lt; A &lt; Q &lt; O &lt; C（tables.go:15-21）。4.6 往后追加 P。
     * 手写 SQL 里的表名字面量必须与这里（= guild_tables.proto 的 table_name）一致，GuildTablesTest 钉住。
     */
    public static final List<String> NAMES = List.of("guild", "guild_player_state", "guild_member", "guild_application",
            PLAYER_OP_SEQ, ASSET_OP, DAILY_COUNTER);

    /** 与 {@link #NAMES} 一一对应的表消息原型。 */
    public static final List<Message> PROTOTYPES = List.of(GuildRow.getDefaultInstance(),
            GuildPlayerStateRow.getDefaultInstance(), GuildMemberRow.getDefaultInstance(),
            GuildApplicationRow.getDefaultInstance(), GuildPlayerOpSeqRow.getDefaultInstance(),
            GuildAssetOpRow.getDefaultInstance(), GuildDailyCounterRow.getDefaultInstance());

    private GuildTables() {
    }

    /** 登记了全部七张表的 pbmysql 实例（不碰库）。 */
    public static PbMysql registry() {
        PbMysql db = new PbMysql();
        for (Message prototype : PROTOTYPES) {
            db.register(prototype);
        }
        return db;
    }

    /**
     * 在一条自动提交的连接上建表 / 只扩不缩地同步（整轮持 pbmysql 的咨询锁；结构漂移抛异常，启动失败）。
     * 连接串上给请求流量的 socketTimeout（4 s）不够等 30 s 的咨询锁加 DDL：这条连接上临时放宽到 {@code networkTimeout}，用完还原
     * （同 FriendConfiguration.friendTables）。
     */
    public static PbMysql sync(DataSource dataSource, Duration networkTimeout) throws SQLException {
        PbMysql db = registry();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            int socketTimeout = c.getNetworkTimeout();
            c.setNetworkTimeout(Runnable::run, (int) networkTimeout.toMillis());
            try {
                db.syncAll(c);
            } finally {
                if (!c.isClosed()) {
                    c.setNetworkTimeout(Runnable::run, socketTimeout);
                }
            }
        }
        return db;
    }
}
