package com.game.scene.world;

/**
 * 玩家的换图在途阶段（scene-handoff-spec §5.5、zone-travel-spec §5.5；不持久化，只在场景逻辑线程上读写）。
 *
 * <ul>
 *   <li>{@link #NONE}：没有在途换图；</li>
 *   <li>{@link #PRECHECK}：只给跨 zone 传送（226，{@link SwitchReason#TRAVEL}，批次 5.4）：同步校验已过、应答已转成延迟应答，正在读在队关系
 *       （3026 的判定）。<b>不冻结</b>，同 RESOLVING；槽在在队检查上限 + 1 s 后过期，作废方先经槽上的延迟应答回 3027 再清槽，
 *       所以 226 的应答一定在这个时限内到；</li>
 *   <li>{@link #RESOLVING}：63 的远端去向 / 226 已受理（应答 {@code {0}} 已回），正在等 scene-manager 选目标。<b>不冻结</b>：
 *       玩家照常游玩，离场 / 断链 / 接管 / 失去归属照现有逻辑处理，迟到的选择结果因实例已不在被丢弃（同基线「18 之后才冻结」）；</li>
 *   <li>{@link #FREEZING}：目标在别的节点 / 别的 zone，已停下、拍下冻结快照、交出事务在途。冻结中可变更的入口一律拒绝或延后
 *       （§5.9），离开 / 接管只记下、等交出结局出来再处理。</li>
 * </ul>
 */
public enum SwitchPhase {
    NONE,
    PRECHECK,
    RESOLVING,
    FREEZING
}
