package com.game.scene.world;

/**
 * {@link SceneWorld} 调玩家持久收件箱消费端的钩子（mmorpg {@code docs/design/player-inbox-service.md} §7、§12；实现是
 * {@code com.game.scene.inbox.InboxService}）。与 {@link BattleHooks} 并列。只在场景逻辑线程上调用；实现必须不阻塞（拉取 / 确认是异步 Dubbo，
 * 结果投递回逻辑线程）、不抛异常。
 */
public interface InboxHooks {

    /**
     * 进场之后（登录 / 重连 / 顶号 / 交出进场；组队跟随与战斗进场恢复之后）：落地拉取（设计 §7.3 第 1 条）。
     *
     * @param carried 同一 epoch 被接替的旧实例（沿用它的运行时状态：等同 mmorpg「同节点重复路由不算新落地」）；否则 null（新落地：无条件拉一次）
     */
    void onEntered(SceneWorld world, ScenePlayer player, ScenePlayer carried);

    /** 一次在线存盘确认落库之后（只在 SAVED 时调）：落盘之后才确认的快路径（设计 §7.7）。 */
    void onPersisted(SceneWorld world, ScenePlayer player);

    /**
     * 实例带最终写回离场之后（主动离开 / 断线 / 被接管 / 停服；交出、失去归属、写回被围栏拒这几种不带写回的收尾不调）：对已落盘、待确认的结局
     * 尽力补发一次确认，不等回包（设计 §7.7「离开时尽力补发一次 Ack」，评审 M4）。
     */
    void onLeaving(SceneWorld world, ScenePlayer player);

    /** 回合制战斗冻结解除之后：之前因为战斗延后的，立即重查（设计 §7.3 第 4 条）。 */
    void onBattleFreezeCleared(SceneWorld world, ScenePlayer player);

    /** 不接收件箱（测试与不接 game-inbox 的装配）。 */
    InboxHooks NONE = new InboxHooks() {
        @Override
        public void onEntered(SceneWorld world, ScenePlayer player, ScenePlayer carried) {
        }

        @Override
        public void onPersisted(SceneWorld world, ScenePlayer player) {
        }

        @Override
        public void onLeaving(SceneWorld world, ScenePlayer player) {
        }

        @Override
        public void onBattleFreezeCleared(SceneWorld world, ScenePlayer player) {
        }
    };
}
