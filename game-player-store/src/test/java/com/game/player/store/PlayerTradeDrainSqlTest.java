package com.game.player.store;

import static org.junit.jupiter.api.Assertions.*;

import com.game.player.store.PlayerTradeStore.Code;
import com.game.player.store.PlayerTradeStore.DrainTask;
import com.game.player.store.PlayerTradeStore.Failure;
import com.game.player.store.PlayerTradeStore.FreezeRequest;
import com.game.player.store.PlayerTradeStore.Status;
import com.game.player.store.state.Facing;
import com.game.player.store.state.PlayerState;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 使用生产Mapper/事务/证明验证器；SCENE的RAM排空ACK来自受控夹具，SQL应用是真实执行。 */
class PlayerTradeDrainSqlTest {
    private static final Duration BUDGET = Duration.ofSeconds(5);
    private static final String SOURCE = "scene-original";
    private static PlayerTradeStore trades(PlayerTradeStoreTest.Fixture f) {
        return new PlayerTradeStore(f.source, f.manager, f.clock::get, 5, PlayerTradeStore.sqlFreezeVerifier(),
            (sql, request) -> {});
    }
    private static void refuses(Code code, org.junit.jupiter.api.function.Executable work) {
        assertEquals(code, assertThrows(Failure.class, work).code());
    }

    @Test void 无OPEN的旧受理入场在同锁永久封E_撤单后只允许更高E进入() throws Exception {
        try (var f = new PlayerTradeStoreTest.Fixture()) {
            f.player(1, "seller");
            assertEquals(new PlayerStore.ClaimResult.Claimed(1), f.players.claimOwnership(1, "seller", 1, BUDGET));
            var request = new FreezeRequest(101, 1, "seller", 1);
            var trades = trades(f);
            trades.beginFreeze(request, BUDGET);
            assertEquals(Status.FROZEN, trades.confirmFreeze(request, BUDGET).status());
            assertEquals("SQL_NO_ADMISSION", f.sql.queryForObject("SELECT proof_mode FROM player_trade_drain WHERE listing_id=101", String.class));
            assertEquals(1, f.mapper.countTradeSeals(1, 1));
            assertEquals(0, f.count("player_final_save"));
            assertEquals(Status.UNLOCKED, trades.cancelFreeze(request, BUDGET).status());
            assertTrue(f.players.loadForScene(1, 1, SOURCE, BUDGET).isEmpty());
            assertEquals(new PlayerStore.ClaimResult.Claimed(2), f.players.claimOwnership(1, "seller", 1, BUDGET));
            assertTrue(f.players.loadForScene(1, 1, SOURCE, BUDGET).isEmpty());
            assertTrue(f.players.loadForScene(1, 2, SOURCE, BUDGET).isPresent());
            assertEquals(Status.UNLOCKED, trades.beginFreeze(request, BUDGET).status());
            assertEquals(2, f.row(1).getOwnerEpoch());
            assertEquals(0, f.row(1).getTradeListingId());
        }
    }

    @Test void 撤单必须等RAM排空ACK和真实APPLIED_原scene退出后SQL补偿仍可按原请求完成() throws Exception {
        try (var f = new PlayerTradeStoreTest.Fixture()) {
            f.player(1, "seller");
            assertEquals(new PlayerStore.ClaimResult.Claimed(1), f.players.claimOwnership(1, BUDGET));
            assertTrue(f.players.loadForScene(1, 1, SOURCE, BUDGET).isPresent());
            var request = new FreezeRequest(101, 1, "seller", 1);
            var trades = trades(f); trades.beginFreeze(request, BUDGET);
            var task = trades.pendingSceneDrains(SOURCE, 0, 20).getFirst();
            assertEquals(request, task.request());
            assertTrue(trades.pendingSceneDrains("different-scene", 0, 20).isEmpty());
            refuses(Code.NOT_READY, () -> trades.cancelFreeze(request, BUDGET));
            assertEquals(101, f.row(1).getTradeListingId());
            assertEquals(1, f.sql.queryForObject("SELECT cancel_requested FROM player_trade_freeze WHERE listing_id=101", Integer.class).intValue());
            refuses(Code.NOT_READY, () -> trades.recordSceneDrain(task, BUDGET));
            PlayerRow finalRow = f.row(1); finalRow.setLevel(3); finalRow.setPosX(42.5); finalRow.setSceneConfigId(7);
            byte[] original = PlayerState.newBuilder().setFacing(Facing.newBuilder().setX(0.5)).build().toByteArray();
            assertEquals(PlayerStore.FinalSaveStatus.ACCEPTED, f.players.registerFinalSave(finalRow, original, SOURCE, BUDGET).status());
            refuses(Code.NOT_READY, () -> trades.recordSceneDrain(new DrainTask(request, 1, "different-scene"), BUDGET));
            assertEquals(Status.FREEZING, trades.recordSceneDrain(task, BUDGET).status(), "ACK不能伪造SQL完成或清锁");
            assertEquals("SCENE_ACK", f.sql.queryForObject("SELECT proof_mode FROM player_trade_drain WHERE listing_id=101", String.class));
            assertEquals(101, f.row(1).getTradeListingId());
            assertEquals(PlayerStore.FinalSaveStatus.APPLIED, f.players.completeFinalSave(1, 1, BUDGET).status());
            // 重建业务对象模拟原scene已退出；不重新造scene的RAM或source证明。
            assertEquals(Status.UNLOCKED, trades(f).cancelFreeze(request, BUDGET).status());
            assertArrayEquals(original, f.sql.queryForObject("SELECT data FROM player_state WHERE player_id=1", (rs, ignored) -> rs.getBytes(1)));
            assertEquals(new PlayerStore.ClaimResult.Claimed(2), f.players.claimOwnership(1, "seller", 1, BUDGET));
            assertTrue(f.players.loadForScene(1, 1, SOURCE, BUDGET).isEmpty());
        }
    }

    @Test void 接管和伪APPLIED标记不能确认_必须原摘要实际字节和标量都一致() throws Exception {
        try (var f = new PlayerTradeStoreTest.Fixture()) {
            f.player(1, "seller"); f.players.claimOwnership(1, BUDGET);
            f.players.loadForScene(1, 1, SOURCE, BUDGET);
            var request = new FreezeRequest(101, 1, "seller", 1);
            var trades = trades(f); trades.beginFreeze(request, BUDGET);
            PlayerRow finalRow = f.row(1); finalRow.setLevel(3); finalRow.setPosX(42.5);
            byte[] bytes = PlayerState.newBuilder().setFacing(Facing.newBuilder().setX(0.5)).build().toByteArray();
            f.players.registerFinalSave(finalRow, bytes, SOURCE, BUDGET);
            var task = new DrainTask(request, 1, SOURCE);
            trades.recordSceneDrain(task, BUDGET);
            refuses(Code.NOT_READY, () -> trades.confirmFreeze(request, BUDGET));
            // 只篡改本例故障输入；标记不是SQL资产完成证据。
            f.sql.update("UPDATE player_final_save SET status='APPLIED' WHERE player_id=1 AND owner_epoch=1");
            refuses(Code.NOT_READY, () -> trades.confirmFreeze(request, BUDGET));
            f.sql.update("UPDATE player_final_save SET status='PENDING' WHERE player_id=1 AND owner_epoch=1");
            f.players.completeFinalSave(1, 1, BUDGET);
            f.sql.update("UPDATE player_state SET data=? WHERE player_id=1", PlayerState.getDefaultInstance().toByteArray());
            refuses(Code.NOT_READY, () -> trades.confirmFreeze(request, BUDGET));
            f.sql.update("UPDATE player_state SET data=? WHERE player_id=1", bytes);
            f.sql.update("UPDATE player SET pos_x=999 WHERE player_id=1");
            refuses(Code.NOT_READY, () -> trades.confirmFreeze(request, BUDGET));
            f.sql.update("UPDATE player SET pos_x=42.5 WHERE player_id=1");
            assertEquals(Status.FROZEN, trades.confirmFreeze(request, BUDGET).status());
            f.sql.update("UPDATE player_trade_drain SET payload_digest=? WHERE listing_id=101", new byte[32]);
            refuses(Code.NOT_READY, () -> trades.confirmFreeze(request, BUDGET));
            assertEquals(101, f.row(1).getTradeListingId());
        }
    }

    @Test void 更旧OPEN来源未排空时不能假定没有在线实体_不拿租约超时清锁() throws Exception {
        try (var f = new PlayerTradeStoreTest.Fixture()) {
            f.player(1, "seller"); f.players.claimOwnership(1, BUDGET);
            f.players.loadForScene(1, 1, "old-source", BUDGET);
            f.clock.addAndGet(PlayerStore.OWNER_LEASE.toMillis() * 2);
            assertEquals(new PlayerStore.ClaimResult.Claimed(2), f.players.claimOwnership(1, BUDGET));
            var request = new FreezeRequest(101, 1, "seller", 1);
            var trades = trades(f); trades.beginFreeze(request, BUDGET);
            refuses(Code.NOT_READY, () -> trades.confirmFreeze(request, BUDGET));
            refuses(Code.NOT_READY, () -> trades.cancelFreeze(request, BUDGET));
            assertEquals(0, f.count("player_trade_drain"));
            assertEquals(101, f.row(1).getTradeListingId());
        }
    }
    private record RecoverySetup(PlayerTradeStore store,FreezeRequest freeze,
                                 PlayerTradeStore.RecoveryRequest request,PlayerRow current,byte[] bytes) {}
    private static com.game.proto.common.base.RoleAdmissionIdentity identity(long epoch,String source) {
        return com.game.proto.common.base.RoleAdmissionIdentity.newBuilder().setPlayerId(1).setOwnerEpoch(epoch)
                .setOwnerRevision(1).setSceneInstanceId(source).setGeneration(7).setSceneNodeId("scene-node")
                .setSceneId(41).setZoneId(7).setSessionId(2).setLoginGeneration(3).build();
    }
    private static RecoverySetup recovery(PlayerTradeStoreTest.Fixture f,boolean complete) {
        f.player(1,"seller");assertEquals(new PlayerStore.ClaimResult.Claimed(1),f.players.claimOwnership(1,BUDGET));
        assertTrue(f.players.loadForScene(identity(1,"old-source"),"seller",BUDGET).isPresent());
        f.clock.addAndGet(PlayerStore.OWNER_LEASE.toMillis()*2);
        assertEquals(new PlayerStore.ClaimResult.Claimed(2),f.players.claimOwnership(1,BUDGET));
        assertTrue(f.players.loadForScene(identity(2,SOURCE),"seller",BUDGET).isPresent());
        var store=trades(f);var freeze=new FreezeRequest(101,1,"seller",1);store.beginFreeze(freeze,BUDGET);
        var current=f.row(1);current.setLevel(9);current.setSceneConfigId(7);current.setPosX(42.5);
        byte[] bytes=PlayerState.newBuilder().setFacing(Facing.newBuilder().setX(0.25)).build().toByteArray();
        assertEquals(PlayerStore.FinalSaveStatus.ACCEPTED,f.players.registerFinalSave(current,bytes,SOURCE,BUDGET).status());
        store.recordSceneDrain(new DrainTask(freeze,2,SOURCE),BUDGET);
        if(complete)assertEquals(PlayerStore.FinalSaveStatus.APPLIED,f.players.completeFinalSave(1,2,BUDGET).status());
        return new RecoverySetup(store,freeze,new PlayerTradeStore.RecoveryRequest(java.util.UUID.randomUUID().toString(),freeze,1,"old-source"),current,bytes);
    }
    private static int oldOpen(PlayerTradeStoreTest.Fixture f) {
        return f.sql.queryForObject("SELECT COUNT(*) FROM player_final_save WHERE player_id=1 AND owner_epoch=1",Integer.class);
    }
    private static void notRecovered(PlayerTradeStoreTest.Fixture f) {
        assertEquals(1,oldOpen(f));assertEquals(0,f.count("player_trade_admission_recovery"));
        assertEquals(101,f.row(1).getTradeListingId());
    }

    @Test void 旧OPEN默认计划零写_同事务退休原件并永久认重_不冒APPLIED() throws Exception {
        try(var f=new PlayerTradeStoreTest.Fixture()) {
            var r=recovery(f,true);refuses(Code.NOT_READY,()->r.store().confirmFreeze(r.freeze(),BUDGET));
            var plan=r.store().planOpenRecovery(r.request(),BUDGET);notRecovered(f);
            assertEquals(2,plan.currentEpoch());assertEquals(64,plan.requestDigest().length());
            var original=f.sql.queryForMap("SELECT * FROM player_final_save WHERE player_id=1 AND owner_epoch=2");
            var receipt=r.store().applyOpenRecovery(plan,BUDGET);
            assertEquals("RETIRED_BY_SQL_SUCCESSOR",receipt.status());assertEquals(0,oldOpen(f));
            assertEquals(1,f.count("player_trade_admission_recovery"));
            assertArrayEquals(r.bytes(),f.sql.queryForObject("SELECT data FROM player_state WHERE player_id=1",byte[].class));
            var after=f.sql.queryForMap("SELECT * FROM player_final_save WHERE player_id=1 AND owner_epoch=2");
            assertEquals(original.keySet(),after.keySet());
            for(var column:original.entrySet()) {
                if(column.getValue() instanceof byte[] bytes)assertArrayEquals(bytes,(byte[])after.get(column.getKey()));
                else assertEquals(column.getValue(),after.get(column.getKey()));
            }
            assertEquals(PlayerStore.FinalSaveStatus.FENCED,f.players.registerFinalSave(originalRow(f,1),new byte[0],"old-source",BUDGET).status());
            assertEquals(Status.FROZEN,r.store().confirmFreeze(r.freeze(),BUDGET).status());
            assertEquals(Status.UNLOCKED,r.store().cancelFreeze(r.freeze(),BUDGET).status());
            assertEquals(new PlayerStore.ClaimResult.Claimed(3),f.players.claimOwnership(1,BUDGET));
            assertEquals(receipt,trades(f).applyOpenRecovery(plan,BUDGET),"新代不参与原永久收据的只读重放");
            assertEquals(3,f.row(1).getOwnerEpoch());assertEquals(0,f.row(1).getTradeListingId());
        }
    }
    private static PlayerRow originalRow(PlayerTradeStoreTest.Fixture f,long epoch) {
        var row=f.row(1);row.setOwnerEpoch(epoch);return row;
    }

    @Test void 旧OPEN恢复拒PENDING_未知终局_假APPLIED及缺失实际state_不丢原待办() throws Exception {
        try(var f=new PlayerTradeStoreTest.Fixture()) {
            var r=recovery(f,false);
            byte[] pending=f.sql.queryForObject("SELECT data FROM player_final_save WHERE player_id=1 AND owner_epoch=2",byte[].class);
            refuses(Code.NOT_READY,()->r.store().planOpenRecovery(r.request(),BUDGET));notRecovered(f);
            assertArrayEquals(pending,f.sql.queryForObject("SELECT data FROM player_final_save WHERE player_id=1 AND owner_epoch=2",byte[].class));
            assertEquals(PlayerStore.FinalSaveStatus.APPLIED,f.players.completeFinalSave(1,2,BUDGET).status());
            f.sql.update("UPDATE player_final_save SET status='UNKNOWN' WHERE player_id=1 AND owner_epoch=1");
            refuses(Code.NOT_READY,()->r.store().planOpenRecovery(r.request(),BUDGET));notRecovered(f);
            f.sql.update("UPDATE player_final_save SET status='OPEN' WHERE player_id=1 AND owner_epoch=1");
            f.sql.update("UPDATE player_state SET data=? WHERE player_id=1",new byte[0]);
            refuses(Code.NOT_READY,()->r.store().planOpenRecovery(r.request(),BUDGET));notRecovered(f);
            f.sql.update("DELETE FROM player_state WHERE player_id=1");
            refuses(Code.NOT_READY,()->r.store().planOpenRecovery(r.request(),BUDGET));notRecovered(f);
        }
    }

    @Test void 旧OPEN恢复计划绑定route及完整旧准入_任何漂移不删原行() throws Exception {
        try(var f=new PlayerTradeStoreTest.Fixture()) {
            var r=recovery(f,true);var plan=r.store().planOpenRecovery(r.request(),BUDGET);
            f.sql.update("UPDATE zone_merge_fence SET revision=revision+1 WHERE zone_id=7 AND shard=0");
            refuses(Code.CONFLICT,()->r.store().applyOpenRecovery(plan,BUDGET));notRecovered(f);
            f.sql.update("UPDATE zone_merge_fence SET revision=revision-1 WHERE zone_id=7 AND shard=0");
            f.sql.update("UPDATE player_final_save SET admission_proto=? WHERE player_id=1 AND owner_epoch=1",identity(1,"different-source").toByteArray());
            refuses(Code.CORRUPT,()->r.store().applyOpenRecovery(plan,BUDGET));notRecovered(f);
        }
    }

    @Test void 旧OPEN无完整原件或SQL_NO_ADMISSION不能维护追认_删后ABSENT不是成功() throws Exception {
        try(var f=new PlayerTradeStoreTest.Fixture()) {
            var r=recovery(f,true);var plan=r.store().planOpenRecovery(r.request(),BUDGET);
            f.sql.update("UPDATE player_final_save SET admission_proto=NULL WHERE player_id=1 AND owner_epoch=1");
            refuses(Code.NOT_READY,()->r.store().planOpenRecovery(r.request(),BUDGET));notRecovered(f);
            f.sql.update("UPDATE player_final_save SET admission_proto=? WHERE player_id=1 AND owner_epoch=1",identity(1,"old-source").toByteArray());
            f.sql.update("UPDATE player_trade_drain SET proof_mode='SQL_NO_ADMISSION' WHERE listing_id=101");
            refuses(Code.NOT_READY,()->r.store().applyOpenRecovery(plan,BUDGET));notRecovered(f);
            f.sql.update("UPDATE player_trade_drain SET proof_mode='SCENE_ACK' WHERE listing_id=101");
            f.sql.update("DELETE FROM player_final_save WHERE player_id=1 AND owner_epoch=1");
            refuses(Code.NOT_READY,()->r.store().applyOpenRecovery(plan,BUDGET));
            assertEquals(0,f.count("player_trade_admission_recovery"));
        }
    }

    @Test void 旧OPEN并发两恢复UUID最多一个提交_同原件重建对象可查永久结果() throws Exception {
        try(var f=new PlayerTradeStoreTest.Fixture()) {
            var r=recovery(f,true);var first=r.store().planOpenRecovery(r.request(),BUDGET);
            var second=r.store().planOpenRecovery(new PlayerTradeStore.RecoveryRequest(java.util.UUID.randomUUID().toString(),r.freeze(),1,"old-source"),BUDGET);
            var start=new java.util.concurrent.CountDownLatch(1);var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var tasks=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
                for(var plan:java.util.List.of(first,second))tasks.add(executor.submit(()->{
                    assertTrue(start.await(5,java.util.concurrent.TimeUnit.SECONDS));
                    try{trades(f).applyOpenRecovery(plan,BUDGET);return true;}catch(Failure failure){assertEquals(Code.NOT_READY,failure.code());return false;}
                }));
                start.countDown();int success=0;for(var task:tasks)if(task.get(15,java.util.concurrent.TimeUnit.SECONDS))success++;
                assertEquals(1,success);assertEquals(1,f.count("player_trade_admission_recovery"));assertEquals(0,oldOpen(f));
                String winner=f.sql.queryForObject("SELECT recovery_id FROM player_trade_admission_recovery",String.class);
                var plan=winner.equals(first.request().recoveryId())?first:second;
                assertEquals(plan.requestDigest(),trades(f).applyOpenRecovery(plan,BUDGET).requestDigest());
                f.sql.update("UPDATE player_trade_admission_recovery SET request_digest=? WHERE recovery_id=?",new byte[32],winner.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                refuses(Code.CONFLICT,()->trades(f).applyOpenRecovery(plan,BUDGET));
            } finally {start.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
        }
    }

    @Test void 恢复原件schema缺唯一原代索引时拒启动() throws Exception {
        try(var f=new PlayerTradeStoreTest.Fixture()) {
            try(var connection=f.source.getConnection()) {
                String drop="H2".equals(connection.getMetaData().getDatabaseProductName())
                        ? "ALTER TABLE player_trade_admission_recovery DROP CONSTRAINT uk_trade_admission_recovery_epoch"
                        : "ALTER TABLE player_trade_admission_recovery DROP INDEX uk_trade_admission_recovery_epoch";
                f.sql.execute(drop);
            }
            try(var connection=f.source.getConnection()) {
                assertThrows(IllegalStateException.class,()->PlayerSqlAuthorityAutoConfiguration.verifyAdmissionRecoverySchema(connection));
            }
        }
    }

    private record UnusedEpoch(PlayerTradeStore store,FreezeRequest freeze,long currentEpoch,
                               java.util.List<PlayerTradeStore.RecoveryRequest> requests) {}
    private static UnusedEpoch unusedEpoch(PlayerTradeStoreTest.Fixture f,int oldCount,boolean freezeNow) {
        f.player(1,"seller");assertEquals(new PlayerStore.ClaimResult.Claimed(1),f.players.claimOwnership(1,BUDGET));
        var requests=new java.util.ArrayList<PlayerTradeStore.RecoveryRequest>();var freeze=new FreezeRequest(101,1,"seller",1);
        for(long epoch=1;epoch<=oldCount;epoch++) {
            String source="old-source-"+epoch;
            assertTrue(f.players.loadForScene(identity(epoch,source),"seller",BUDGET).isPresent());
            requests.add(new PlayerTradeStore.RecoveryRequest(java.util.UUID.randomUUID().toString(),freeze,epoch,source));
            f.clock.addAndGet(PlayerStore.OWNER_LEASE.toMillis()*2);
            assertEquals(new PlayerStore.ClaimResult.Claimed(epoch+1),f.players.claimOwnership(1,BUDGET));
        }
        var store=trades(f);if(freezeNow)store.beginFreeze(freeze,BUDGET);
        return new UnusedEpoch(store,freeze,oldCount+1,java.util.List.copyOf(requests));
    }
    private static int released(PlayerTradeStoreTest.Fixture f) {
        return f.sql.queryForObject("SELECT owner_released FROM player WHERE player_id=1",Integer.class);
    }

    @Test void 仅claim未load的更高E可按真实SQL_NO_ADMISSION封闭_计划不提前释放或造APPLIED() throws Exception {
        try(var f=new PlayerTradeStoreTest.Fixture()) {
            var r=unusedEpoch(f,1,true);assertEquals(0,released(f));
            byte[] before=f.sql.queryForObject("SELECT data FROM player_state WHERE player_id=1",byte[].class);
            long saved=f.sql.queryForObject("SELECT saved_epoch FROM player_state WHERE player_id=1",Long.class);
            long updated=f.sql.queryForObject("SELECT updated_at FROM player_state WHERE player_id=1",Long.class);
            var plan=r.store().planOpenRecovery(r.requests().getFirst(),BUDGET);
            assertEquals("SQL_NO_ADMISSION",plan.proofMode());assertTrue(plan.sealUnusedEpoch());assertEquals(0,plan.remainingAdmissions());
            assertEquals(plan,r.store().planOpenRecovery(r.requests().getFirst(),BUDGET),"同原SQL数值重复编码必须稳定");
            f.sql.update("UPDATE player SET pos_y=0.125 WHERE player_id=1");
            assertNotEquals(plan.playerDigest(),r.store().planOpenRecovery(r.requests().getFirst(),BUDGET).playerDigest());
            refuses(Code.CONFLICT,()->r.store().applyOpenRecovery(plan,BUDGET));notRecovered(f);
            f.sql.update("UPDATE player SET pos_y=0 WHERE player_id=1");
            f.sql.update("UPDATE player_state SET data=? WHERE player_id=1",PlayerState.newBuilder().setFacing(Facing.newBuilder().setY(0.125)).build().toByteArray());
            assertNotEquals(plan.stateDigest(),r.store().planOpenRecovery(r.requests().getFirst(),BUDGET).stateDigest());
            refuses(Code.CONFLICT,()->r.store().applyOpenRecovery(plan,BUDGET));notRecovered(f);
            f.sql.update("UPDATE player_state SET data=? WHERE player_id=1",before);
            assertEquals(plan,r.store().planOpenRecovery(r.requests().getFirst(),BUDGET));
            assertEquals(0,released(f));assertEquals(0,f.count("player_trade_drain"));notRecovered(f);
            var receipt=r.store().applyOpenRecovery(plan,BUDGET);
            assertEquals("SQL_NO_ADMISSION",receipt.proofMode());assertTrue(receipt.unadmittedEpochSealed());
            assertEquals(1,released(f));assertEquals(0,f.count("player_final_save"));
            assertEquals("SQL_NO_ADMISSION",f.sql.queryForObject("SELECT proof_mode FROM player_trade_drain WHERE listing_id=101",String.class));
            assertArrayEquals(before,f.sql.queryForObject("SELECT data FROM player_state WHERE player_id=1",byte[].class));
            assertEquals(saved,f.sql.queryForObject("SELECT saved_epoch FROM player_state WHERE player_id=1",Long.class).longValue());
            assertEquals(updated,f.sql.queryForObject("SELECT updated_at FROM player_state WHERE player_id=1",Long.class).longValue());
            assertEquals(PlayerStore.FinalSaveStatus.FENCED,f.players.registerFinalSave(originalRow(f,1),before,"old-source-1",BUDGET).status());
            assertEquals(Status.FROZEN,r.store().confirmFreeze(r.freeze(),BUDGET).status());
            assertEquals(Status.UNLOCKED,r.store().cancelFreeze(r.freeze(),BUDGET).status());
            assertTrue(f.players.loadForScene(identity(2,SOURCE),"seller",BUDGET).isEmpty());
            assertEquals(new PlayerStore.ClaimResult.Claimed(3),f.players.claimOwnership(1,BUDGET));
            assertTrue(f.players.loadForScene(identity(3,SOURCE),"seller",BUDGET).isPresent());
            assertEquals(receipt,trades(f).applyOpenRecovery(plan,BUDGET));
        }
    }

    @Test void 多旧OPEN固定剩余原件集与last语义_较早退休不提前释放_漂移后原计划拒绝() throws Exception {
        try(var f=new PlayerTradeStoreTest.Fixture()) {
            var r=unusedEpoch(f,2,true);
            var first=r.store().planOpenRecovery(r.requests().get(0),BUDGET);
            var secondBefore=r.store().planOpenRecovery(r.requests().get(1),BUDGET);
            assertEquals(1,first.remainingAdmissions());assertFalse(first.sealUnusedEpoch());
            f.sql.update("UPDATE player_final_save SET next_attempt_at=next_attempt_at+1 WHERE player_id=1 AND owner_epoch=2");
            refuses(Code.CONFLICT,()->r.store().applyOpenRecovery(first,BUDGET));assertEquals(0,f.count("player_trade_admission_recovery"));
            f.sql.update("UPDATE player_final_save SET next_attempt_at=next_attempt_at-1 WHERE player_id=1 AND owner_epoch=2");
            var receipt=r.store().applyOpenRecovery(first,BUDGET);
            assertEquals(1,receipt.otherOpenAdmissions());assertFalse(receipt.unadmittedEpochSealed());
            assertEquals(0,released(f));assertEquals(0,f.count("player_trade_drain"));assertEquals(1,f.count("player_final_save"));
            refuses(Code.NOT_READY,()->r.store().confirmFreeze(r.freeze(),BUDGET));
            refuses(Code.CONFLICT,()->r.store().applyOpenRecovery(secondBefore,BUDGET));
            assertEquals(1,f.count("player_trade_admission_recovery"));assertEquals(0,released(f));
            var last=r.store().planOpenRecovery(r.requests().get(1),BUDGET);assertEquals(0,last.remainingAdmissions());assertTrue(last.sealUnusedEpoch());
            assertTrue(r.store().applyOpenRecovery(last,BUDGET).unadmittedEpochSealed());
            assertEquals(2,f.count("player_trade_admission_recovery"));assertEquals(1,released(f));assertEquals(0,f.count("player_final_save"));
            assertEquals(receipt,trades(f).applyOpenRecovery(first,BUDGET),"较早收据永远不追认为最后一条或全排空");
            assertEquals(Status.FROZEN,r.store().confirmFreeze(r.freeze(),BUDGET).status());
        }
    }

    @Test void load先取得P就必须已有OPEN而拒无准入恢复_冻结先取得P则迟load永不建RAM() throws Exception {
        try(var f=new PlayerTradeStoreTest.Fixture()) {
            var r=unusedEpoch(f,1,false);
            assertTrue(f.players.loadForScene(identity(2,SOURCE),"seller",BUDGET).isPresent(),"load先持P，返回快照之前已经提交OPEN");
            r.store().beginFreeze(r.freeze(),BUDGET);
            refuses(Code.NOT_READY,()->r.store().planOpenRecovery(r.requests().getFirst(),BUDGET));
            assertEquals(2,f.count("player_final_save"));assertEquals(0,f.count("player_trade_admission_recovery"));assertEquals(0,released(f));
        }
        try(var f=new PlayerTradeStoreTest.Fixture()) {
            var r=unusedEpoch(f,1,true);var plan=r.store().planOpenRecovery(r.requests().getFirst(),BUDGET);
            var start=new java.util.concurrent.CountDownLatch(1);var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var loading=executor.submit(()->{assertTrue(start.await(5,java.util.concurrent.TimeUnit.SECONDS));return f.players.loadForScene(identity(2,SOURCE),"seller",BUDGET);});
                var applying=executor.submit(()->{assertTrue(start.await(5,java.util.concurrent.TimeUnit.SECONDS));return trades(f).applyOpenRecovery(plan,BUDGET);});
                start.countDown();assertTrue(loading.get(15,java.util.concurrent.TimeUnit.SECONDS).isEmpty());
                assertTrue(applying.get(15,java.util.concurrent.TimeUnit.SECONDS).unadmittedEpochSealed());
                assertEquals(0,f.count("player_final_save"));assertEquals(1,f.count("player_trade_admission_recovery"));assertEquals(1,released(f));
            } finally {start.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
        }
    }

    @Test void 无准入恢复仍拒缺行损坏及未来savedE_不造初始存档也不清旧OPEN() throws Exception {
        try(var f=new PlayerTradeStoreTest.Fixture()) {
            var r=unusedEpoch(f,1,true);
            var plan=r.store().planOpenRecovery(r.requests().getFirst(),BUDGET);
            f.sql.update("UPDATE player_state SET saved_epoch=99 WHERE player_id=1");
            refuses(Code.NOT_READY,()->r.store().applyOpenRecovery(plan,BUDGET));notRecovered(f);assertEquals(0,released(f));
            f.sql.update("UPDATE player_state SET saved_epoch=0,data=? WHERE player_id=1",new byte[]{(byte)0xff});
            refuses(Code.NOT_READY,()->r.store().planOpenRecovery(r.requests().getFirst(),BUDGET));notRecovered(f);
            f.sql.update("DELETE FROM player_state WHERE player_id=1");
            refuses(Code.NOT_READY,()->r.store().planOpenRecovery(r.requests().getFirst(),BUDGET));notRecovered(f);
            assertEquals(0,f.count("player_state"));assertEquals(0,f.count("player_trade_drain"));assertEquals(0,released(f));
        }
    }

}
