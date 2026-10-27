package com.game.match.rating;

import com.game.common.sql.testing.AuthoritySqlFixture;
import com.game.discovery.sql.AuthorityDataSourcePolicy;

import com.alibaba.druid.pool.DruidDataSource;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 仅显式开启本机专用 TiDB；每用例随机库，清理仅限本用例库。 */
@EnabledIfEnvironmentVariable(named="MATCH_IT_TIDB",matches=".+")
class BattleCompletionStoreTiDbTest extends BattleCompletionStoreCases {
    private String database;
    private String jdbcRoot;
    private AuthoritySqlFixture fixture;
    @BeforeEach void open()throws Exception {
        jdbcRoot=RatingTestDatabase.tidbRoot(System.getenv("MATCH_IT_TIDB"));
        fixture=AuthoritySqlFixture.prepare(AuthoritySqlFixture.Options.fromEnvironment(jdbcRoot,RatingTestDatabase.MYSQL_USER,
                RatingTestDatabase.MYSQL_PASSWORD,"jcompletion",false,List.of(1)),(connection,principal)->{
            BattleCompletionStore.registry().initializeFresh(connection,principal);
            try(var statement=connection.createStatement()){
                statement.execute("CREATE TABLE player(player_id BIGINT PRIMARY KEY,zone_id INT NOT NULL DEFAULT 1,name VARCHAR(128) NOT NULL DEFAULT '',level INT NOT NULL DEFAULT 1,class_id INT NOT NULL DEFAULT 0,gender INT NOT NULL DEFAULT 0,appearance_id VARCHAR(128) NOT NULL DEFAULT '')");
            }
        });
        database=fixture.database();
        var pool=new DruidDataSource();data=pool;
        pool.setUrl(fixture.jdbcUrl("?useSSL=false&connectTimeout=3000&socketTimeout=4000"));
        pool.setUsername(fixture.normalUser());pool.setPassword(fixture.normalPassword());pool.setMaxActive(8);pool.setMaxWait(3000);
        AuthorityDataSourcePolicy.protectDruid(pool,fixture.writerBoot(),true);
        BattleCompletionStore.sync(data);store=new BattleCompletionStore(data);
    }
    java.sql.Connection schemaConnection()throws java.sql.SQLException{return fixture.schemaConnection();}
    @AfterEach void close()throws Exception {
        if(data instanceof DruidDataSource pool)pool.close();
        if(fixture!=null)fixture.close();
    }
}
