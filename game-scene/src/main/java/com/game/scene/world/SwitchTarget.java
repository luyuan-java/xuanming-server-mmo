package com.game.scene.world;

/**
 * 客户端换场景（63）解析出的去向（{@link SceneWorld#resolveSwitchTarget}，scene-channels-spec §4.12、scene-handoff-spec §5.5）。
 * 本节点内的去向同步完成；远端去向由 {@link ClientRequestHandler} 先回应答、再经 scene-manager 选目标（批次 5.2）。
 */
sealed interface SwitchTarget {

    /** 换到本节点上的这个场景（可能就是当前场景：同图重选挑回原频道，应答成功、不发 79）。 */
    record Local(Scene scene) implements SwitchTarget {
    }

    /**
     * 本节点解析不了，交给 scene-manager 选（批次 5.2）：显式 scene_id 不在本节点；或只带主世界地图而本节点没有该图的承载中频道
     * （5.1 的 per-node 覆盖下只在该图频道全在排空时出现，切 hash 覆盖后常见）。不带节点号：节点由 scene-manager 定（Q9）。
     */
    record Remote() implements SwitchTarget {
    }

    /** 拒绝，应答带这个 tip（指定的场景在排空中、地图不是主世界、跨节点换图没装配 → 3023）。 */
    record Reject(int tip) implements SwitchTarget {
    }
}
