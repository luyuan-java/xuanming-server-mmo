package com.game.data.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.game.data.gainblock.GainBlockStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.redisson.client.RedisConnectionException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 走 Spring MVC 的真实参数绑定与 JSON；存储是桩。 */
class GainBlockControllerTest {

    private static final long NOW = 1_800_000_000_000L;

    private final GainBlockStore store = mock(GainBlockStore.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new GainBlockController(store,
            Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC))).build();

    /** 操作人请求头：容器按 ISO-8859-1 看字节，客户端发的是 UTF-8。 */
    private static String header(String operator) {
        return new String(operator.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    @Test
    void 封禁带操作人与时刻_列表与解封() throws Exception {
        when(store.block(GainBlockStore.CURRENCY, 1, "运维甲", NOW, "刷钻"))
                .thenReturn(new GainBlockStore.Entry(1, "运维甲", NOW, "刷钻"));
        when(store.list(GainBlockStore.CURRENCY)).thenReturn(List.of(new GainBlockStore.Entry(1, "运维甲", 5, "刷钻")));
        when(store.unblock(GainBlockStore.CURRENCY, 1)).thenReturn(true);

        mvc.perform(put("/admin/gain-blocks/currency/1").param("reason", "刷钻")
                        .header(AdminAuthFilter.OPERATOR_HEADER, header("运维甲")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.operator").value("运维甲"))
                .andExpect(jsonPath("$.timeMs").value(NOW));
        mvc.perform(get("/admin/gain-blocks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency[0].id").value(1))
                .andExpect(jsonPath("$.currency[0].reason").value("刷钻"));
        mvc.perform(delete("/admin/gain-blocks/currency/1").param("reason", "修好了")
                        .header(AdminAuthFilter.OPERATOR_HEADER, "ops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(true));
    }

    @Test
    void 参数校验_id与原因() throws Exception {
        mvc.perform(put("/admin/gain-blocks/currency/-1").param("reason", "x")).andExpect(status().isBadRequest());
        mvc.perform(put("/admin/gain-blocks/currency/abc").param("reason", "x")).andExpect(status().isBadRequest());
        mvc.perform(put("/admin/gain-blocks/currency/2147483648").param("reason", "x"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/admin/gain-blocks/currency/1")).andExpect(status().isBadRequest());
        mvc.perform(put("/admin/gain-blocks/currency/1").param("reason", " ")).andExpect(status().isBadRequest());
        mvc.perform(put("/admin/gain-blocks/currency/1").param("reason", "a".repeat(257)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/admin/gain-blocks/currency/1").param("reason", "a" + (char) 10 + "b"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/admin/gain-blocks/currency/x").param("reason", "x")).andExpect(status().isBadRequest());
        // 解封也必填原因
        mvc.perform(delete("/admin/gain-blocks/currency/1")).andExpect(status().isBadRequest());
        verify(store, never()).block(anyString(), anyInt(), anyString(), anyLong(), anyString());
        verify(store, never()).unblock(anyString(), anyInt());
    }

    @Test
    void Redis不可用回503_懒创建客户端失败也是503() throws Exception {
        when(store.list(GainBlockStore.CURRENCY)).thenThrow(new RedisConnectionException("连不上"));
        when(store.unblock(eq(GainBlockStore.CURRENCY), anyInt()))
                .thenThrow(new BeanCreationException("redissonClient", "连不上", new RedisConnectionException("连不上")));

        mvc.perform(get("/admin/gain-blocks")).andExpect(status().isServiceUnavailable());
        mvc.perform(delete("/admin/gain-blocks/currency/1").param("reason", "x"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void 不是Redis的异常不伪装成503() {
        when(store.list(GainBlockStore.CURRENCY)).thenThrow(new IllegalStateException("程序错误"));

        assertThatThrownBy(() -> mvc.perform(get("/admin/gain-blocks")))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void 物品类别同样可封可解_列表两类都列_未知类别404() throws Exception {
        when(store.block(GainBlockStore.ITEM, 10, "ops", NOW, "刷药")).thenReturn(new GainBlockStore.Entry(10, "ops", NOW,
                "刷药"));
        when(store.list(GainBlockStore.CURRENCY)).thenReturn(List.of());
        when(store.list(GainBlockStore.ITEM)).thenReturn(List.of(new GainBlockStore.Entry(10, "ops", NOW, "刷药")));
        when(store.unblock(GainBlockStore.ITEM, 10)).thenReturn(false);

        mvc.perform(put("/admin/gain-blocks/item/10").param("reason", "刷药").header(AdminAuthFilter.OPERATOR_HEADER, "ops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10));
        mvc.perform(get("/admin/gain-blocks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").isEmpty())
                .andExpect(jsonPath("$.item[0].id").value(10));
        mvc.perform(delete("/admin/gain-blocks/item/10").param("reason", "修好了"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(false));
        mvc.perform(put("/admin/gain-blocks/pet/1").param("reason", "x")).andExpect(status().isNotFound());
        mvc.perform(delete("/admin/gain-blocks/pet/1").param("reason", "x")).andExpect(status().isNotFound());
    }
}
