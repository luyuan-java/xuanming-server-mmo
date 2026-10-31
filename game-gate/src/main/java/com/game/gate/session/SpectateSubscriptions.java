package com.game.gate.session;

import com.game.proto.SpectateTicketPayload;

/**
 * gate 本地的观战订阅表（实现 {@code com.game.gate.spectate.SpectateHub}；会话层只认这个接口）。全部方法任意线程可调、不阻塞；
 * 同一个调用线程上的调用按顺序生效（会话层都在该连接的 EventLoop 上调）。
 */
public interface SpectateSubscriptions {

    /** 没有 hub 的装配（不关心观战的测试）：什么也不做。 */
    SpectateSubscriptions NONE = new SpectateSubscriptions() {
        @Override
        public void subscribe(ClientSession session, SpectateTicketPayload ticket) {
        }

        @Override
        public void unsubscribe(ClientSession session, long battleId) {
        }

        @Override
        public void drop(ClientSession session) {
        }
    };

    /** 订阅凭证指向的那一场（凭证已验过）。一个会话同时只订一场：新订阅替换旧订阅。 */
    void subscribe(ClientSession session, SpectateTicketPayload ticket);

    /** 退订；{@code battleId} 与当前订阅不符时什么也不做（幂等）。 */
    void unsubscribe(ClientSession session, long battleId);

    /** 会话断开或玩家绑定结束：摘除它的订阅（凭证绑的是玩家，换了身份不能接着收）。 */
    void drop(ClientSession session);
}
