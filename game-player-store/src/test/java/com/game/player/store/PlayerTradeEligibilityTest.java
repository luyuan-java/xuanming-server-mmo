package com.game.player.store;

import static org.junit.jupiter.api.Assertions.*;
import com.game.player.store.PlayerTradeStore.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 生产资格回调与 beginFreeze 同事务，逐次拒绝必须整笔回滚，不能遗留半个锁或收据。 */
class PlayerTradeEligibilityTest {
    @Test void 缺资格验证器拒绝_有帮派或未终局合同也拒绝_清理后才可冻结() throws Exception {
        try (var f = new PlayerTradeStoreTest.Fixture()) {
            f.player(1, "seller");
            f.sql.execute("CREATE TABLE trade_commerce(commerce_id BIGINT PRIMARY KEY,seller_player_id BIGINT NOT NULL,buyer_player_id BIGINT NOT NULL,status INT NOT NULL)");
            f.sql.execute("CREATE TABLE guild_player_state(player_id BIGINT PRIMARY KEY)");
            f.sql.execute("CREATE TABLE guild_member(player_id BIGINT PRIMARY KEY,guild_id BIGINT NOT NULL)");
            var request = new FreezeRequest(101, 1, "seller", 1); var timeout = Duration.ofSeconds(5);
            var missing = new PlayerTradeStore(f.source, f.manager, f.clock::get, 5, null);
            assertEquals(Code.NOT_READY, assertThrows(Failure.class, () -> missing.beginFreeze(request, timeout)).code());
            assertEquals(0, f.count("player_trade_freeze")); assertEquals(0, f.row(1).getTradeListingId());
            var eligibility = new PlayerStoreAutoConfiguration().playerTradeFreezeEligibility(true);
            var actual = new PlayerTradeStore(f.source, f.manager, f.clock::get, 5, null, eligibility);
            f.sql.update("INSERT INTO guild_player_state VALUES(1)"); f.sql.update("INSERT INTO guild_member VALUES(1,77)");
            assertEquals(Code.CONFLICT, assertThrows(Failure.class, () -> actual.beginFreeze(request, timeout)).code());
            assertEquals(0, f.count("player_trade_freeze")); f.sql.update("DELETE FROM guild_member");
            f.sql.update("INSERT INTO trade_commerce VALUES(99,1,0,4)");
            assertEquals(Code.NOT_READY, assertThrows(Failure.class, () -> actual.beginFreeze(request, timeout)).code());
            assertEquals(0, f.count("player_trade_freeze")); assertEquals(0, f.row(1).getTradeListingId());
            f.sql.update("UPDATE trade_commerce SET status=11");
            assertEquals(Status.FREEZING, actual.beginFreeze(request, timeout).status());
            assertEquals(101, f.row(1).getTradeListingId());
        }
    }
    @Test void 关闭离帮规则仍不允许未决买家资产_本角色寄售行不自阻塞() throws Exception {
        try (var f = new PlayerTradeStoreTest.Fixture()) {
            f.player(1, "seller");
            f.sql.execute("CREATE TABLE trade_commerce(commerce_id BIGINT PRIMARY KEY,seller_player_id BIGINT NOT NULL,buyer_player_id BIGINT NOT NULL,status INT NOT NULL)");
            var eligibility = new PlayerStoreAutoConfiguration().playerTradeFreezeEligibility(false);
            var store = new PlayerTradeStore(f.source, f.manager, f.clock::get, 5, null, eligibility);
            var request = new FreezeRequest(101, 1, "seller", 1); var timeout = Duration.ofSeconds(5);
            f.sql.update("INSERT INTO trade_commerce VALUES(99,2,1,2)");
            assertEquals(Code.NOT_READY, assertThrows(Failure.class, () -> store.beginFreeze(request, timeout)).code());
            f.sql.update("DELETE FROM trade_commerce"); f.sql.update("INSERT INTO trade_commerce VALUES(101,1,0,3)");
            assertEquals(Status.FREEZING, store.beginFreeze(request, timeout).status());
        }
    }
}
