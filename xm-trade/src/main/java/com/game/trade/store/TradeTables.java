package com.game.trade.store;

import com.game.pbmysql.PbMysql;
import com.game.trade.store.pb.TradeFavoriteRow;
import com.game.trade.store.pb.TradeListingRow;
import com.google.protobuf.Message;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;

/**
 * 聚宝斋两张表的登记与建表（基线 data.Tables，tables.go:19-31 的前两项；trade-spec §1.1、§5.5、§5.10 第 2 步）。表定义是 Java 自有的
 * {@code xm/trade/trade_tables.proto}，不依赖同步来的 trade_table.proto；DDL 与 Go proto2mysql 逐字节相同（TradeTablesTest 对拍）。
 *
 * <p>与基线的差异（T2）：表建在 {@code xm_java}，不建独占库；没有 {@code -migrate} / Job / AutoMigrate / 只读 plan（trade.go:61-66、:364-395），
 * 启动期在自动提交连接上 {@link PbMysql#syncAll}（{@code GET_LOCK} 保护、只扩不缩、会<b>补建</b>缺失的普通索引；结构漂移拒启，同 guild D2）。
 * 4.7 只建这两张；P2 资产通道的 trade_player_op_seq / trade_asset_op 不建（§1.10）。
 */
public final class TradeTables {

    /** trade_listing（商品）。 */
    public static final String LISTING = "trade_listing";
    /** trade_favorite（收藏）。 */
    public static final String FAVORITE = "trade_favorite";

    /** 表名，与 {@link #PROTOTYPES} 一一对应。手写 SQL 里的表名字面量必须与这里（= trade_tables.proto 的 table_name）一致，TradeTablesTest 钉住。 */
    public static final List<String> NAMES = List.of(LISTING, FAVORITE);

    /** 表消息原型（登记顺序同基线 Tables()）。 */
    public static final List<Message> PROTOTYPES = List.of(TradeListingRow.getDefaultInstance(), TradeFavoriteRow.getDefaultInstance());

    private TradeTables() {
    }

    /** 登记了两张表的 pbmysql 实例（不碰库）。 */
    public static PbMysql registry() {
        PbMysql db = new PbMysql();
        for (Message prototype : PROTOTYPES) {
            db.register(prototype);
        }
        return db;
    }

    /**
     * 在一条自动提交的连接上建表 / 只扩不缩地同步（整轮持 pbmysql 的咨询锁；结构漂移抛异常，启动失败）。
     * 连接串上给请求流量的 socketTimeout 不够等咨询锁加 DDL：这条连接上临时放宽到 {@code networkTimeout}，用完还原（同 GuildTables.sync）。
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
