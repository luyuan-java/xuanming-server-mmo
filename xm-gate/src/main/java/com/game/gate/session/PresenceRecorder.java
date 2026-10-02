package com.game.gate.session;

/**
 * 会话 → 玩家在线目录的写入口。{@link ClientDispatcher} 在会话所属 EventLoop 上调用：scene 确认进场时 {@link #online}，
 * 场景绑定结束（离场、进场失败、被踢、链路断开）或会话关闭时 {@link #offline}。实现必须非阻塞（异步写 Redis）。
 */
public interface PresenceRecorder {

    /** 不记录（测试 / 不需要在线目录的装配）。 */
    PresenceRecorder NONE = new PresenceRecorder() {
        @Override
        public void online(long playerId, int sessionId, long ownerEpoch) {
        }

        @Override
        public void offline(long playerId, int sessionId) {
        }
    };

    /**
     * @param ownerEpoch 这次进场的数据归属 epoch：同一玩家的两次登录以它定先后（不同会话的确认可能乱序到达），
     *                   epoch 更低的迟到确认不得覆盖新登录的条目
     */
    void online(long playerId, int sessionId, long ownerEpoch);

    /** 只撤销这个会话写的那一条（同一玩家在别处的新会话不受影响）。 */
    void offline(long playerId, int sessionId);
}
