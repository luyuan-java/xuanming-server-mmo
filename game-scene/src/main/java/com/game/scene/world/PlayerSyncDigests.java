package com.game.scene.world;

import com.game.proto.SyncDigestS2C;
import java.util.function.Consumer;

/**
 * 玩家落地同步摘要读源（253 GetSyncDigest → 254 NotifySyncDigest）。
 * 实现方须保证回调切回场景逻辑线程；任何不可用或异常情形回调 {@link SyncDigestParser#unavailable()}。
 */
@FunctionalInterface
public interface PlayerSyncDigests {

    /** 未配置读源时回 UNAVAILABLE（与 C++ Redis 未就绪口径一致）。 */
    PlayerSyncDigests UNAVAILABLE = (playerId, callback) -> callback.accept(SyncDigestParser.unavailable());

    void read(long playerId, Consumer<SyncDigestS2C> onResultOnLogic);
}
