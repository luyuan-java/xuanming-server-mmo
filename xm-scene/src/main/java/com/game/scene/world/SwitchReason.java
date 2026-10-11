package com.game.scene.world;

/**
 * 一次在途换图（{@link PlayerSwitch}）是为了什么（zone-travel-spec §0.6 裁决 16；指标 {@code xm.scene.transfers{reason}}）。
 * 同一个 {@code PlayerSwitch} 上只有这一个判别字段：批次 5.4 引入 {@link #PLAYER} / {@link #TRAVEL}，5.5 追加 {@code EVACUATE}
 * （scene-drain-spec §5.2）。{@link PlayerSwitch.Purpose} 说的是「怎么做」（选目标后冻结交出，还是在本节点建镜像），与它正交。
 */
enum SwitchReason {
    /** 玩家自己发的 63：远端去向的跨节点换图（交出并保持，批次 5.2），或镜像分支的取号（批次 5.3，永不冻结）。 */
    PLAYER,
    /**
     * 玩家发的 226 跨 zone 传送（批次 5.4）：PRECHECK（在队检查，延迟应答）→ RESOLVING（选 gate、签票据）→ FREEZING（交出并释放）。
     * 结局与 PLAYER 的差别：新 epoch 在交出事务里已释放，任何路径都不再释放它；失败推的 tip 是 3027（不是 3023）；交出提交后发重定向帧
     * （{@link ClientSink#playerRedirect}），不发改绑指令。
     */
    TRAVEL
}
