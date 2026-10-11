package com.game.scene.world;

/**
 * 交出提交、实例已移出世界之后，源节点为这个会话留下的墓碑（scene-handoff-spec §5.5「墓碑」、zone-travel-spec §5.5）。只在场景逻辑线程上读写。
 *
 * <p>只服务一种情形：gate 在收到 PlayerTransfer 之前已发出 PlayerLeave（两帧在链路上交叉），这条 leave 落到源节点时实例已不在。
 * 查到墓碑就按 voluntary 用旧 epoch E、续接的序号写登出墓碑或重连租约（gate 随后把这次 transfer 当过期丢弃）。
 * 墓碑<b>绝不</b>用来释放 E+1：源节点无法知道 gate 是否已改绑。
 *
 * <p>交给链路之后又异步写失败时（{@code ClientSink.playerTransfer} / {@code playerRedirect} 的 {@code onWriteFailed}），源节点写重连租约
 * （跨节点换图另外释放 E+1）——位置只写一次（{@link #markLocationWritten}），交叉的 leave 已写过就不再写。
 *
 * <p><b>跨 zone 传送</b>（批次 5.4，{@link #forRedirect}）：墓碑在<b>重定向帧发出之前</b>就留下（先写待落点、写完才发帧，Q14），
 * 这段时间里它是「帧还没定」的标记（{@link #redirectPending()}）：期间不过期、所在链路断开 / 停服时也不清，帧的去留由待落点写完的回调裁决
 * （墓碑还在 → 发帧，链路已断则写不出、按 TF7 改回源场景；已被交叉的 leave 消费 → 不发帧）。
 */
final class TransferTombstone {

    private final SessionKey session;
    private final long toEpoch;
    /** 已移出世界的实例（位置记录用它的 epoch E、场景与写序号）。 */
    private final ScenePlayer removed;
    private final long expireAtNanos;
    private boolean locationWritten;
    /** 跨 zone 传送：待落点在写、重定向帧还没定（发或不发）。 */
    private boolean redirectPending;
    private boolean writeFailed;

    TransferTombstone(SessionKey session, long toEpoch, ScenePlayer removed, long expireAtNanos) {
        this.session = session;
        this.toEpoch = toEpoch;
        this.removed = removed;
        this.expireAtNanos = expireAtNanos;
    }

    /** 跨 zone 传送的墓碑：留下时重定向帧还没定（{@link #redirectPending()} 为 true，直到 {@link #settleRedirect()}）。 */
    static TransferTombstone forRedirect(SessionKey session, long toEpoch, ScenePlayer removed, long expireAtNanos) {
        TransferTombstone tombstone = new TransferTombstone(session, toEpoch, removed, expireAtNanos);
        tombstone.redirectPending = true;
        return tombstone;
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

    /** 过期了（重定向帧还没定的不算过期：它要等待落点写完的回调来裁决）。 */
    boolean expired(long nowNanos) {
        return !redirectPending && nowNanos - expireAtNanos >= 0;
    }

    boolean redirectPending() {
        return redirectPending;
    }

    /** 重定向帧的去留已定（发了，或确定不发）；返回 false = 之前已定过（同一次待落点写的结果回了两次，调用方的编程错误）。 */
    boolean settleRedirect() {
        if (!redirectPending) {
            return false;
        }
        redirectPending = false;
        return true;
    }

    /** 标记位置已写；返回 true = 这是第一次（调用方该写），false = 之前已写过。 */
    boolean markLocationWritten() {
        if (locationWritten) {
            return false;
        }
        locationWritten = true;
        return true;
    }

    /** 标记帧异步写失败；返回 true = 这是第一次（调用方计一次），false = 之前已标过。 */
    boolean markWriteFailed() {
        if (writeFailed) {
            return false;
        }
        writeFailed = true;
        return true;
    }
}
