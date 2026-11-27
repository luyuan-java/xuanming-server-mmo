package com.game.login.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import com.game.player.store.PlayerStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.config.Config;

/**
 * 核验防沉迷会话账本（2026-11-26 截止后保留兜底修复）、{@link LoginAdminRpcService} 调用方隔离及小游戏免实名缺省配置。
 */
class NppaLedgerBackstopAndGuardTest {

    @Test
    @SuppressWarnings("unchecked")
    void 账本Lua脚本与读截止时刻符合20261126防兜底失效口径() {
        String prelude = NppaLedger.luaPreludeForTest();
        String sweep = NppaLedger.sweepScriptForTest();
        // 1. lease_end 仅在截止时刻未到时封顶（cap_ms > now_ms and cap_ms < e）
        assertThat(prelude).contains("if cap_ms > now_ms and cap_ms < e then e = cap_ms end");
        // 2. 清扫命中 lease_ended == cap 时保留 "||cap" 登记并续一个租约，普通分支清掉兜底残留时不计入收尾数
        assertThat(sweep).contains("if cap > 0 and lease_ended == cap then");
        assertThat(sweep).contains("redis.call('HSET', KEYS[1], conn, '||' .. num(cap))");
        assertThat(sweep).contains("redis.call('ZADD', KEYS[2], num(now_ms + lease), conn)");
        assertThat(sweep).contains("if si ~= '' or cap == 0 or lease_ended < cap then ended = ended + 1 end");

        // 3. readPlayDeadlineMs 解析完整登记与清扫保留的 "||cap" 条目，Redis 异常不吞
        RedissonClient redis = mock(RedissonClient.class);
        RMap<String, String> map = mock(RMap.class);
        when(redis.<String, String>getMap(anyString(), any(Codec.class))).thenReturn(map);
        NppaLedger ledger = new NppaLedger(redis, new NppaRedisKeys(new RedisKeys(GameNamespace.of("game"))),
                Duration.ofMinutes(30));

        when(map.get("gate-1/1")).thenReturn("si1|pi1|1800000000000");
        assertThat(ledger.readPlayDeadlineMs("gate-1/1")).isEqualTo(1_800_000_000_000L);

        when(map.get("gate-1/2")).thenReturn("||1800000000999");
        assertThat(ledger.readPlayDeadlineMs("gate-1/2")).isEqualTo(1_800_000_000_999L);

        when(map.get("gate-1/3")).thenReturn(null);
        assertThat(ledger.readPlayDeadlineMs("gate-1/3")).isZero();

        when(map.get("gate-1/4")).thenThrow(new IllegalStateException("redis unreachable"));
        assertThatThrownBy(() -> ledger.readPlayDeadlineMs("gate-1/4"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void realNamePiOfPlayer只放行gamePayment远程调用_其他服务拒绝() {
        PlayerStore store = mock(PlayerStore.class);
        when(store.findRealNamePiByPlayerId(77L)).thenReturn("pi_val");
        LoginAdminRpcService service = new LoginAdminRpcService(store, null, null, Runnable::run);

        try {
            // 缺失与空字符串 remote.application 必须 fail-closed 拒绝
            RpcContext.removeServerAttachment();
            assertThatThrownBy(() -> service.realNamePiOfPlayer(77L).join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RpcException.class);

            RpcContext.getServerAttachment().setAttachment(CommonConstants.REMOTE_APPLICATION_KEY, "");
            assertThatThrownBy(() -> service.realNamePiOfPlayer(77L).join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RpcException.class);

            RpcContext.getServerAttachment().setAttachment(CommonConstants.REMOTE_APPLICATION_KEY, "game-trade");
            assertThatThrownBy(() -> service.realNamePiOfPlayer(77L).join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RpcException.class);

            RpcContext.getServerAttachment().setAttachment(CommonConstants.REMOTE_APPLICATION_KEY, "game-payment");
            assertThat(service.realNamePiOfPlayer(77L).join()).isEqualTo("pi_val");
        } finally {
            RpcContext.removeServerAttachment();
        }
    }

    @Test
    void 小游戏平台托管渠道缺省包含微信与抖音小游戏() {
        ComplianceProperties props = new ComplianceProperties();
        assertThat(props.getAntiAddiction().getPlatformManagedAuthTypes())
                .containsExactly("wechat_minigame", "douyin_minigame");
    }

    @Test
    void 真Redis验证截止后先清扫再续租仍拿到截止时刻且过一个租约自动清理() throws Exception {
        String addr = System.getProperty("game.it.redis");
        Assumptions.assumeTrue(addr != null && !addr.isBlank(), "需 -Dgame.it.redis 运行真 Redis Lua 回归");
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(addr);
        RedissonClient redis = Redisson.create(cfg);
        try {
            NppaRedisKeys keys = new NppaRedisKeys(new RedisKeys(GameNamespace.of("itnppabackstop")));
            NppaLedger ledger = new NppaLedger(redis, keys, Duration.ofMillis(400));
            String conn = "gate-it/" + System.nanoTime();
            long capMs = System.currentTimeMillis() + 80L;
            ledger.begin(conn, "si_backstop", "pi_backstop", capMs);
            Thread.sleep(150L);

            // 1. 截止后清扫：补报 1 条下线、返回收尾 1，但保留 "||cap" 登记供续租兜底
            int swept1 = ledger.sweep();
            assertThat(swept1).isGreaterThanOrEqualTo(1);
            List<NppaLedger.Claimed> events = ledger.claim(16);
            assertThat(events).anyMatch(c -> c.valid() && c.behavior().bt() == NppaClient.Behavior.LOGOUT);
            ledger.ack(events);

            // 2. 续租仍返回原截止时刻（login 兜底踢线可触发）
            assertThat(ledger.renew(conn)).isEqualTo(capMs);
            assertThat(ledger.readPlayDeadlineMs(conn)).isEqualTo(capMs);

            // 3. 不再续租，过一个租约后再清扫 -> 删除兜底条目且不计入收尾数、不再报第二条下线
            Thread.sleep(500L);
            int swept2 = ledger.sweep();
            assertThat(swept2).isZero();
            assertThat(ledger.renew(conn)).isZero();
            assertThat(ledger.claim(16)).isEmpty();
        } finally {
            redis.shutdown();
        }
    }
}
