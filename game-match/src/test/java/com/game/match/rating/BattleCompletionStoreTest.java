package com.game.match.rating;

import org.junit.jupiter.api.BeforeEach;

class BattleCompletionStoreTest extends BattleCompletionStoreCases {
    @BeforeEach void open()throws Exception {
        data=RatingTestDatabase.h2DataSource("battle-completion-"+java.util.UUID.randomUUID());
        try(var c=RatingTestDatabase.h2SchemaDataSource(data).getConnection();var s=c.createStatement()) {
            for(var table:BattleCompletionStore.registry().tables())s.execute(RatingTestDatabase.h2Ddl(table.createTableSql()));
        }
        RatingTestDatabase.initializeRouteAuthority(data);store=new BattleCompletionStore(data);
    }
    @Override java.sql.Connection schemaConnection() throws java.sql.SQLException {
        return RatingTestDatabase.h2SchemaDataSource(data).getConnection();
    }
    @org.junit.jupiter.api.AfterEach void closeH2() throws Exception {
        if (data instanceof AutoCloseable fixture) fixture.close();
    }
}
