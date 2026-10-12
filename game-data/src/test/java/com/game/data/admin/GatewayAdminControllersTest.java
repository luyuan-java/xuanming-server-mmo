package com.game.data.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.game.gateway.store.AnnouncementRow;
import com.game.gateway.store.GatewayStore;
import com.game.gateway.store.WhitelistRow;
import com.game.gateway.store.ZoneManualStatus;
import com.game.gateway.store.ZoneRow;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 区服目录 / 公告 / 白名单的运维接口：snake_case JSON、400 / 404 / 204 的口径；存储是桩（真 SQL 见 GatewayStoreSqlTest）。 */
class GatewayAdminControllersTest {

    private final GatewayStore store = mock(GatewayStore.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ZoneAdminController(store),
            new AnnouncementAdminController(store), new WhitelistAdminController(store)).build();

    private static final ZoneRow ZONE = new ZoneRow(1, "一区", 1, 5000, "停服", 1_900_000_000L, true, 2, 10, 20);

    @Test
    void 区服_列表与查询_键名snake_case_不存在404() throws Exception {
        when(store.zones()).thenReturn(List.of(ZONE));
        when(store.zone(1)).thenReturn(Optional.of(ZONE));
        when(store.zone(2)).thenReturn(Optional.empty());
        mvc.perform(get("/admin/zones")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].zone_id").value(1))
                .andExpect(jsonPath("$[0].manual_status").value(1))
                .andExpect(jsonPath("$[0].maintenance_msg").value("停服"))
                .andExpect(jsonPath("$[0].open_time").value(1_900_000_000L))
                .andExpect(jsonPath("$[0].sort_order").value(2))
                .andExpect(jsonPath("$[0].created_at").value(10));
        mvc.perform(get("/admin/zones/1")).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("一区"));
        mvc.perform(get("/admin/zones/2")).andExpect(status().isNotFound());
    }

    @Test
    void 区服_创建缺字段400_缺省值_非法参数400() throws Exception {
        mvc.perform(post("/admin/zones").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"一区\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/zones").contentType(MediaType.APPLICATION_JSON).content("{\"zone_id\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/zones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone_id\":0,\"name\":\"零区\"}"))
                .andExpect(status().isBadRequest());
        verify(store, never()).upsertZone(any());

        when(store.upsertZone(new ZoneRow(5, "五区", 0, 5000, "", null, false, 0, 0, 0))).thenReturn(ZONE);
        mvc.perform(post("/admin/zones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone_id\":5,\"name\":\"五区\"}"))
                .andExpect(status().isOk());

        when(store.upsertZone(new ZoneRow(6, "六区", 9, 5000, "", null, false, 0, 0, 0)))
                .thenThrow(new IllegalArgumentException("manual_status 只能是 0–3"));
        mvc.perform(post("/admin/zones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone_id\":6,\"name\":\"六区\",\"manual_status\":9}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 区服_改不存在404_缺name400_删204或404_维护与开放() throws Exception {
        when(store.updateZone(eq(7), any())).thenReturn(Optional.empty());
        mvc.perform(put("/admin/zones/7").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"七区\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/admin/zones/7").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        when(store.deleteZone(1)).thenReturn(true);
        mvc.perform(delete("/admin/zones/1")).andExpect(status().isNoContent());
        mvc.perform(delete("/admin/zones/2")).andExpect(status().isNotFound());

        when(store.setZoneStatus(1, ZoneManualStatus.MAINTENANCE, "14:00 恢复")).thenReturn(Optional.of(ZONE));
        mvc.perform(post("/admin/zones/1/maintenance").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maintenance_msg\":\"14:00 恢复\"}"))
                .andExpect(status().isOk());
        when(store.setZoneStatus(eq(1), eq(ZoneManualStatus.MAINTENANCE), isNull())).thenReturn(Optional.of(ZONE));
        mvc.perform(post("/admin/zones/1/maintenance")).andExpect(status().isOk());
        when(store.setZoneStatus(anyInt(), eq(ZoneManualStatus.OPEN), eq(""))).thenReturn(Optional.empty());
        mvc.perform(post("/admin/zones/9/open")).andExpect(status().isNotFound());
    }

    @Test
    void 公告_新建忽略请求里的id_删除恒204() throws Exception {
        when(store.createAnnouncement("维护", "今晚", "maintenance", 100L, null))
                .thenReturn(new AnnouncementRow(3, "维护", "今晚", "maintenance", 100L, null, 5));
        mvc.perform(post("/admin/announcements").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":1,\"title\":\"维护\",\"content\":\"今晚\",\"type\":\"maintenance\",\"start_time\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.start_time").value(100))
                .andExpect(jsonPath("$.created_at").value(5));
        when(store.createAnnouncement(isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenThrow(new IllegalArgumentException("title 必填"));
        mvc.perform(post("/admin/announcements").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/admin/announcements/42")).andExpect(status().isNoContent());
        verify(store).deleteAnnouncement(42);
    }

    @Test
    void 白名单_缺字段400_加入回库里的行_移出恒204() throws Exception {
        mvc.perform(post("/admin/whitelist").contentType(MediaType.APPLICATION_JSON).content("{\"zone_id\":1}"))
                .andExpect(status().isBadRequest());
        when(store.addWhitelist(1, "robot_0001", "内测")).thenReturn(new WhitelistRow(1, "robot_0001", "内测"));
        mvc.perform(post("/admin/whitelist").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone_id\":1,\"account\":\"robot_0001\",\"note\":\"内测\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zone_id").value(1))
                .andExpect(jsonPath("$.account").value("robot_0001"));
        when(store.whitelist(1)).thenReturn(List.of(new WhitelistRow(1, "robot_0001", "内测")));
        mvc.perform(get("/admin/whitelist/1")).andExpect(status().isOk()).andExpect(jsonPath("$[0].note").value("内测"));
        mvc.perform(delete("/admin/whitelist/1/robot_0001")).andExpect(status().isNoContent());
        verify(store).removeWhitelist(1, "robot_0001");
    }
}
