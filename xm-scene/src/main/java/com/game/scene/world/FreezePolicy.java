package com.game.scene.world;

/**
 * 冻结中（跨节点换图的交出事务在途，{@link ScenePlayer#frozen()}）一个客户端方法怎么处理（scene-handoff-spec §5.9，D7）。
 *
 * <p>基线要每个系统自己查冻结，漏一处冻结快照就与内存分叉；Java 把客户端入口收拢到分发处（{@link ClientRequestHandler}），
 * 注册时每个方法声明一个策略，<b>没声明的一律 {@link #REJECT}</b>——以后新加的方法忘了考虑冻结，结果是冻结窗口里被拒，
 * 而不是悄悄改掉冻结快照之外的状态。漏掉的闸另由交出提交时的快照比对兜底检测
 * （{@code xm_scene_transfer_post_freeze_mutations_total}，应恒为 0）。
 *
 * <p>只在 FREEZING 生效；RESOLVING（等 scene-manager 选目标）不冻结，所有方法照常（同基线「18 之后才冻结」）。
 * 冻结中「照常处理」的几类（{@link #ALLOW} / {@link #READ_ONLY} / {@link #GATED}）在分发处的行为相同，区别在处理器对冻结的承诺。
 */
public enum FreezePolicy {

    /**
     * 照常处理：处理器不改可持久化状态，或自己判冻结。84 放技能（基线请求层不拒、只在施法点 no-op；Java 的施法运行态不持久化，
     * 伤害 / buff 两版都未生效）；63 换场景（在途回 3014，由处理器自己判）。
     */
    ALLOW,

    /** 只读冻结内存，照常应答（查询类：43、77、54、167、191、193、190、181）。 */
    READ_ONLY,

    /**
     * 进处理器，由服务闸按 {@link ScenePlayer#frozen()} 回基线码、零改动：背包 / 宝宝 / 属性 / 任务 1005，货币加 / 扣 27003。
     * 处理器里每一处写都必须经过有闸的服务。
     */
    GATED,

    /**
     * 静默丢弃，不进处理器、不回包（基线 player_movement_handler 对冻结实体同样静默丢）。只给应答为 {@code Empty} 的移动上行
     * 134 / 132 / 131，玩法功能不能声明（分发处按「移动」计数：{@code xm_scene_moves_total{result=frozen}}）。
     */
    DROP,

    /**
     * 不进处理器，回应答内 {@code error_message{1005}}（与「功能不可用」1006 同形；应答是 {@code Empty} 的方法静默丢）。
     * <b>缺省策略</b>。173 自动加点（D9：基线不过写前置，冻结中照改随后被丢）、94 / 95 GM 封禁 / 解封货币显式声明为它。
     */
    REJECT
}
