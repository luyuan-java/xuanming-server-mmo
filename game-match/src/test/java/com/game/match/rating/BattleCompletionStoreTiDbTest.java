package com.game.match.rating;

import com.alibaba.druid.pool.DruidDataSource;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 仅显式开启本机专用 TiDB；每用例随机库，清理仅限本用例库。 */
@EnabledIfEnvironmentVariable(named="MATCH_IT_TIDB",matches="127\\.0\\.0\\.1:4000")
class BattleCompletionStoreTiDbTest extends BattleCompletionStoreCases {
    private String database;
    @BeforeEach void open()throws Exception {
        database="jcompletion_"+java.util.UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:4000/","root","");var s=c.createStatement()) {s.execute("CREATE DATABASE `"+database+"`");}
        var pool=new DruidDataSource();data=pool;
        pool.setUrl("jdbc:mysql://127.0.0.1:4000/"+database+"?useSSL=false&connectTimeout=3000&socketTimeout=4000");
        pool.setUsername("root");pool.setPassword("");pool.setMaxActive(8);pool.setMaxWait(3000);
        pool.setConnectionInitSqls(List.of("SET SESSION tidb_snapshot='',tidb_read_staleness=0,tidb_low_resolution_tso=OFF,tidb_read_consistency='strict',tidb_replica_read='leader',tidb_txn_mode='pessimistic',transaction_isolation='READ-COMMITTED'"));
        BattleCompletionStore.sync(data);store=new BattleCompletionStore(data);
    }
    @AfterEach void close()throws Exception {
        if(data instanceof DruidDataSource pool)pool.close();
        if(database!=null)try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:4000/","root","");var s=c.createStatement()) {s.execute("DROP DATABASE `"+database+"`");}
    }
}
