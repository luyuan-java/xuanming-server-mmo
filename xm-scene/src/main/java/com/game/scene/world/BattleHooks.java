package com.game.scene.world;

/**
 * {@link SceneWorld} 调回合制战斗的钩子（scene-battle-spec §7.8、§7.12、§10.5；实现是 {@code com.game.scene.battle.PlayerBattleService}）。
 * 只在场景逻辑线程上调用；实现必须不阻塞（Redis 异步，结果投递回逻辑线程）、不抛异常。
 */
public interface BattleHooks {

    /**
     * 进场之后（登录 / 重连 / 顶号 / 交出进场，组队跟随钩子之后）：进场恢复（§7.8）。
     *
     * @param carried 同一 epoch 被接替的旧实例（{@code previous.ownerEpoch() == epoch}，沿用它的内存状态）；否则 null
     */
    void onEntered(SceneWorld world, ScenePlayer player, ScenePlayer carried);

    /** 一次在线存盘确认落库之后（{@code markPersisted} 之后，只在 SAVED 时调）：快路径销账（§7.12，D19）。 */
    void onPersisted(SceneWorld world, ScenePlayer player);

    /**
     * 交出没提交、原地解冻之后（实例仍在、会话仍在）：<b>重跑一次完整的进场恢复</b>（§7.8 第 1 步起）。规格 §10.5 的原文只要求补「锁步骤」
     * （冻结期间到达的确认只续了锁、没挂冻结）；实现有意做成它的超集：锁步骤本身就要一次恢复读，读失败时落到 RETRY 才有人重试，
     * 还能把冻结期间被延后的结算当场补上、把冻结期间没 forget 的账本条目销掉。代价是一次 Redis 往返内 {@code recovery = PENDING}
     * （备战 1006、结算 DEFERRED），读失败转 RETRY、由 reaper 重读。与还在途的更早一次恢复读靠代际号区分，只认最新一代。
     */
    void onUnfrozenInPlace(SceneWorld world, ScenePlayer player);

    /** 不接战斗（测试与不接 Redis 的装配）。 */
    BattleHooks NONE = new BattleHooks() {
        @Override
        public void onEntered(SceneWorld world, ScenePlayer player, ScenePlayer carried) {
        }

        @Override
        public void onPersisted(SceneWorld world, ScenePlayer player) {
        }

        @Override
        public void onUnfrozenInPlace(SceneWorld world, ScenePlayer player) {
        }
    };
}
