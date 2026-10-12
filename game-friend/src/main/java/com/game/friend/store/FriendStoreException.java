package com.game.friend.store;

/** 好友存储的依赖故障（MySQL 出错、守卫缺行重试用尽、ensure 死锁重试用尽）：上层一律定性为 1003。 */
public final class FriendStoreException extends RuntimeException {

    public FriendStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public FriendStoreException(String message) {
        super(message);
    }
}
