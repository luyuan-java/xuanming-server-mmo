package com.game.match.rating;

import org.junit.jupiter.api.BeforeEach;

class BattleCompletionStoreTest extends BattleCompletionStoreCases {
    @BeforeEach void open()throws Exception {
        data=RatingTestDatabase.h2DataSource("battle-completion-"+java.util.UUID.randomUUID());
        try(var c=data.getConnection();var s=c.createStatement()) {
            for(var table:BattleCompletionStore.registry().tables())s.execute(RatingTestDatabase.h2Ddl(table.createTableSql()));
        }
        RatingTestDatabase.initializeRouteAuthority(data);store=new BattleCompletionStore(data);
    }
}
