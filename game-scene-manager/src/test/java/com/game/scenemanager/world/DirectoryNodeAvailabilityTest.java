package com.game.scenemanager.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.game.api.proto.*;
import com.game.scenemanager.InstanceIdIssuer;
import com.game.scenemanager.SceneNodeSource;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DirectoryNodeAvailabilityTest {
    private SceneNodeInfo node(boolean evacuating) {
        return SceneNodeInfo.newBuilder().setZoneId(1).setNodeId(10).setInstanceId("original").setLeaseEpoch(7)
                .setLinkHost("127.0.0.1").setLinkPort(22000).setEvacuating(evacuating).build();
    }
    @Test void 实例只读单条原件_疏散或不存在不发号_恢复后可以发号() {
        var reads = new AtomicInteger();
        var ids = new AtomicInteger();
        for (SceneNodeInfo record : new SceneNodeInfo[]{node(true), null, node(false)}) {
            SceneNodeSource source = new SceneNodeSource() {
                public List<SceneNodeInfo> list(int zone) { throw new AssertionError("不得全量读目录"); }
                public Optional<SceneNodeInfo> find(int zone, int id) { reads.incrementAndGet(); return Optional.ofNullable(record); }
            };
            var issuer = new InstanceIdIssuer(() -> OptionalLong.of(ids.incrementAndGet()), new DirectoryNodeAvailability(source));
            var request = CreateInstanceRequest.newBuilder().setZoneId(1).setRequesterSceneNodeId(10)
                    .setRequesterInstanceId("original").setRequesterLeaseEpoch(7)
                    .setSceneConfigId(1).setKind(ChannelKind.CHANNEL_KIND_DUNGEON).setDungeonConfigId(1).build();
            assertThat(issuer.issue(request).result()).isEqualTo(record != null && !record.getEvacuating()
                    ? InstanceIdIssuer.Result.OK : InstanceIdIssuer.Result.NODE_UNAVAILABLE);
        }
        assertThat(reads).hasValue(3);
        assertThat(ids).hasValue(1);
    }
    @Test void 目录原件保留疏散身份_读取故障不能当不存在() {
        var view = DirectoryView.of(1, List.of(node(true)));
        assertThat(view.node(10).evacuating()).isTrue();
        assertThat(view.node(10).leaseEpoch()).isEqualTo(7);
        assertThat(view.node(10).instanceId()).isEqualTo("original");
        var availability = new DirectoryNodeAvailability(zone -> { throw new IllegalStateException("目录故障"); });
        assertThatThrownBy(() -> availability.acceptsNewChannels(1, 10)).hasMessage("目录故障");
        assertThat(new DirectoryNodeAvailability(zone -> List.of(node(false).toBuilder().setZoneId(2).build()))
                .acceptsNewChannels(1, 10)).isFalse();
    }

    @Test void 生产工厂拒绝延迟旧实例旧代次及缺失原件_仅当前身份发新号() {
        var ids = org.mockito.Mockito.mock(com.game.scenemanager.SceneIdAllocator.class);
        org.mockito.Mockito.when(ids.tryNext()).thenReturn(OptionalLong.of(1001));
        var source = (SceneNodeSource) zone -> List.of(node(false));
        var issuer = new com.game.scenemanager.SceneManagerConfiguration().instanceIdIssuer(ids, source);
        var request = CreateInstanceRequest.newBuilder().setZoneId(1).setRequesterSceneNodeId(10)
                .setSceneConfigId(1).setKind(ChannelKind.CHANNEL_KIND_DUNGEON).setDungeonConfigId(1)
                .setRequesterInstanceId("original").setRequesterLeaseEpoch(7).build();
        for (var invalid : List.of(request.toBuilder().clearRequesterInstanceId().build(),
                request.toBuilder().clearRequesterLeaseEpoch().build(),
                request.toBuilder().setRequesterInstanceId("old-instance").build(),
                request.toBuilder().setRequesterLeaseEpoch(6).build(),
                request.toBuilder().setRequesterLeaseEpoch(8).build())) {
            assertThat(issuer.issue(invalid).result()).isEqualTo(InstanceIdIssuer.Result.NODE_UNAVAILABLE);
        }
        org.mockito.Mockito.verifyNoInteractions(ids);
        assertThat(issuer.issue(request).result()).isEqualTo(InstanceIdIssuer.Result.OK);
        org.mockito.Mockito.verify(ids).tryNext();
    }
}
