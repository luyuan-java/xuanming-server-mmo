package com.game.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.api.AccountLoginService;
import com.game.api.proto.GateNodeInfo;
import com.game.discovery.gate.GateSource;
import com.game.discovery.namespace.GameNamespaceAutoConfiguration;
import com.game.gateway.assign.AssignGateController;
import com.game.gateway.login.LoginController;
import com.game.gateway.ratelimit.RateLimitDecision;
import com.game.gateway.ratelimit.RateLimiter;
import com.game.gateway.serverlist.ServerListController;
import com.game.gateway.store.GatewayStore;
import com.game.gateway.store.ZoneRow;
import com.game.gateway.zone.ZoneHealthProbe;
import com.game.proto.login.LoginResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 独立MVC切片使用固定三边界目录，不用sleep或共享旧测试的目录缓存。 */
@WebMvcTest(controllers = {AssignGateController.class, ServerListController.class, LoginController.class})
@ImportAutoConfiguration(GameNamespaceAutoConfiguration.class)
@Import({GatewayConfiguration.class, GatewayUnsignedZoneHttpTest.Meters.class})
@TestPropertySource(properties = GatewayConfiguration.TOKEN_SECRET_ENV + "=uint32-http-unit-secret")
class GatewayUnsignedZoneHttpTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Meters {
        @Bean SimpleMeterRegistry simpleMeterRegistry() { return new SimpleMeterRegistry(); }
    }

    @Autowired private MockMvc mvc;
    @MockitoBean private GateSource gates;
    @MockitoBean private AccountLoginService login;
    @MockitoBean private RedissonClient redis;
    @MockitoBean private GatewayStore store;
    @MockitoBean private ZoneHealthProbe probe;
    @MockitoBean private RateLimiter limiter;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void fixedDirectory() {
        when(store.zones()).thenReturn(List.of(row(Integer.MAX_VALUE), row(Integer.MIN_VALUE), row(-1)));
        when(probe.health(anyInt())).thenReturn(ZoneHealthProbe.Health.UNKNOWN);
        when(probe.loadLevel(anyInt())).thenReturn(Optional.empty());
        when(limiter.check(anyInt(), any(), any(), any())).thenReturn(RateLimitDecision.pass());
        when(gates.listGates(anyInt())).thenAnswer(invocation -> List.of(GateNodeInfo.newBuilder()
                .setZoneId(invocation.getArgument(0, Integer.class)).setNodeId(1).setInstanceId("unit-gate")
                .setClientHost("127.0.0.1").setClientPort(11001).setPlayerCount(0).build()));
        when(login.login(any())).thenReturn(CompletableFuture.completedFuture(LoginResponse.newBuilder().build()));
    }

    @Test
    void 三边界区号以正整数下发并可分配与登录() throws Exception {
        JsonNode list = json.readTree(mvc.perform(get("/api/server-list")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(list.get("zones").findValues("zone_id").stream().map(JsonNode::longValue).toList())
                .containsExactly(2147483647L, 2147483648L, 4294967295L);
        for (long value : new long[] {2147483647L, 2147483648L, 4294967295L}) {
            mvc.perform(post("/api/assign-gate").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"zone_id\":" + value + "}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
            verify(gates).listGates((int) value);
            var started = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"zone_id\":" + value + ",\"account\":\"a\",\"password\":\"p\"}"))
                    .andExpect(request().asyncStarted()).andReturn();
            mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        }
        verify(login, times(3)).login(any());
    }

    @Test
    void 浮点负数与溢出都在Web解析边界拒绝且无下游副作用() throws Exception {
        for (String invalid : new String[] {"-1", "-0.5", "4294967295.5", "4294967296", "999999999999999999999999"}) {
            for (String path : new String[] {"/api/assign-gate", "/api/queue-status"}) {
                mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"zone_id\":" + invalid + ",\"queue_token\":\"t\"}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400))
                        .andExpect(jsonPath("$.error").value("bad_request"));
            }
            mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"zone_id\":" + invalid + ",\"account\":\"a\"}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(gates, login, redis, store);
    }

    @Test
    void Assign缺省与零仍是未知区_Queue缺省与零仍使用原队列语义() throws Exception {
        for (String body : new String[] {"{}", "{\"zone_id\":0}", "{\"zone_id\":null}"}) {
            mvc.perform(post("/api/assign-gate").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.error").value("zone_not_found"));
        }
        for (String zone : new String[] {"", ",\"zone_id\":0", ",\"zone_id\":null"}) {
            mvc.perform(post("/api/queue-status").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"queue_token\":\"t\"" + zone + "}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(410))
                    .andExpect(jsonPath("$.error").value("queue_disabled"));
        }
        verifyNoInteractions(gates, login, redis);
    }

    @Test
    void Login缺省与null及零仍到原未知区分支_不会以解析失败替代() throws Exception {
        for (String zone : new String[] {"", ",\"zone_id\":null", ",\"zone_id\":0"}) {
            var started = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"account\":\"a\",\"password\":\"p\"" + zone + "}"))
                    .andExpect(request().asyncStarted()).andReturn();
            mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("unknown_zone"));
        }
        verifyNoInteractions(gates, login, redis, store, limiter);
    }
    private static ZoneRow row(int bits) {
        return new ZoneRow(bits, "区" + Integer.toUnsignedString(bits), 0, 100, "", null, false, 0, 0, 0);
    }
}
