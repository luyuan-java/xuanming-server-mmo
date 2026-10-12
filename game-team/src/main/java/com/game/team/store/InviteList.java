package com.game.team.store;

import java.util.List;

/**
 * S_INVITE_LIST 的结果（基线 store.go:416-436 返回 {@code (nowMs, entries)}）。
 *
 * @param nowMs   本次执行的 Redis TIME（毫秒）：ListMyInvites 回包的 server_time_ms
 * @param entries 剔除过期项之后剩下的项，按 ZSET 顺序（score 升序、同分按成员字典序）；不可变
 */
public record InviteList(long nowMs, List<InviteIndexEntry> entries) {

    public InviteList {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
