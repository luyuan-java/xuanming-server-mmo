package com.game.login.hosted;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.OwnerTakeover;
import com.game.api.proto.SessionContext;
import com.game.login.account.AccountLogin;
import com.game.login.handler.RenewSessionHandler;
import com.game.login.hosted.HostedHomingReconciler.HomingRecord;
import com.game.login.hosted.HostedHomingReconciler.PlacementOutcome;
import com.game.login.hosted.HostedHomingReconciler.ReconcileResult;
import com.game.proto.login.RenewSessionRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 批次 4T-2B：离线托管归位对账（跨区路由到角色所属 zone、无托管时对齐月卡 TTL、修订失效清理、keep_held 协议）。 */
class HostedHomingReconcilerTest {

    @Test
    void 托管归位_有心跳跳过_缺心跳对齐月卡TTL并路由到角色所在zone_修订失效删归位记录() {
        InMemoryStore store = new InMemoryStore();
        List<String> calls = new ArrayList<>();
        PlacementOutcome[] nextOutcome = {PlacementOutcome.PLACED};
        HostedHomingReconciler reconciler = new HostedHomingReconciler(store,
                (targetZoneId, playerId, ownerRevision, sessionId, loginGeneration) -> {
                    calls.add(targetZoneId + ":" + playerId + ":" + ownerRevision + ":" + sessionId + ":" + loginGeneration);
                    return nextOutcome[0];
                });

        long now = 1_800_000_000L;
        HomingRecord rec = new HomingRecord(7, 42L, 101, 999L, now + 300L);
        assertThat(HomingRecord.decode(rec.encode())).contains(rec);
        store.homing.put(5001L, rec);
        store.afkPass.put(5001L, now + 3600L); // 月卡已续费延长

        // 1. 心跳仍在 -> 已有节点托管，不发归位请求
        store.presence.put(5001L, true);
        assertThat(reconciler.reconcileOnRenew(5001L, 101, 999L, now)).isEqualTo(ReconcileResult.ALREADY_HOSTED);
        assertThat(calls).isEmpty();

        // 2. 心跳消失 + 月卡已续费 -> 先把归位记录 TTL 对齐到新月卡到期，再向角色所在 zone=7 申请托管落点（用归位里的 ownerRevision=42）
        store.presence.remove(5001L);
        assertThat(reconciler.reconcileOnRenew(5001L, 101, 999L, now)).isEqualTo(ReconcileResult.HOMING_PLACED);
        assertThat(store.homing.get(5001L).expiresAtSec()).isEqualTo(now + 3600L);
        assertThat(store.lastAlignedTtlSec).isEqualTo(3600L);
        assertThat(calls).containsExactly("7:5001:42:101:999");

        // 3. 会话或登录代次不匹配 -> 跳过
        assertThat(reconciler.reconcileOnRenew(5001L, 102, 999L, now)).isEqualTo(ReconcileResult.SESSION_MISMATCH);
        assertThat(reconciler.reconcileOnRenew(5001L, 101, 1000L, now)).isEqualTo(ReconcileResult.SESSION_MISMATCH);

        // 4. 月卡剩余不足 60 秒 -> 跳过
        store.afkPass.put(5001L, now + 59L);
        assertThat(reconciler.reconcileOnRenew(5001L, 101, 999L, now)).isEqualTo(ReconcileResult.PASS_INSUFFICIENT);

        // 5. 归属修订失效（交易/封存/换号）-> 删除归位记录、后续不再归位
        store.afkPass.put(5001L, now + 3600L);
        nextOutcome[0] = PlacementOutcome.OWNER_REVISION_INVALID;
        assertThat(reconciler.reconcileOnRenew(5001L, 101, 999L, now)).isEqualTo(ReconcileResult.HOMING_INVALIDATED);
        assertThat(store.homing).doesNotContainKey(5001L);
        assertThat(reconciler.reconcileOnRenew(5001L, 101, 999L, now)).isEqualTo(ReconcileResult.NO_HOMING);
    }

    @Test
    void keepHeld字段可正常序列化并接入RenewSessionHandler() {
        OwnerTakeover keepHeldMsg = OwnerTakeover.newBuilder().setPlayerId(5001L).setOwnerEpoch(9L).setKeepHeld(true).build();
        assertThat(keepHeldMsg.getKeepHeld()).isTrue();

        InMemoryStore store = new InMemoryStore();
        long now = Math.floorDiv(System.currentTimeMillis(), 1000L);
        store.homing.put(8001L, new HomingRecord(3, 88L, 205, 777L, now + 600L));
        store.afkPass.put(8001L, now + 600L);
        List<Integer> routedZones = new ArrayList<>();
        HostedHomingReconciler reconciler = new HostedHomingReconciler(store,
                (z, p, rev, sid, gen) -> {
                    routedZones.add(z);
                    return PlacementOutcome.PLACED;
                });
        com.game.login.session.LoginDevices devices = org.mockito.Mockito.mock(com.game.login.session.LoginDevices.class);
        org.mockito.Mockito.when(devices.admit(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(com.game.login.session.LoginDevices.Admission.ADMITTED);
        AccountLogin login = new AccountLogin(
                org.mockito.Mockito.mock(com.game.login.auth.LoginAuthenticator.class),
                org.mockito.Mockito.mock(com.game.player.store.PlayerStore.class),
                org.mockito.Mockito.mock(com.game.login.token.LoginTokens.class),
                devices, new com.game.login.dispatch.InFlightKeys<>(), null, false);
        SessionContext ctx = SessionContext.newBuilder()
                .setGateNodeId(1).setGateInstanceId("gate-1").setSessionId(205).setZoneId(1).setAccount("acc-homing")
                .setPlayerId(8001L).setOwnerRevision(777L).build();
        RenewSessionHandler handler = new RenewSessionHandler(login, Duration.ofMinutes(5))
                .withHostedHomingReconciler(reconciler);

        var reply = handler.handle(ctx, RenewSessionRequest.getDefaultInstance()).join();
        assertThat(reply.body().orElseThrow()).isInstanceOf(com.game.proto.login.RenewSessionResponse.class);
        assertThat(((com.game.proto.login.RenewSessionResponse) reply.body().orElseThrow()).getRenewed()).isTrue();
        assertThat(routedZones).as("即使会话在 zone 1，归位也必须路由到角色归位记录的 zone 3").containsExactly(3);
    }

    private static final class InMemoryStore implements HostedHomingReconciler.Store {
        final Map<Long, Boolean> presence = new HashMap<>();
        final Map<Long, HomingRecord> homing = new HashMap<>();
        final Map<Long, Long> afkPass = new HashMap<>();
        long lastAlignedTtlSec;

        @Override public boolean hasHostedPresence(long playerId) { return Boolean.TRUE.equals(presence.get(playerId)); }
        @Override public Optional<HomingRecord> readHoming(long playerId) { return Optional.ofNullable(homing.get(playerId)); }
        @Override public long readAfkPassExpiresAtSec(long playerId) { return afkPass.getOrDefault(playerId, 0L); }
        @Override public void alignHomingTtl(long playerId, HomingRecord updated, long ttlSeconds) {
            homing.put(playerId, updated);
            lastAlignedTtlSec = ttlSeconds;
        }
        @Override public void deleteHoming(long playerId) { homing.remove(playerId); }
    }
}
