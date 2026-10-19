package com.game.inbox.notify;

import com.game.proto.player_inbox.PlayerInboxDomain;
import java.time.Duration;

/**
 * 发一次在线提醒（设计 §6.3）。<b>只是提醒</b>：scene 收到后把这名玩家这个域的下一次检查提前，丢了由落地无条件拉取与在线有界兜底补上，
 * 所以是「至多一次」。结局只用于决定是否清 {@code notify_due_ms}。
 */
@FunctionalInterface
public interface Notifier {

    /**
     * 阻塞至多约 {@code timeout}（调用方在自己的工作线程上调，不在 Dubbo I/O 线程上）。从不抛异常。
     */
    NotifyResult notify(long playerId, PlayerInboxDomain domain, Duration timeout);

    /** 不发提醒（单测与只建表的装配）：全部交给补发循环。 */
    Notifier NONE = (playerId, domain, timeout) -> NotifyResult.ERROR;
}
