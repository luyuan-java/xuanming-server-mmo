package com.game.scenemanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class SceneDirectoryProviderTest {

    private static final WorldSceneConfigs WORLD = new WorldSceneConfigs(1, Set.of(1));
    private static final AssignSceneRequest REQUEST =
            AssignSceneRequest.newBuilder().setZoneId(1).setPlayerId(42).build();

    @Test
    void 分配结果以已完成的future返回() throws Exception {
        SceneNodeInfo node = SceneNodeInfo.newBuilder()
                .setZoneId(1).setNodeId(3).setLinkHost("127.0.0.1").setLinkPort(21000)
                .addScenes(SceneEntry.newBuilder().setSceneId(77).setSceneConfigId(1))
                .build();
        SceneDirectoryProvider provider = new SceneDirectoryProvider(new SceneAssigner(zone -> List.of(node), WORLD));

        CompletableFuture<AssignSceneResponse> future = provider.assign(REQUEST);

        assertThat(future).isCompleted();
        assertThat(future.get().getSceneNodeId()).isEqualTo(3);
        assertThat(future.get().getSceneId()).isEqualTo(77);
    }

    @Test
    void 无场景是正常应答而不是异常() throws Exception {
        SceneDirectoryProvider provider = new SceneDirectoryProvider(new SceneAssigner(zone -> List.of(), WORLD));

        assertThat(provider.assign(REQUEST).get().getTipId()).isEqualTo(SceneAssigner.TIP_NO_SCENE);
    }

    @Test
    void 目录不可读时future以异常完成且不外泄内部细节() {
        SceneDirectoryProvider provider = new SceneDirectoryProvider(new SceneAssigner(zone -> {
            throw new IllegalStateException("redis://10.0.0.1:6379 unreachable");
        }, WORLD));

        CompletableFuture<AssignSceneResponse> future = provider.assign(REQUEST);

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("场景目录暂不可用")
                .hasMessageNotContaining("10.0.0.1")
                .hasNoCause();
    }
}
