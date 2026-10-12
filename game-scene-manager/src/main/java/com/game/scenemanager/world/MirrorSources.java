package com.game.scenemanager.world;

/**
 * 「这个频道是不是镜像源」（scene-channels-spec §0.4、§4.7 条件 2；批次 5.3 接上，dungeon-mirror-spec §6.6）。镜像与源共置，源被缩容 / 迁走会让镜像
 * 变成孤儿，所以缩容与择机迁移跳过镜像源（基线 channelHasMirrors，world_autoscale.go:281-293；再平衡跳过是 Java 的 D12）。
 *
 * <p>生产实现是每拍从节点目录推导的 {@link DirectoryMirrorSources}（{@link WorldChannelPlanner} 每拍调一次 {@code DirectoryMirrorSources::of}）。
 * 查询出错（抛异常）按「是」处理（fail-closed，同基线：缩容是可选的省钱动作，查不清就别动）。调用发生在控制面 tick 线程上。
 * 孤儿图（P1）与死节点（P2）不受它约束：镜像由节点本地级联处理。
 */
@FunctionalInterface
public interface MirrorSources {

    /** 没有镜像（目录里没有任何 MIRROR 条目；单测缺省）。 */
    MirrorSources NONE = sceneId -> false;

    boolean isSource(long sceneId);
}
