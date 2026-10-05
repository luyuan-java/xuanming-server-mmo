package com.game.scene.world;

/**
 * 客户端换场景（63）解析出的去向（{@link SceneWorld#resolveSwitchTarget}，scene-channels-spec §4.12、§0.4）。
 * 5.1 只有本节点内的去向；跨节点（5.2）届时再加一种「远端」去向，由 {@link ClientRequestHandler} 接上 scene-manager 往返。
 */
sealed interface SwitchTarget {

    /** 换到本节点上的这个场景（可能就是当前场景：同图重选挑回原频道，应答成功、不发 79）。 */
    record Local(Scene scene) implements SwitchTarget {
    }

    /** 拒绝，应答带这个 tip（目标不在本节点、在排空中、本节点该图没有承载中的频道 → 3023）。 */
    record Reject(int tip) implements SwitchTarget {
    }
}
