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

    /** 交出没提交、原地解冻之后（实例仍在、会话仍在）：补跑进场恢复的锁步骤（§10.5）。 */
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
