package com.game.discovery.world;

import java.time.Duration;

/** 玩家选线目录的字节读模型；阻塞 IO 只能在业务工作线程调用，不能作为准入凭据。 */
public interface WorldChannelDirectoryStore {
    /** 未发布或过期返回 null；依赖失败抛异常，不伪装成空目录。 */
    byte[] read(int zoneId, int configId);

    /** 领导者令牌和计划版本同时匹配才发布；被围栏返回 false。 */
    boolean publish(int zoneId, String leaderToken, long planVersion, int configId, byte[] payload, Duration ttl);
}
