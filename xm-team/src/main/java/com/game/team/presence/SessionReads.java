package com.game.team.presence;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.team.store.SessionLoader;

/**
 * 组队用到的两种在线判定（team-spec §6.6）：
 * <ul>
 *   <li>{@link SessionLoader#load}：规则层的会话四态（惰性转让队长、转让目标在线），逐成员 fail-closed、从不抛出；</li>
 *   <li>{@link #isOnline}：邀请目标的严格单查（基线 service.go:209-216 {@code LoadSession} + {@code IsSessionOnline}）。</li>
 * </ul>
 * 生产实现 {@link TeamSessions}。会阻塞等待异步读（上界是 deadline），只在工作线程上调用。
 */
public interface SessionReads extends SessionLoader {

    /**
     * 玩家此刻是否在游戏里（严格：{@code xm:presence} 条目存在、能解码、与键一致）。
     *
     * @return false = 没有条目（基线会话不存在或不是 ONLINE → 4017）
     * @throws DependencyException 读失败、超出预算、条目损坏或与键不符（基线 LoadSession 出错 → 4030）
     */
    boolean isOnline(long playerId, Deadline deadline);
}
