package com.game.scene.world;

/**
 * 交出提交、PlayerTransfer 已交给链路之后，源节点为这个会话留下的墓碑（scene-handoff-spec §5.5「墓碑」）。只在场景逻辑线程上读写。
 *
 * <p>只服务一种情形：gate 在收到 PlayerTransfer 之前已发出 PlayerLeave（两帧在链路上交叉），这条 leave 落到源节点时实例已不在。
 * 查到墓碑就按 voluntary 用旧 epoch E、序号 +1 写登出墓碑或重连租约（gate 随后把这次 transfer 当过期并放弃 E+1）。
 * 墓碑<b>绝不</b>用来释放 E+1：源节点无法知道 gate 是否已改绑。
 *
 * <p>交给链路之后又异步写失败时（{@code ClientSink.playerTransfer} 的 {@code onWriteFailed}），源节点释放 E+1 并写重连租约——
 * 位置只写一次（{@link #markLocationWritten}），交叉的 leave 已写过就不再写。
 */
final class TransferTombstone {

    private final SessionKey session;
    private final long toEpoch;
    /** 已移出世界的实例（位置记录用它的 epoch E、场景与写序号）。 */
    private final ScenePlayer removed;
    private final long expireAtNanos;
    private boolean locationWritten;

    TransferTombstone(SessionKey session, long toEpoch, ScenePlayer removed, long expireAtNanos) {
        this.session = session;
        this.toEpoch = toEpoch;
        this.removed = removed;
        this.expireAtNanos = expireAtNanos;
    }

    SessionKey session() {
        return session;
    }

    long playerId() {
        return removed.playerId();
    }

    long fromEpoch() {
        return removed.ownerEpoch();
    }

    long toEpoch() {
        return toEpoch;
    }

    ScenePlayer removed() {
        return removed;
    }

    boolean expired(long nowNanos) {
        return nowNanos - expireAtNanos >= 0;
    }

    /** 标记位置已写；返回 true = 这是第一次（调用方该写），false = 之前已写过。 */
    boolean markLocationWritten() {
        if (locationWritten) {
            return false;
        }
        locationWritten = true;
        return true;
    }
}
