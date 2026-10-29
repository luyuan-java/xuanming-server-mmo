package com.game.data.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.discovery.drain.SceneEvacuationMarks;
import com.game.discovery.drain.SceneEvacuationMarks.Mark;
import com.game.discovery.drain.SceneEvacuationMarks.MarkResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.client.RedisException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(OutputCaptureExtension.class)
class SceneEvacuationAdminControllerTest {
    @SuppressWarnings("unchecked")
    private final NodeDirectory<SceneNodeInfo> directory = mock(NodeDirectory.class);
    private final SceneEvacuationMarks marks = mock(SceneEvacuationMarks.class);
    private final SceneEvacuationAdminController controller =
            new SceneEvacuationAdminController(() -> directory, () -> marks, Duration.ofMinutes(30));
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private static SceneNodeInfo node(int id, boolean evacuating, int players) {
        return SceneNodeInfo.newBuilder().setZoneId(1).setNodeId(id).setInstanceId("i" + id).setLeaseEpoch(-1L)
                .setLinkHost("127.0.0.1").setLinkPort(12000).setEvacuating(evacuating)
                .addScenes(SceneEntry.newBuilder().setSceneId(-1L).setSceneConfigId(101).setPlayerCount(players)
                        .setKind(ChannelKind.CHANNEL_KIND_WORLD).setDraining(evacuating)).build();
    }

    private SceneEvacuationAdminController.EvacuateResult call(Long zone, Long node, Long ttl, Boolean force) {
        return controller.evacuate(new SceneEvacuationAdminController.EvacuateBody(zone, node, ttl, force), request);
    }

    @Test
    void 目录叠加区分旧实例_只有标记适用且自报疏散且空节点可下线() {
        when(directory.list(1)).thenReturn(List.of(node(4, false, 0), node(3, true, 0), node(2, true, -1), node(1, true, 0)));
        when(marks.evacuating(eq(1), any())).thenReturn(Map.of(1, new Mark(123, "i1", "123:i1"),
                2, new Mark(124, "i2", "124:i2"), 3, new Mark(1, "old", "1:old"), 4, new Mark(125, "i4", "125:i4")));
        var views = controller.list(1);
        assertThat(views).extracting(SceneEvacuationAdminController.SceneView::nodeId).containsExactly(1, 2, 3, 4);
        assertThat(views).extracting(SceneEvacuationAdminController.SceneView::evacuated).containsExactly(true, false, false, false);
        assertThat(views.getFirst().leaseEpoch()).isEqualTo("18446744073709551615");
        assertThat(views.getFirst().channels().getFirst().sceneId()).isEqualTo("18446744073709551615");
        assertThat(views.getFirst().channels().getFirst().state()).isEqualTo("DRAINING");
        assertThat(views.get(3).channels().getFirst().state()).isEqualTo("ACTIVE");
        assertThat(views.get(1).playerCount()).isEqualTo(4_294_967_295L);
        assertThat(views.get(2).staleMark()).isTrue();
        assertThat(views.get(2).evacuatingSince()).isNull();
        assertThat(views.getFirst().evacuatingSince()).isEqualTo(123);
    }

    @Test
    void 默认TTL与当前实例交给原子存储_后备剔除自报疏散和坏目录_成功记审计(CapturedOutput output) {
        request.addHeader(AdminAuthFilter.OPERATOR_HEADER, "operator-a");
        when(directory.list(1)).thenReturn(List.of(node(1, false, 2), node(2, false, 3), node(3, true, 0),
                node(4, false, 0).toBuilder().setLinkPort(0).build(), node(5, false, 0).toBuilder().setZoneId(2).build()));
        when(marks.mark(1, 1, "i1", Duration.ofHours(1), Map.of(2, "i2"))).thenReturn(MarkResult.MARKED);
        when(marks.read(1, 1)).thenReturn(new Mark(123, "i1", "123:i1"));
        var result = call(1L, 1L, null, null);
        assertThat(result.marked()).isTrue();
        assertThat(result.instanceId()).isEqualTo("i1");
        assertThat(result.evacuatingSince()).isEqualTo(123);
        verify(marks).mark(1, 1, "i1", Duration.ofHours(1), Map.of(2, "i2"));
        assertThat(output.getAll()).contains("scene evacuate", "result=MARKED", "operator=operator-a");
    }

    @Test
    void 最后一台409_force显式跳过_重复false_无TTL409且不读取成成功() {
        when(directory.list(1)).thenReturn(List.of(node(1, false, 0)));
        when(marks.mark(eq(1), eq(1), eq("i1"), any(), eq(Map.of()))).thenReturn(MarkResult.LAST_NODE);
        assertThatThrownBy(() -> call(1L, 1L, null, null)).hasMessageContaining("409").hasMessageContaining("last_node");
        when(marks.mark(eq(1), eq(1), eq("i1"), any(), isNull())).thenReturn(MarkResult.MARKED);
        assertThat(call(1L, 1L, null, true).marked()).isTrue();
        when(marks.mark(eq(1), eq(1), eq("i1"), any(), isNull())).thenReturn(MarkResult.ALREADY_EVACUATING);
        assertThat(call(1L, 1L, null, true).marked()).isFalse();
        clearInvocations(marks);
        when(marks.mark(eq(1), eq(1), eq("i1"), any(), isNull())).thenReturn(MarkResult.EXISTING_INVALID);
        assertThatThrownBy(() -> call(1L, 1L, null, true)).hasMessageContaining("409").hasMessageContaining("existing_mark_invalid");
        verify(marks, never()).read(anyInt(), anyInt());
    }

    @Test
    void 未知节点404_非法请求400且零写_重复身份503() {
        when(directory.list(1)).thenReturn(List.of(node(1, false, 0)));
        assertThatThrownBy(() -> call(1L, 2L, null, true)).hasMessageContaining("404");
        for (Long bad : new Long[] {null, 0L, -1L, 2_147_483_648L}) {
            assertThatThrownBy(() -> call(bad, 1L, null, true)).hasMessageContaining("400");
            assertThatThrownBy(() -> call(1L, bad, null, true)).hasMessageContaining("400");
        }
        for (long bad : new long[] {-1, 0, 1799, 86401}) {
            assertThatThrownBy(() -> call(1L, 1L, bad, true)).hasMessageContaining("400");
        }
        when(directory.list(1)).thenReturn(List.of(node(1, false, 0), node(1, false, 0)));
        assertThatThrownBy(() -> call(1L, 1L, null, true)).hasMessageContaining("503");
        verifyNoInteractions(marks);
    }

    @Test
    void 租约换代与永久退役都回409_force不能覆盖() {
        when(directory.list(1)).thenReturn(List.of(node(1, false, 0)));
        for (var result : List.of(MarkResult.TARGET_CHANGED, MarkResult.ZONE_RETIRED)) {
            when(marks.mark(eq(1), eq(1), eq("i1"), any(), isNull())).thenReturn(result);
            assertThatThrownBy(() -> call(1L, 1L, null, true)).hasMessageContaining("409")
                    .hasMessageContaining(result.name().toLowerCase(java.util.Locale.ROOT));
        }
        verify(marks, never()).read(anyInt(), anyInt());
    }

    @Test
    void Redis读写故障503_懒加载不挡构造_撤销恒204并审计(CapturedOutput output) {
        when(directory.list(1)).thenThrow(new RedisException("offline"));
        assertThatThrownBy(() -> controller.list(1)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
        assertThatThrownBy(() -> call(1L, 1L, null, true)).hasMessageContaining("503");
        var lazy = new SceneEvacuationAdminController(() -> { throw new NoSuchBeanDefinitionException("redis"); },
                () -> marks, Duration.ofMinutes(30));
        assertThatThrownBy(() -> lazy.list(1)).hasMessageContaining("503");
        doThrow(new RedisException("offline")).when(marks).clear(1, 1);
        assertThatThrownBy(() -> controller.cancel(1, 1, request)).hasMessageContaining("503");
        assertThat(controller.cancel(1, 2, request).getStatusCode().value()).isEqualTo(204);
        verify(marks).clear(1, 2);
        assertThat(output.getAll()).contains("scene evacuation cancel zone_id=1 node_id=2");
    }
}
