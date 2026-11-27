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

        when(store.upsertZone(new ZoneRow(5, "五区", 0, ZoneRow.DEFAULT_CAPACITY, "", null, false, 0, 0, 0))).thenReturn(ZONE);
        mvc.perform(post("/admin/zones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone_id\":5,\"name\":\"五区\"}"))
                .andExpect(status().isOk());

        when(store.upsertZone(new ZoneRow(6, "六区", 9, ZoneRow.DEFAULT_CAPACITY, "", null, false, 0, 0, 0)))
                .thenThrow(new IllegalArgumentException("manual_status 只能是 0–3"));
        mvc.perform(post("/admin/zones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone_id\":6,\"name\":\"六区\",\"manual_status\":9}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 区服_暂停恢复创角与角色上限接口() throws Exception {
        ZoneRow closed = new ZoneRow(1, "一区", 0, 45_000, "", null, true, 1, true, 30_000, 10, 20);
        when(store.setCreateRoleClosed(1, true)).thenReturn(Optional.of(closed));
        when(store.setCreateRoleClosed(1, false)).thenReturn(Optional.of(ZONE));
        when(store.setCreateRoleClosed(9, true)).thenReturn(Optional.empty());
        when(store.setRoleCapacity(1, 30_000)).thenReturn(Optional.of(closed));
        when(store.setRoleCapacity(1, -1)).thenThrow(new IllegalArgumentException("role_capacity 不能为负"));

        mvc.perform(post("/admin/zones/1/create-role/close")).andExpect(status().isOk())
                .andExpect(jsonPath("$.create_role_closed").value(true))
                .andExpect(jsonPath("$.role_capacity").value(30_000));
        mvc.perform(post("/admin/zones/1/create-role/open")).andExpect(status().isOk())
                .andExpect(jsonPath("$.create_role_closed").value(false));
        mvc.perform(post("/admin/zones/9/create-role/close")).andExpect(status().isNotFound());
        mvc.perform(put("/admin/zones/1/role-capacity").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role_capacity\":30000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role_capacity").value(30_000));
        mvc.perform(put("/admin/zones/1/role-capacity").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/admin/zones/1/role-capacity").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role_capacity\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 区服_创建只插不改_撞已存在不同409带原行_相同200() throws Exception {
        when(store.zone(1)).thenReturn(Optional.of(ZONE));
        mvc.perform(post("/admin/zones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone_id\":1,\"name\":\"一区改名\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason_code").value("zone_already_exists"))
                .andExpect(jsonPath("$.existing.name").value("一区"));
        verify(store, never()).upsertZone(any());

        mvc.perform(post("/admin/zones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone_id\":1,\"name\":\"一区\",\"manual_status\":1,\"capacity\":5000,\"maintenance_msg\":\"停服\",\"open_time\":1900000000,\"recommended\":true,\"sort_order\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("一区"));
        verify(store, never()).upsertZone(any());
    }

    @Test
    void 区服_改不存在404_缺字段400_缺IfMatch428_不符412_匹配200_删204或404_维护与开放() throws Exception {
        // 缺必填字段 / zone_id 不符 -> 400 且完全不碰 store
        mvc.perform(put("/admin/zones/7").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason_code").value("missing_required_field"));
        mvc.perform(put("/admin/zones/7").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"七区\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("manual_status"));
        mvc.perform(put("/admin/zones/7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone_id\":8,\"name\":\"七区\",\"manual_status\":0,\"capacity\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason_code").value("zone_id_mismatch"));
        // 缺 If-Match -> 428
        mvc.perform(put("/admin/zones/7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"七区\",\"manual_status\":0,\"capacity\":1000}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.reason_code").value("missing_if_match"));
        verify(store, never()).updateZone(anyInt(), any());

        // 不存在 -> 404
        when(store.zone(7)).thenReturn(Optional.empty());
        mvc.perform(put("/admin/zones/7").header("If-Match", "\"v20\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"七区\",\"manual_status\":0,\"capacity\":1000}"))
                .andExpect(status().isNotFound());

        // If-Match 不符 -> 412 且不更新
        when(store.zone(1)).thenReturn(Optional.of(ZONE));
        mvc.perform(put("/admin/zones/1").header("If-Match", "\"v999\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"一区新\",\"manual_status\":0,\"capacity\":6000}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.reason_code").value("etag_mismatch"));
        verify(store, never()).updateZone(anyInt(), any());

        // If-Match 匹配 -> 200 并更新
        when(store.updateZone(eq(1), any())).thenReturn(Optional.of(ZONE));
        mvc.perform(put("/admin/zones/1").header("If-Match", "\"v20\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"一区新\",\"manual_status\":0,\"capacity\":6000}"))
                .andExpect(status().isOk());

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

    @Test
    void 目录白名单三边界HTTP全操作_响应不输出负区号() throws Exception {
        for (long value : new long[] {2147483647L, 2147483648L, 4294967295L}) {
            int bits = (int) value;
            ZoneRow row = new ZoneRow(bits, "高位", 0, 5000, "", null, false, -7, 10, 20);
            when(store.zone(bits)).thenReturn(Optional.of(row));
            when(store.zones()).thenReturn(List.of(row));
            when(store.upsertZone(any())).thenReturn(row);
            when(store.updateZone(eq(bits), any())).thenReturn(Optional.of(row));
            when(store.setZoneStatus(eq(bits), any(), any())).thenReturn(Optional.of(row));
            when(store.deleteZone(bits)).thenReturn(true);
            when(store.addWhitelist(bits, "a", "n")).thenReturn(new WhitelistRow(bits, "a", "n"));
            when(store.whitelist(bits)).thenReturn(List.of(new WhitelistRow(bits, "a", "n")));
            mvc.perform(get("/admin/zones/" + value)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.zone_id").value(value));
            mvc.perform(get("/admin/zones")).andExpect(jsonPath("$[0].zone_id").value(value));
            mvc.perform(post("/admin/zones").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"zone_id\":" + value + ",\"name\":\"高位\",\"manual_status\":0,\"capacity\":5000,\"sort_order\":-7}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.zone_id").value(value));
            mvc.perform(put("/admin/zones/" + value).header("If-Match", "\"v20\"").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"zone_id\":" + value + ",\"name\":\"路径\",\"manual_status\":0,\"capacity\":5000}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.zone_id").value(value));
            mvc.perform(post("/admin/zones/" + value + "/maintenance")).andExpect(status().isOk());
            mvc.perform(post("/admin/zones/" + value + "/open")).andExpect(status().isOk());
            mvc.perform(delete("/admin/zones/" + value)).andExpect(status().isNoContent());
            mvc.perform(post("/admin/whitelist").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"zone_id\":" + value + ",\"account\":\"a\",\"note\":\"n\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.zone_id").value(value));
            mvc.perform(get("/admin/whitelist/" + value)).andExpect(jsonPath("$[0].zone_id").value(value));
            mvc.perform(delete("/admin/whitelist/" + value + "/a")).andExpect(status().isNoContent());
            verify(store, org.mockito.Mockito.atLeastOnce()).zone(bits);
            verify(store).removeWhitelist(bits, "a");
        }
    }

    @Test
    void 运维zone的浮点负值及溢出请求在任何store调用前400() throws Exception {
        for (String invalid : new String[] {"-1", "0", "-0.5", "4294967295.5", "4294967296"}) {
            mvc.perform(post("/admin/zones").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"zone_id\":" + invalid + ",\"name\":\"拒绝\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post("/admin/whitelist").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"zone_id\":" + invalid + ",\"account\":\"a\"}"))
                    .andExpect(status().isBadRequest());
        }
        for (String invalid : new String[] {"-1", "0", "4294967296", "4294967295.5"}) {
            mvc.perform(get("/admin/zones/" + invalid)).andExpect(status().isBadRequest());
            mvc.perform(delete("/admin/whitelist/" + invalid + "/a")).andExpect(status().isBadRequest());
        }
        org.mockito.Mockito.verifyNoInteractions(store);
    }
}
