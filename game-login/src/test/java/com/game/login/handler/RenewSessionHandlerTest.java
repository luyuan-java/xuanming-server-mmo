package com.game.login.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.SessionContext;
import com.game.common.GameNamespace;
import com.game.common.compliance.AntiAddictionRules;
import com.game.discovery.RedisKeys;
import com.game.login.account.AccountLogin;
import com.game.login.auth.LoginAuthenticator;
import com.game.login.compliance.AntiAddictionGate;
import com.game.login.compliance.NppaLedger;
import com.game.login.compliance.NppaRedisKeys;
import com.game.login.dispatch.HandlerReply;
import com.game.login.dispatch.InFlightKeys;
import com.game.login.session.LoginDevices;
import com.game.login.session.RedisLoginDevices;
import com.game.login.token.LoginTokens;
import com.game.player.store.PlayerStore;
import com.game.proto.login.RenewSessionRequest;
import com.game.proto.login.RenewSessionResponse;
import com.game.table.LoginErrorTip;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

/** 验证 RenewSession 周期续租、未成年人越过可玩截止兜底踢线及 RedisLoginDevices 单键脚本集群槽安全。 */
class RenewSessionHandlerTest {

    @Test
    void 正常续租顺延防沉迷账本并返回三分之一TTL间隔() {
        RedissonClient redis = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redis.getScript(any(Codec.class))).thenReturn(script);
        long futureDeadline = 2_000_000L;
        when(script.eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE), anyList(), any(), any()))
                .thenReturn(Long.toString(futureDeadline));

        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC);
        NppaLedger ledger = new NppaLedger(redis, new NppaRedisKeys(new RedisKeys(GameNamespace.of("game"))), Duration.ofMinutes(30));
        AntiAddictionGate aa = new AntiAddictionGate(AntiAddictionRules.Calendar.of(List.of()), Set.of(), null, ledger, clock);
        LoginDevices devices = mock(LoginDevices.class);
        when(devices.admit("u1", "gate-1/100")).thenReturn(LoginDevices.Admission.ADMITTED);

        AccountLogin accountLogin = new AccountLogin(mock(LoginAuthenticator.class), mock(PlayerStore.class),
                mock(LoginTokens.class), devices, new InFlightKeys<>(), aa, false);
        RenewSessionHandler handler = new RenewSessionHandler(accountLogin, Duration.ofMinutes(30));

        SessionContext session = SessionContext.newBuilder()
                .setGateNodeId(1).setGateInstanceId("gate-1").setSessionId(100).setAccount("u1").build();
        HandlerReply reply = handler.handle(session, RenewSessionRequest.getDefaultInstance()).join();
        RenewSessionResponse body = (RenewSessionResponse) reply.body().orElseThrow();
        assertThat(body.getRenewed()).isTrue();
        assertThat(body.getRenewAfterSeconds()).isEqualTo(600);
        assertThat(reply.directives()).isEmpty();
        verify(devices).admit("u1", "gate-1/100");
    }

    @Test
    void 未成年人超过可玩截止仍在线_续租返回兜底CloseSession2049并收尾账本() {
        RedissonClient redis = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redis.getScript(any(Codec.class))).thenReturn(script);
        long passedDeadline = 1_000_000L;
        when(script.eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE), anyList(), any(), any()))
                .thenReturn(Long.toString(passedDeadline));

        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_005_000L), ZoneOffset.UTC);
        NppaLedger ledger = new NppaLedger(redis, new NppaRedisKeys(new RedisKeys(GameNamespace.of("game"))), Duration.ofMinutes(30));
        AntiAddictionGate aa = new AntiAddictionGate(AntiAddictionRules.Calendar.of(List.of()), Set.of(), null, ledger, clock);
        LoginDevices devices = mock(LoginDevices.class);

        AccountLogin accountLogin = new AccountLogin(mock(LoginAuthenticator.class), mock(PlayerStore.class),
                mock(LoginTokens.class), devices, new InFlightKeys<>(), aa, false);
        RenewSessionHandler handler = new RenewSessionHandler(accountLogin, Duration.ofMinutes(30));

        // 已在游戏内（playerId > 0）不再触碰大厅设备名单，仍续期并复核防沉迷截止
        SessionContext inGame = SessionContext.newBuilder()
                .setGateNodeId(1).setGateInstanceId("gate-1").setSessionId(101).setAccount("minor1").setPlayerId(5001L).build();
        HandlerReply reply = handler.handle(inGame, RenewSessionRequest.getDefaultInstance()).join();
        RenewSessionResponse body = (RenewSessionResponse) reply.body().orElseThrow();
        assertThat(body.getRenewed()).isFalse();
        assertThat(reply.directives()).hasSize(1);
        assertThat(reply.directives().getFirst().hasCloseSession()).isTrue();
        assertThat(reply.directives().getFirst().getCloseSession().getTipId())
                .isEqualTo(LoginErrorTip.login_error.kLoginMinorPlayTimeOver_VALUE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void RedisLoginDevices单键脚本每次只触碰1个Key保证RedisCluster无CROSSSLOT() {
        RedissonClient redis = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        RBucket<Object> bucket = mock(RBucket.class);
        when(redis.getBucket(anyString(), any(Codec.class))).thenReturn(bucket);
        when(bucket.get()).thenReturn(null);
        when(redis.getScript(any(Codec.class))).thenReturn(script);
        when(script.eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.INTEGER), anyList(), any(Object[].class)))
                .thenReturn(1L);

        RedisLoginDevices devices = new RedisLoginDevices(
                redis, new RedisKeys(GameNamespace.of("game")),
                Clock.fixed(Instant.ofEpochMilli(1000L), ZoneOffset.UTC),
                Duration.ofMinutes(30), 3);

        assertThat(devices.admit("acc1", "gate-1/7")).isEqualTo(LoginDevices.Admission.ADMITTED);
        assertThat(devices.bind("acc1", "gate-1/7", "")).isTrue();

        ArgumentCaptor<List<Object>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(script, org.mockito.Mockito.atLeast(3))
                .eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.INTEGER), keysCaptor.capture(), any(Object[].class));
        for (List<Object> keys : keysCaptor.getAllValues()) {
            assertThat(keys).as("Redis Cluster 安全要求每条 Lua 脚本只传 1 个 key").hasSize(1);
        }
    }
}
