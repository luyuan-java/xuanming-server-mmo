package com.game.data.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.GateNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.discovery.drain.GateDrainMarks;
import com.game.discovery.drain.GateDrainMarks.Mark;
import com.game.discovery.drain.GateDrainMarks.MarkResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * gate 排空运维接口：列表叠加标记（旧实例的标记标成 stale）、打给目录里的当前实例、最后一台不带 force 409（检查交给存储原子地做）、
 * 带 force 不检查、不认识的 gate 404、TTL 须在 [min-ttl, 86400]、撤销恒 204。存储是替身。
 */
class GateDrainAdminControllerTest {

    @SuppressWarnings("unchecked")
    private final NodeDirectory<GateNodeInfo> directory = mock(NodeDirectory.class);
    private final GateDrainMarks marks = mock(GateDrainMarks.class);
    private final GateDrainAdminController controller =
            new GateDrainAdminController(() -> directory, () -> marks, Duration.ofMinutes(30));
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private static GateNodeInfo gate(int node, int players) {
        return GateNodeInfo.newBuilder().setZoneId(1).setNodeId(node).setInstanceId("i" + node).setClientHost("h")
                .setClientPort(11000).setPlayerCount(players).build();
    }

    @Test
    void 列表叠加排空起点与drained_旧实例的标记标成stale() {
        when(directory.list(1)).thenReturn(List.of(gate(2, 5), gate(1, 0), gate(3, 0)));
        when(marks.draining(eq(1), any())).thenReturn(Map.of(1, new Mark(1_800_000_000L, "i1", "1800000000:i1"),
                3, new Mark(5L, "dead", "5:dead")));
        when(marks.drained(eq(1), any())).thenReturn(Map.of(1, "below_threshold"));
        List<GateDrainAdminController.GateView> views = controller.list(1);
        assertThat(views).extracting(GateDrainAdminController.GateView::nodeId).containsExactly(1, 2, 3);
        assertThat(views.get(0).drainingSince()).isEqualTo(1_800_000_000L);
        assertThat(views.get(0).drained()).isEqualTo("below_threshold");
        assertThat(views.get(1).drainingSince()).isNull();
        assertThat(views.get(1).playerCount()).isEqualTo(5);
        assertThat(views.get(2).staleMark()).isTrue();
        assertThat(views.get(2).drainingSince()).isNull();
    }

    @Test
    void 打给当前实例_带上其余gate做最后一台检查_缺省TTL一小时() {
        when(directory.list(1)).thenReturn(List.of(gate(1, 0), gate(2, 5)));
        when(marks.mark(1, 1, "i1", Duration.ofHours(1), Map.of(2, "i2"))).thenReturn(MarkResult.MARKED);
        when(marks.draining(1, List.of(1))).thenReturn(Map.of(1, new Mark(123L, "i1", "123:i1")));
        GateDrainAdminController.DrainResult result =
                controller.drain(new GateDrainAdminController.DrainBody(1L, 1L, null, null), request);
        assertThat(result.marked()).isTrue();
        assertThat(result.instanceId()).isEqualTo("i1");
        assertThat(result.drainingSince()).isEqualTo(123L);
    }

    @Test
    void 最后一台不带force409_带force不做检查_不认识的gate404_TTL越界400() {
        when(directory.list(1)).thenReturn(List.of(gate(1, 0), gate(2, 5)));
        when(marks.mark(eq(1), eq(1), eq("i1"), any(), eq(Map.of(2, "i2")))).thenReturn(MarkResult.LAST_GATE);
        assertThatThrownBy(() -> controller.drain(new GateDrainAdminController.DrainBody(1L, 1L, 1800L, null), request))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409").hasMessageContaining("last_gate");
        when(marks.mark(eq(1), eq(1), eq("i1"), any(), isNull())).thenReturn(MarkResult.MARKED);
        assertThat(controller.drain(new GateDrainAdminController.DrainBody(1L, 1L, 1800L, true), request).marked()).isTrue();

        assertThatThrownBy(() -> controller.drain(new GateDrainAdminController.DrainBody(1L, 7L, 1800L, true), request))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> controller.drain(new GateDrainAdminController.DrainBody(1L, 1L, 600L, true), request))
                .as("短于 min-ttl（须长于 gateway 的 deadline）").isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        assertThatThrownBy(() -> controller.drain(new GateDrainAdminController.DrainBody(1L, 1L, 86_401L, true), request))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        assertThatThrownBy(() -> controller.drain(new GateDrainAdminController.DrainBody(null, 1L, 1800L, true), request))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        verify(marks, never()).mark(anyInt(), eq(7), any(), any(), any());
    }

    @Test
    void 撤销恒204() {
        assertThat(controller.undrain(1, 3, request).getStatusCode().value()).isEqualTo(204);
        verify(marks).clear(1, 3);
    }

    @Test
    void 排空运维正十进制高位区的列表打标撤销与响应() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        for (long value : new long[] {2147483647L, 2147483648L, 4294967295L}) {
            int bits = (int) value;
            when(directory.list(bits)).thenReturn(List.of(gate(1, 0).toBuilder().setZoneId(bits).build()));
            when(marks.draining(eq(bits), any())).thenReturn(Map.of());
            when(marks.drained(eq(bits), any())).thenReturn(Map.of());
            when(marks.mark(eq(bits), eq(1), eq("i1"), any(), isNull())).thenReturn(MarkResult.MARKED);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/gates/" + value))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/gates/drain")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"zone_id\":" + value + ",\"node_id\":1,\"force\":true}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.zone_id").value(value));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/admin/gates/drain/" + value + "/1"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
            verify(marks).clear(bits, 1);
        }
    }

    @Test
    void 排空zone浮点与溢出在访问Redis前400() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        for (String invalid : new String[] {"-1", "0", "-0.5", "4294967295.5", "4294967296"}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/gates/drain")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"zone_id\":" + invalid + ",\"node_id\":1}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
        org.mockito.Mockito.verifyNoInteractions(directory, marks);
    }
}
