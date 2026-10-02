package com.game.scene.world;

import com.google.protobuf.Message;

/**
 * 一个玩法功能：把自己处理的客户端玩家服务方法注册进 scene 的请求分发（{@link ClientRequestHandler}）。
 * 方法按契约里的「服务裸名 + 方法名」注册，消息号由消息号注册表解析（消息号由 mmorpg 生成器发、会漂移，不写死）。
 */
@FunctionalInterface
public interface SceneFeature {

    void register(Registrar registrar);

    /** 注册入口。 */
    interface Registrar {

        /**
         * @throws IllegalStateException 契约里没有这个方法、它不是 scene 的客户端玩家服务、请求类型对不上，或已被注册过
         */
        <Q extends Message> void on(String service, String method, Class<Q> requestType, PlayerRequestHandler<Q> handler);
    }
}
