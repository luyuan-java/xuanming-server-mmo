package com.game.common.inbox;

/** 收件箱持久主键的唯一分片公式；跨域护栏与Inbox存储必须同源，改公式需要迁移全部行。 */
public final class InboxShards {
    public static final int SHARD_COUNT=64;
    private InboxShards() {}
    public static int rowShard(long playerId) { return (int)Long.remainderUnsigned(mix64(playerId),SHARD_COUNT); }
    public static long mix64(long value) {
        value^=value>>>30;value*=0xbf58476d1ce4e5b9L;
        value^=value>>>27;value*=0x94d049bb133111ebL;
        return value^(value>>>31);
    }
}
