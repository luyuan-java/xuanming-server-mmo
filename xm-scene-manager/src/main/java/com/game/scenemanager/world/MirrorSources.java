package com.game.scenemanager.world;

/**
 * 「这个频道是不是镜像源」（scene-channels-spec §0.4、§4.7 条件 2；5.3 钩子）。镜像与源共置，源被缩容 / 迁走会让镜像变成孤儿，
 * 所以缩容与择机迁移跳过镜像源（基线 channelHasMirrors，world_autoscale.go:281-293）。
 *
 * <p>查询出错（抛异常）按「是」处理（fail-closed，同基线：缩容是可选的省钱动作，查不清就别动）。5.1 没有镜像，实现为 {@link #NONE}。
 * 调用发生在控制面 tick 线程上，实现可以阻塞。
 */
@FunctionalInterface
public interface MirrorSources {

    /** 5.1：没有镜像。 */
    MirrorSources NONE = sceneId -> false;

    boolean isSource(long sceneId);
}
