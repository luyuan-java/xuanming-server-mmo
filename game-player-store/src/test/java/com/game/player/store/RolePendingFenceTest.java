package com.game.player.store;

import static org.junit.jupiter.api.Assertions.*;

import com.game.player.store.PlayerTradeStore.*;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 角色交易真实SQL入口复核三类帮会资产；默认H2，可随既有game.it.mysql切换独占TiDB库。 */
class RolePendingFenceTest {
    @Test void 收件箱pending和未知状态拒冻结_明确最终结局才放行()throws Exception{
        try(var f=new PlayerTradeStoreTest.Fixture()){
            f.player(1,"seller");f.sql.update("INSERT INTO player_inbox_item(shard,player_id,state) VALUES(?,1,1)",com.game.common.inbox.InboxShards.rowShard(1));
            for(int state:new int[]{0,1,99}){f.sql.update("UPDATE player_inbox_item SET state=?",state);refused(f);}
            f.sql.update("UPDATE player_inbox_item SET state=5");assertEquals(Status.FREEZING,f.trade(5,true).beginFreeze(request(),BUDGET).status());
        }
    }
    @Test void 经济回执未消费或部分到账拒原身份变化_稳定寄售只允许保持owner的home搬迁()throws Exception{
        try(var f=new PlayerTradeStoreTest.Fixture()){
            f.player(1,"seller");f.sql.update("INSERT INTO trade_asset_op VALUES(1,1,2,0,0)");
            var tx=new org.springframework.transaction.support.TransactionTemplate(f.manager);
            java.util.function.Supplier<Boolean> blocked=()->tx.execute(s->new org.springframework.jdbc.core.JdbcTemplate(f.source).execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->{com.game.common.player.PlayerRouteAuthority.lock(c,java.util.List.of(1L),3);return RolePendingFence.economicBlocked(c,1);}));
            assertTrue(blocked.get());f.sql.update("UPDATE trade_asset_op SET business_consumed=1,receipt_ack_pending=1");assertTrue(blocked.get());
            f.sql.update("UPDATE trade_asset_op SET receipt_ack_pending=0,status=5");assertTrue(blocked.get());f.sql.update("UPDATE trade_asset_op SET status=2");assertFalse(blocked.get());
            f.sql.update("INSERT INTO trade_listing VALUES(17,1,2)");assertTrue(blocked.get());
            Boolean homeClear=tx.execute(s->new org.springframework.jdbc.core.JdbcTemplate(f.source).execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->{com.game.common.player.PlayerRouteAuthority.lock(c,java.util.List.of(1L),3);return !RolePendingFence.economicBlockedForHomeMove(c,1);}));
            assertTrue(Boolean.TRUE.equals(homeClear));
        }
    }
    @Test void 私有历练同意阻止冻结_明确终局才放行_过期未知和缺房间不猜安全()throws Exception{
        try(var f=new PlayerTradeStoreTest.Fixture()){
            f.player(1,"seller");f.sql.update("INSERT INTO guild_activity_slot VALUES(1,99,?)",Long.MAX_VALUE);refused(f);
            f.sql.update("INSERT INTO guild_trial_lobby VALUES(99,1)");refused(f);
            for(int state:new int[]{0,2,3,99}){f.sql.update("UPDATE guild_trial_lobby SET state=?",state);refused(f);}
            f.sql.update("UPDATE guild_trial_lobby SET state=1");f.sql.update("UPDATE guild_activity_slot SET expires_ms=0");refused(f);
            f.sql.update("UPDATE guild_activity_slot SET expires_ms=1");refused(f);
            f.sql.update("UPDATE guild_trial_lobby SET state=4");assertEquals(Status.FREEZING,f.trade(5,true).beginFreeze(request(),BUDGET).status());
        }
    }
    @Test void 生产资格仅完整合同终态放行_DELIVERED和未知九均不能转卖()throws Exception{
        for(int status:new int[]{0,5,9,10,99,7,11})try(var fixture=new PlayerTradeStoreTest.Fixture()){
            fixture.player(1,"seller");
            fixture.sql.update("INSERT INTO trade_commerce VALUES(91,1,0,?)",status);
            var eligibility=new PlayerStoreAutoConfiguration().playerTradeFreezeEligibility(false);
            var trade=new PlayerTradeStore(fixture.source,fixture.manager,fixture.clock::get,5,(sql,receipt)->true,eligibility);
            if(status==7||status==11)assertEquals(Status.FREEZING,trade.beginFreeze(request(),BUDGET).status());
            else{assertEquals(Code.NOT_READY,assertThrows(Failure.class,()->trade.beginFreeze(request(),BUDGET)).code());assertEquals(0,fixture.count("player_trade_freeze"));assertEquals(0,fixture.row(1).getTradeListingId());}
        }
    }
    private static final Duration BUDGET = Duration.ofSeconds(5);
    private static FreezeRequest request() { return new FreezeRequest(101,1,"seller",1); }
    private static void refused(PlayerTradeStoreTest.Fixture fixture) {
        assertEquals(Code.NOT_READY,assertThrows(Failure.class,() -> fixture.trade(5,true).beginFreeze(request(),BUDGET)).code());
        assertEquals(0,fixture.row(1).getTradeListingId());
        assertEquals(0,fixture.count("player_trade_freeze"));
    }
    @Test void 永久在队关系阻止冻结_离队墓碑才放行()throws Exception{
        try(var fixture=new PlayerTradeStoreTest.Fixture()){
            fixture.player(1,"seller");fixture.sql.update("INSERT INTO team_member_authority VALUES(1,99)");refused(fixture);
            fixture.sql.update("UPDATE team_member_authority SET team_id=0 WHERE player_id=1");
            assertEquals(Status.FREEZING,fixture.trade(5,true).beginFreeze(request(),BUDGET).status());
        }
    }
    @Test void Team待处理同意与未知开局阻止冻结_原DB时效及已知终局才放行()throws Exception{
        try(var fixture=new PlayerTradeStoreTest.Fixture()){
            fixture.player(1,"seller");fixture.sql.update("INSERT INTO team_invite_authority VALUES(1,99,?,8)",Long.MAX_VALUE);refused(fixture);
            fixture.sql.update("UPDATE team_invite_authority SET invitee_id=8,initiator_player_id=1");refused(fixture);
            fixture.sql.update("UPDATE team_invite_authority SET expires_ms=0");fixture.sql.update("INSERT INTO team_application_authority VALUES(1,99,?,8)",Long.MAX_VALUE);refused(fixture);
            fixture.sql.update("UPDATE team_application_authority SET player_id=8,recipient_player_id=1");refused(fixture);
            fixture.sql.update("UPDATE team_application_authority SET expires_ms=0");fixture.sql.update("INSERT INTO team_launch_role VALUES(1,99,1)");
            for(int state:new int[]{0,1,3,99}){fixture.sql.update("UPDATE team_launch_role SET state=?",state);refused(fixture);}
            fixture.sql.update("UPDATE team_launch_role SET state=2");assertEquals(Status.FREEZING,fixture.trade(5,true).beginFreeze(request(),BUDGET).status());
        }
    }

    @Test void ACTIVE未知投影与未发奖均阻止冻结_只完整终态放行() throws Exception {
        try (var fixture = new PlayerTradeStoreTest.Fixture()) {
            fixture.player(1,"seller");
            fixture.sql.update("INSERT INTO guild_trial_launch_role VALUES(1,99,1)");
            refused(fixture);
            fixture.sql.update("UPDATE guild_trial_launch_role SET state=0"); refused(fixture);
            fixture.sql.update("UPDATE guild_trial_launch_role SET state=2");
            fixture.sql.update("INSERT INTO guild_activity_reward_owed VALUES(71,1,0)"); refused(fixture);
            fixture.sql.update("UPDATE guild_activity_reward_owed SET status=2"); refused(fixture);
            fixture.sql.update("UPDATE guild_activity_reward_owed SET status=1");
            assertEquals(Status.FREEZING,fixture.trade(5,true).beginFreeze(request(),BUDGET).status());
        }
    }

    @Test void PENDING和manual及未知资产状态拒过户_三个完整终态与他人记录不误拦() throws Exception {
        try (var fixture = new PlayerTradeStoreTest.Fixture()) {
            fixture.player(1,"seller");
            fixture.sql.update("INSERT INTO guild_asset_op VALUES(71,1,1)");
            for (int status : new int[]{0,1,5,99}) {
                fixture.sql.update("UPDATE guild_asset_op SET status=?",status); refused(fixture);
            }
            fixture.sql.update("DELETE FROM guild_asset_op");
            fixture.sql.update("INSERT INTO guild_asset_op VALUES(71,1,2),(72,1,3),(73,1,4),(74,9,1)");
            fixture.sql.update("INSERT INTO guild_activity_reward_owed VALUES(75,9,0)");
            fixture.sql.update("INSERT INTO guild_trial_launch_role VALUES(9,99,1)");
            assertEquals(Status.FREEZING,fixture.trade(5,true).beginFreeze(request(),BUDGET).status());
        }
    }

    @Test void 未进入事务或表不可用均拒绝_不能把依赖缺失当作没有ACTIVE() throws Exception {
        try (var fixture = new PlayerTradeStoreTest.Fixture()) {
            fixture.player(1,"seller");
            try (var connection = fixture.source.getConnection()) {
                assertTrue(connection.getAutoCommit());
                assertThrows(SQLException.class,() -> RolePendingFence.blocked(connection,1));
            }
            fixture.sql.execute("DROP TABLE guild_trial_launch_role");
            assertThrows(RuntimeException.class,() -> fixture.trade(5,true).beginFreeze(request(),BUDGET));
            assertEquals(0,fixture.row(1).getTradeListingId()); assertEquals(0,fixture.count("player_trade_freeze"));
        }
    }

    @Test void 邮件pending及manual与排队备战建房票均阻止冻结_终态才放行() throws Exception {
        try (var fixture = new PlayerTradeStoreTest.Fixture()) {
            fixture.player(1,"seller");
            fixture.sql.update("INSERT INTO mail_asset_op VALUES(71,1,1)");
            for (int status : new int[]{0,1,4,99}) {
                fixture.sql.update("UPDATE mail_asset_op SET status=?",status); refused(fixture);
            }
            fixture.sql.update("UPDATE mail_asset_op SET status=5");
            fixture.sql.update("INSERT INTO match_global_ticket VALUES(1,1)");
            for (int state : new int[]{0,1,2,3,99}) {
                fixture.sql.update("UPDATE match_global_ticket SET state=?",state); refused(fixture);
            }
            fixture.sql.update("UPDATE match_global_ticket SET state=4");
            assertEquals(Status.FREEZING,fixture.trade(5,true).beginFreeze(request(),BUDGET).status());
        }
    }

    @Test void 未消费有效邀请阻双方冻结_过期或已知终局才放行() throws Exception {
        try (var fixture = new PlayerTradeStoreTest.Fixture()) {
            fixture.player(1,"seller");
            fixture.sql.update("INSERT INTO match_global_challenge VALUES(1,9,1,?)",Long.MAX_VALUE);
            refused(fixture);
            fixture.sql.update("DELETE FROM match_global_challenge");
            fixture.sql.update("INSERT INTO match_global_challenge VALUES(9,1,1,?)",Long.MAX_VALUE);
            refused(fixture);
            for (int state : new int[]{0,99}) {
                fixture.sql.update("UPDATE match_global_challenge SET state=?,expires_ms=0",state); refused(fixture);
            }
            fixture.sql.update("UPDATE match_global_challenge SET state=1,expires_ms=0");
            fixture.sql.update("INSERT INTO match_global_challenge VALUES(1,8,2,?),(8,1,3,?)",Long.MAX_VALUE,Long.MAX_VALUE);
            fixture.sql.update("INSERT INTO match_global_challenge VALUES(2,3,1,?)",Long.MAX_VALUE);
            assertEquals(Status.FREEZING,fixture.trade(5,true).beginFreeze(request(),BUDGET).status());
        }
    }
    @Test void 观战原意图和取消未知均阻止冻结_只有closed可过户()throws Exception{
        try(var fixture=new PlayerTradeStoreTest.Fixture()){
            fixture.player(1,"seller");fixture.sql.update("INSERT INTO match_global_watching VALUES(1,1)");
            for(int state:new int[]{0,1,2,3,4,99}){fixture.sql.update("UPDATE match_global_watching SET state=?",state);refused(fixture);}
            fixture.sql.update("UPDATE match_global_watching SET state=5");assertEquals(Status.FREEZING,fixture.trade(5,true).beginFreeze(request(),BUDGET).status());
        }
    }
}
