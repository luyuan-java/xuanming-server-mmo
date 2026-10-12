package com.game.scene.world;

import com.google.protobuf.Message;

/**
 * 一个玩法功能：把自己处理的客户端玩家服务方法注册进 scene 的请求分发（{@link ClientRequestHandler}）。
 * 方法按契约里的「服务裸名 + 方法名」注册，消息号由消息号注册表解析（消息号由 mmorpg 生成器发、会漂移，不写死）。
 * 每个方法同时声明自己在冻结中（跨节点换图交出在途）的策略（{@link FreezePolicy}）与回合制战斗在途时的策略（{@link BattlePolicy}），
 * 不声明的两者都缺省 REJECT。
 */
@FunctionalInterface
public interface SceneFeature {

    void register(Registrar registrar);

    /** 注册入口。 */
    interface Registrar {

        /**
         * 注册一个方法，冻结策略与战斗策略都取缺省 REJECT（冻结中 / 战斗中回应答内 1005、不进处理器）。
         *
         * @throws IllegalStateException 同 {@link #on(String, String, Class, FreezePolicy, BattlePolicy, PlayerRequestHandler)}
         */
        default <Q extends Message> void on(String service, String method, Class<Q> requestType,
                                            PlayerRequestHandler<Q> handler) {
            on(service, method, requestType, FreezePolicy.REJECT, BattlePolicy.REJECT, handler);
        }

        /**
         * 注册一个方法并声明它的冻结策略（scene-handoff-spec §5.9）与战斗策略（scene-battle-spec §7.13）。只给冻结策略的旧重载已删掉：
         * 新方法不能静默得到战斗策略的缺省值。
         *
         * @throws IllegalStateException 契约里没有这个方法、它不是 scene 的客户端玩家服务、请求类型对不上、已被注册过，
         *                               或声明了 {@link FreezePolicy#DROP} / {@link BattlePolicy#DROP} / {@link BattlePolicy#STOP_ONLY}
         *                               （只给场景核心的移动上行）
         */
        <Q extends Message> void on(String service, String method, Class<Q> requestType, FreezePolicy freeze,
                                    BattlePolicy battle, PlayerRequestHandler<Q> handler);
    }
}
