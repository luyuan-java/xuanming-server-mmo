package com.game.battle.admin;

import com.game.battle.rpc.BattleNodeServiceImpl;
import java.util.Optional;

/**
 * dev 管理接口取控制面进程内入口的方式（生产由 {@code BattleNode::controlPlane} 提供）。经它调用与 Dubbo 走同一条准入与投递路径
 * （准入闸、在途上限、逻辑线程复核），dev 接口不会绕开任何一道闸。
 */
@FunctionalInterface
public interface DevBattleBackend {

    /** 控制面；节点没在运行（启动未完成 / 已停机）时为空。任意线程可调，不阻塞。 */
    Optional<BattleNodeServiceImpl> controlPlane();
}
