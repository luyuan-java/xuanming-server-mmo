package com.game.scene.world;

/**
 * 回合制战斗在途（备战或战斗中，{@link ScenePlayer#inBattle()}）时一个客户端方法怎么处理（scene-battle-spec §7.13，D11）。
 * 与 {@link FreezePolicy} 并存：注册时两者都要声明，没声明的一律 {@link #REJECT}——以后新加的方法忘了考虑战斗，结果是战斗中被拒，
 * 而不是悄悄改掉战斗快照之外的状态（{@code combat.md:327} 的隐患）。两种冻结互斥，判定先后不影响结果。
 *
 * <p>基线没有中心闸，各系统各回各的码；Java 码与次序照搬，判定收成一个谓词，入口按方法声明。
 */
public enum BattlePolicy {

    /** 照常处理：只读查询（43 / 77 / 54 / 167 / 181 / 190 / 191 / 193）、只给建议（173 / 188）、GM 货币（37 / 49 / 94 / 95）、任务（194 / 195）。 */
    ALLOW,

    /**
     * 进处理器，由处理器 / 服务闸按 {@link ScenePlayer#inBattle()} 回基线码：63 → 3023、84 → 7004 / 7002、属性写 → 25011、宝宝写 → 26008、
     * 192 → 1005。
     */
    GATED,

    /** 静默丢、不进处理器、不回包（134 MoveStart / 132 MoveSync，基线 {@code mvh.cpp:170-173}）。只给场景核心的移动上行。 */
    DROP,

    /** 只把速度清零、不收位置（131 MoveStop，基线 {@code mvh.cpp:188-192}）。只给场景核心的移动上行。 */
    STOP_ONLY,

    /** 不进处理器，回应答内 {@code error_message{1005}}（应答为 {@code Empty} 的方法静默丢）。<b>缺省策略</b>。 */
    REJECT
}
