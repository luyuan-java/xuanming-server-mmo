package com.game.trade.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.asset.AssetOpSignatures;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.common.deadline.Deadline;
import com.game.common.workshard.WorkShard;
import com.game.discovery.asset.ReliableAssetDelivery;
import com.game.player.store.asset.AssetSeqState;
import com.game.player.store.asset.PersistedAssetLedger;
import com.game.player.store.asset.PersistedAssetLedgerReader;
import com.game.proto.trade.TradeCommerceLeg;
import com.game.proto.trade.TradeCommerceWorkflow;
import com.game.proto.trade.TradeRoleIdentity;
import com.game.trade.store.pb.TradeAssetOpRow;
import com.game.trade.store.pb.TradeCommerceRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-06（合同 64 片 HRW 分工、无变化不重存不改版本）与 2026-11-10（事件唤醒与 30 秒兜底复查）。 */
class TradeShardingAndEventWakeTest {

    private static final CommerceRules.Policy POLICY = new CommerceRules.Policy(Set.of(10), Set.of(), Set.of(1), false, List.of());

    @Test
    void nextCheckAtMs对纯等待合同排到截止上限一小时_有在途腿或待子单排三十秒兜底() {
        long now = 1_000_000L;
        // 截止在 20 分钟后 -> 排到截止时刻
        TradeCommerceRow soon = TradeCommerceRow.newBuilder().setExpiresMs(now + 1_200_000L).build();
        assertThat(CommerceEngine.nextCheckAtMs(soon, TradeCommerceWorkflow.newBuilder().setStage("open").build(), now))
                .isEqualTo(now + 1_200_000L);
        assertThat(CommerceEngine.nextCheckAtMs(soon, TradeCommerceWorkflow.newBuilder().setStage("market_open").build(), now))
                .isEqualTo(now + 1_200_000L);
        assertThat(CommerceEngine.nextCheckAtMs(soon, TradeCommerceWorkflow.newBuilder().setStage("wanted_open").build(), now))
                .isEqualTo(now + 1_200_000L);
        assertThat(CommerceEngine.nextCheckAtMs(soon, TradeCommerceWorkflow.newBuilder().setStage("lot_open").build(), now))
                .isEqualTo(now + 1_200_000L);
        assertThat(CommerceEngine.nextCheckAtMs(soon, TradeCommerceWorkflow.newBuilder().setStage("confirm").build(), now))
                .isEqualTo(now + 1_200_000L);

        // 截止在 5 小时后 -> 最远 1 小时复查
        TradeCommerceRow far = TradeCommerceRow.newBuilder().setExpiresMs(now + 18_000_000L).build();
        assertThat(CommerceEngine.nextCheckAtMs(far, TradeCommerceWorkflow.newBuilder().setStage("open").build(), now))
                .isEqualTo(now + CommerceEngine.IDLE_RECHECK_MAX_MS);

        // 在途腿 / 等子单 / 已发起撤单 -> 30 秒兜底（靠事件唤醒提前）
        assertThat(CommerceEngine.nextCheckAtMs(far, TradeCommerceWorkflow.newBuilder().setStage("deliver").build(), now))
                .isEqualTo(now + CommerceEngine.INFLIGHT_FALLBACK_RECHECK_MS);
        assertThat(CommerceEngine.nextCheckAtMs(far, TradeCommerceWorkflow.newBuilder().setStage("market_wait").build(), now))
                .isEqualTo(now + CommerceEngine.INFLIGHT_FALLBACK_RECHECK_MS);
        assertThat(CommerceEngine.nextCheckAtMs(far,
                TradeCommerceWorkflow.newBuilder().setStage("open").setCancelRequested(true).build(), now))
                .isEqualTo(now + CommerceEngine.INFLIGHT_FALLBACK_RECHECK_MS);
    }

    @Test
    void 合同推进无业务变化时只推迟调度键_不重存不改版本号() throws Exception {
        CommerceStore store = mock(CommerceStore.class);
        long now = 2_000_000L;
        CommerceEngine engine = new CommerceEngine(store, new AtomicLong(500)::incrementAndGet, POLICY, () -> now);

        TradeCommerceWorkflow wf = TradeCommerceWorkflow.newBuilder()
                .setStage("open")
                .setSellerIdentity(TradeRoleIdentity.newBuilder().setAccount("acc-1").setOwnerRevision(1).build())
                .build();
        TradeCommerceRow current = TradeCommerceRow.newBuilder()
                .setCommerceId(77L)
                .setKind(3)
                .setVersion(5L)
                .setStatus(CommerceRules.WAITING)
                .setSellerPlayerId(1001L)
                .setExpiresMs(now + 500_000L)
                .setWorkflowProto(wf.toByteString())
                .build();

        Connection conn = mockConnectionForOwners(now, List.of("acc-1"), List.of(1L));
        when(store.transaction(any(), any())).thenAnswer(inv -> {
            CommerceStore.Transaction<?> tx = inv.getArgument(1);
            return tx.run(conn);
        });
        when(store.lock(conn, 77L)).thenReturn(current);

        TradeCommerceRow result = engine.advance(current, Deadline.after(5000));
        assertThat(result).isEqualTo(current);
        assertThat(result.getVersion()).isEqualTo(5L);
        verify(store).rescheduleUnchanged(conn, 77L, 5L, now + 500_000L);
        verify(store, never()).save(any(), any());
    }

    @Test
    void 市场成交子单结案后在事务外批量唤醒买卖两张母单() throws Exception {
        CommerceStore store = mock(CommerceStore.class);
        long now = 3_000_000L;
        CommerceEngine engine = new CommerceEngine(store, new AtomicLong(600)::incrementAndGet, POLICY, () -> now);

        TradeCommerceWorkflow wf = TradeCommerceWorkflow.newBuilder()
                .setStage("deliver")
                .setSellerIdentity(TradeRoleIdentity.newBuilder().setAccount("acc-s").setOwnerRevision(1).build())
                .setBuyerIdentity(TradeRoleIdentity.newBuilder().setAccount("acc-b").setOwnerRevision(1).build())
                .addLegs(TradeCommerceLeg.newBuilder().setRole("buyer_credit").setPlayerId(2002L).setStream(4)
                        .setOpId(901L).setOutcome(2).build())
                .addLegs(TradeCommerceLeg.newBuilder().setRole("seller_credit").setPlayerId(2001L).setStream(4)
                        .setOpId(902L).setOutcome(2).build())
                .build();
        TradeCommerceRow child = TradeCommerceRow.newBuilder()
                .setCommerceId(888L)
                .setKind(4)
                .setSide(0)
                .setParentId(101L)
                .setCounterpartyId(102L)
                .setVersion(3L)
                .setStatus(CommerceRules.DELIVERING)
                .setSellerPlayerId(2001L)
                .setBuyerPlayerId(2002L)
                .setWorkflowProto(wf.toByteString())
                .build();

        Connection conn = mockConnectionForOwners(now, List.of("acc-s", "acc-b"), List.of(1L, 1L));
        when(store.transaction(any(), any())).thenAnswer(inv -> {
            CommerceStore.Transaction<?> tx = inv.getArgument(1);
            return tx.run(conn);
        });
        when(store.lock(conn, 888L)).thenReturn(child);
        when(store.save(eq(conn), any())).thenAnswer(inv -> {
            TradeCommerceRow.Builder b = inv.getArgument(1);
            return b.setVersion(b.getVersion() + 1).build();
        });

        TradeCommerceRow settled = engine.advance(child, Deadline.after(5000));
        assertThat(settled.getStatus()).isEqualTo(CommerceRules.SETTLED);
        verify(store).wakeMany(argThat(ids -> ids.containsAll(List.of(101L, 102L)) && ids.size() == 2), eq(now), any());
    }

    @Test
    void 多副本分片成员表驱动按片扫描_资产指令终结后在事务提交外唤醒所属合同() throws Exception {
        CommerceStore store = mock(CommerceStore.class);
        ReliableAssetDelivery delivery = mock(ReliableAssetDelivery.class);
        PersistedAssetLedgerReader ledger = mock(PersistedAssetLedgerReader.class);
        long now = 4_000_000L;
        CommerceEngine engine = new CommerceEngine(store, new AtomicLong(700)::incrementAndGet, POLICY, () -> now);

        WorkShard.Membership membership = new WorkShard.Membership(30_000L, () -> now);
        membership.setSelf("trade-a");
        membership.updateMembers(List.of("trade-a", "trade-b"));

        AssetBundle bundle = AssetBundle.newBuilder().build();
        TradeAssetOpRow op = TradeAssetOpRow.newBuilder()
                .setOpId(5001L)
                .setPlayerId(3001L)
                .setStream(4)
                .setStreamEpoch(1L)
                .setSeq(1L)
                .setStatus(1)
                .setRefKind(2)
                .setRefId(9001L)
                .setPayload(bundle.toByteString())
                .build();

        when(store.pendingOps(eq(now), eq(50), argThat(f -> !f.isAll() && !f.isNone() && f.sql().startsWith("MOD(player_id,64) IN("))))
                .thenReturn(List.of(op));
        when(store.due(eq(now), eq(100), argThat(f -> !f.isAll() && !f.isNone() && f.sql().startsWith("MOD(commerce_id,64) IN("))))
                .thenReturn(List.of());
        when(store.ackDue(eq(0L), eq(50), argThat(f -> !f.isAll() && !f.isNone() && f.sql().startsWith("MOD(op_id,64) IN("))))
                .thenReturn(List.of());

        // 模拟 claim 与 send 两次事务：claim 返回带 leaseToken 的 op，finalize 返回 true
        Connection txConn = mock(Connection.class);
        PreparedStatement updateStmt = mock(PreparedStatement.class);
        when(txConn.prepareStatement(anyString())).thenReturn(updateStmt);
        when(updateStmt.executeUpdate()).thenReturn(1);
        when(store.ops(eq(txConn), eq("op_id=? FOR UPDATE"), eq(5001L))).thenReturn(List.of(op));
        doAnswer(inv -> {
            CommerceStore.Transaction<?> tx = inv.getArgument(1);
            return tx.run(txConn);
        }).when(store).transaction(any(), any());

        when(delivery.deliver(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(
                ReliableAssetDelivery.Delivery.answered(AssetOpResponse.newBuilder()
                        .setOutcome(AssetOutcome.ASSET_OUTCOME_APPLIED).build())));

        AssetOpRequest req = CommerceRuntime.request(op);
        PersistedAssetLedger proof = mock(PersistedAssetLedger.class);
        when(proof.outcomeOf(4, 1L, 1L)).thenReturn(new PersistedAssetLedger.Outcome(AssetSeqState.APPLIED, false, 0));
        when(proof.identityMatches(4, 1L, 1L, 5001L, AssetOpSignatures.requestDigest(req))).thenReturn(true);
        when(proof.receipt(4, 1L, 1L)).thenReturn(null);
        when(ledger.read(3001L)).thenReturn(new PersistedAssetLedgerReader.Read.Loaded(proof));

        try (var runtime = new CommerceRuntime(engine, delivery, ledger).withShardMembership(membership)) {
            runtime.tick(Deadline.after(10_000));
        }

        verify(store).pendingOps(eq(now), eq(50), argThat(f -> f.sql().startsWith("MOD(player_id,64) IN(")));
        verify(store).due(eq(now), eq(100), argThat(f -> f.sql().startsWith("MOD(commerce_id,64) IN(")));
        verify(store).ackDue(eq(0L), eq(50), argThat(f -> f.sql().startsWith("MOD(op_id,64) IN(")));
        verify(store).wake(eq(9001L), eq(now), any());
    }

    private static Connection mockConnectionForOwners(long sqlNow, List<String> accounts, List<Long> revs) throws Exception {
        Connection c = mock(Connection.class);
        when(c.unwrap(com.game.common.sql.AuthorityConnection.Fence.class))
                .thenReturn(mock(com.game.common.sql.AuthorityConnection.Fence.class));
        Statement st = mock(Statement.class);
        ResultSet nowRs = mock(ResultSet.class);
        when(c.createStatement()).thenReturn(st);
        when(st.executeQuery(anyString())).thenReturn(nowRs);
        when(nowRs.next()).thenReturn(true);
        when(nowRs.getLong(1)).thenReturn(sqlNow);

        PreparedStatement metaStmt = mock(PreparedStatement.class);
        ResultSet metaRs = mock(ResultSet.class);
        when(metaStmt.executeQuery()).thenReturn(metaRs);
        when(metaRs.next()).thenReturn(true);
        when(metaRs.getString(1)).thenReturn("11111111-1111-1111-1111-111111111111");
        when(metaRs.getString(2)).thenReturn("1");
        when(metaRs.getInt(3)).thenReturn(com.game.common.player.PlayerRouteAuthority.FENCE_SHARDS);

        PreparedStatement zoneStmt = mock(PreparedStatement.class);
        ResultSet zoneRs = mock(ResultSet.class);
        when(zoneStmt.executeQuery()).thenReturn(zoneRs);
        when(zoneRs.next()).thenReturn(false);

        PreparedStatement ownerStmt = mock(PreparedStatement.class);
        ResultSet ownerRs = mock(ResultSet.class);
        when(ownerStmt.executeQuery()).thenReturn(ownerRs);
        java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger(-1);
        when(ownerRs.next()).thenAnswer(inv -> idx.incrementAndGet() < accounts.size());
        when(ownerRs.getString(1)).thenAnswer(inv -> accounts.get(idx.get()));
        when(ownerRs.getString(2)).thenAnswer(inv -> Long.toUnsignedString(revs.get(idx.get())));
        when(ownerRs.getString(3)).thenReturn("0");

        when(c.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0, String.class);
            if (sql.contains("FROM route_meta")) {
                return metaStmt;
            }
            if (sql.contains("SELECT zone_id FROM player")) {
                return zoneStmt;
            }
            return ownerStmt;
        });
        return c;
    }
}
