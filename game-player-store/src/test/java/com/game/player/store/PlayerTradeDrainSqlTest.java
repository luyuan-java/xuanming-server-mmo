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
}
