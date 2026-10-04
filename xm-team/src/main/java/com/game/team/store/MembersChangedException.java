package com.game.team.store;

/**
 * S_READ_MEMBERS 重试 {@link TeamStore#READ_MEMBERS_RETRIES} 次后成员表仍在变化（基线 store.go:45-46 ErrMembersChanged）。
 * 不是依赖故障：服务层放弃这次推送并记指标。
 */
public final class MembersChangedException extends RuntimeException {

    public MembersChangedException(String message) {
        super(message);
    }
}
