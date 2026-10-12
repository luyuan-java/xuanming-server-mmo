package com.game.scenemanager.world;

import com.game.api.proto.ChannelKind;
import java.util.HashSet;
import java.util.Set;

/**
 * 从一拍的节点目录推导「谁是镜像源」（批次 5.3，dungeon-mirror-spec §6.6）：任一节点上报了 {@code kind == MIRROR && source_scene_id == X}
 * 的条目（<b>不论是否在排空</b>，回收宽限 / 级联中的镜像还没销毁），X 就是镜像源。语义同基线 {@code channelHasMirrors} 的
 * {@code SCARD scene:{X}:mirrors > 0}（world_autoscale.go:285-293），但 Java 没有那组 Redis 键：实例由承载节点自有，目录是唯一登记（D1）。
 *
 * <p>纯函数、不抛异常（目录已在内存里）；结果在构造时算好、不可变，可在规划器里反复查。目录最多滞后一次发布（新镜像建好后节点立即补发），
 * 窗口里源被选为缩容牺牲者时由节点本地级联兜底（同基线「排空窗口里新建的镜像」，world_autoscale.go:437-445）。
 */
public final class DirectoryMirrorSources implements MirrorSources {

    private final Set<Long> sources;

    private DirectoryMirrorSources(Set<Long> sources) {
        this.sources = sources;
    }

    /** 按目录推导；没有任何镜像时返回 {@link MirrorSources#NONE}。 */
    public static MirrorSources of(DirectoryView directory) {
        Set<Long> sources = new HashSet<>();
        for (DirectoryView.Node node : directory.nodes().values()) {
            for (DirectoryView.Scene scene : node.scenes().values()) {
                if (scene.kind() == ChannelKind.CHANNEL_KIND_MIRROR && scene.sourceSceneId() != 0) {
                    sources.add(scene.sourceSceneId());
                }
            }
        }
        return sources.isEmpty() ? MirrorSources.NONE : new DirectoryMirrorSources(Set.copyOf(sources));
    }

    @Override
    public boolean isSource(long sceneId) {
        return sources.contains(sceneId);
    }

    @Override
    public String toString() {
        return "DirectoryMirrorSources" + sources;
    }
}
