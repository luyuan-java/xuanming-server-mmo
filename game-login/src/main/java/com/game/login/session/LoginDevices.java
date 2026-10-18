package com.game.login.session;

import java.time.Duration;

/**
 * 每账号设备数上限（同 mmorpg {@code MaxDevicesPerAccount}，回 2024 kTooManyDevices）：数的是账号下「已绑定、没在游戏里」的连接。
 * 登记于 TCP Login（以及会话上的建角 / 进游戏，见 {@link #admit} 的续期），进游戏成功指令发出 / 会话结束时注销；
 * 没注销干净的（gate 崩了、登录应答没送到 gate）按登记的有效期自愈。
 * 实现可阻塞（Redis），只在 login 工作线程上调用；存储故障抛异常（调用方回 2023 kLoginRedisSetFailed）。
 *
 * <p><b>关闭墓碑</b>（mmorpg client-request-ordering.md §6.1 的 login 残留）：gate 调 login 超过 5 s 就放弃这次调用，会话随后可能断开、
 * gate 发 sessionClosed 注销；而 login 上那次调用还在跑，它的 {@link #admit}（登录 / 建角 / 进游戏的续期）与 {@link #bind}
 * 会在注销之后把这条已经不存在的会话登记回名单，占着名额直到有效期（30 分钟）。所以会话结束走 {@link #close}：注销之外在会话反查上
 * 留一个短期的关闭墓碑，墓碑期间 {@link #admit} 回 {@link Admission#CLOSED}、{@link #bind} 不记归属。
 */
public interface LoginDevices {

    /**
     * 关闭墓碑的有效期。下界：大于最长的在途 login 调用从开始执行到最后一次设备写的时间——登录是外部认证 + 账号库（取连接 ≤ 3 s、
     * 每条语句 socketTimeout 10 s）+ 签令牌 + {@link #bind}，最坏约 30 s；建角 / 进游戏的续期在处理器开头。排队不算在内：
     * sessionClosed 的注销与请求进同一个 login 工作队列、排在请求之后，墓碑落下时请求已经开始执行。
     * 上界：远小于 gate 会话号回绕到同一个号的时间（会话序号 17 位、循环递增并跳过在用的号：同一 gate 进程要在 60 s 内新建 13 万条连接，
     * 即每秒 2000 条以上，才会在墓碑期间把这个号发给新连接；那时新连接的第一次登录被当成已关闭的会话拒掉，墓碑过期后重登即可）。
     */
    Duration CLOSED_TOMBSTONE_TTL = Duration.ofSeconds(60);

    /** {@link #admit} 的结局。 */
    enum Admission {
        /** 已登记（本会话已在名单里时是续期）。 */
        ADMITTED,
        /** 名单里已有上限个别的会话：拒绝，什么都不改。 */
        FULL,
        /** 会话已结束（{@link #close} 留下的关闭墓碑还在）：拒绝，什么都不改。这时会话已关，回包送不到。 */
        CLOSED
    }

    /**
     * 先判再登记（基线先登记再判、被拒的连接仍留在集合里且已是登录态，限额可被绕过）：会话已关闭 → {@link Admission#CLOSED}；
     * 本会话已在该账号名单里 → 续期、放行；否则名单里已有 {@code max} 个别的会话 → 拒绝、什么都不改；否则登记、放行。
     *
     * @param sessionKey 会话键（{@link #sessionKey}）
     */
    Admission admit(String account, String sessionKey);

    /**
     * 登录成功、gate 将把会话绑到 {@code account}：会话之前绑的是别的账号就从那个账号的名单里注销（只在成功之后做：被拒的换号登录
     * 不改 gate 的绑定，旧账号的登记必须留着），并记下会话当前计在哪个账号下（断线时按它注销）。
     *
     * @param previousAccount gate 记的会话原账号（空 = 没有）
     * @return false = 会话已关闭（关闭墓碑还在）：不记归属，并撤销本会话在 {@code account} 名单里的登记——那是这次登录的 {@link #admit}
     *         在会话结束之前写下的，{@link #close} 那时还不知道它计在哪个账号下
     */
    boolean bind(String account, String sessionKey, String previousAccount);

    /** 撤销本次新登记（登记之后取 / 建账号失败，gate 不会绑这个账号）。 */
    void revoke(String account, String sessionKey);

    /**
     * 会话离开窗口（进游戏 / 重定向，会话还在）：按记下的账号注销，另外按 gate 给的账号注销（两者不同或记录已过期时都覆盖到）。
     * 不留墓碑——进游戏失败回到大厅后，这条会话还要能重新登记；已有的关闭墓碑原样保留（迟到的进场应答也会走到这里）。
     *
     * @param accountHint gate 记的会话账号（可空）
     */
    void leave(String sessionKey, String accountHint);

    /**
     * 会话结束（gate 的 sessionClosed）：同 {@link #leave} 注销，并留关闭墓碑（有效期 {@link #CLOSED_TOMBSTONE_TTL}），
     * 挡住 gate 注销之后才落到的迟到写（见类注释）。重复关闭只刷新墓碑的有效期。
     *
     * @param accountHint gate 记的会话账号（可空）
     */
    void close(String sessionKey, String accountHint);

    /** 会话键：gate 进程实例 + 会话号（节点号会被复用，实例 uuid 才唯一）。 */
    static String sessionKey(String gateInstanceId, long sessionId) {
        return gateInstanceId + "/" + Long.toUnsignedString(sessionId);
    }
}
