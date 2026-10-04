package com.game.login.session;

/**
 * 每账号设备数上限（同 mmorpg {@code MaxDevicesPerAccount}，回 2024 kTooManyDevices）：数的是账号下「已绑定、没在游戏里」的连接。
 * 登记于 TCP Login（以及会话上的建角 / 进游戏，见 {@link #admit} 的续期），进游戏成功指令发出 / 会话结束时注销；
 * 没注销干净的（gate 崩了、登录应答没送到 gate）按登记的有效期自愈。
 * 实现可阻塞（Redis），只在 login 工作线程上调用；存储故障抛异常（调用方回 2023 kLoginRedisSetFailed）。
 */
public interface LoginDevices {

    /**
     * 先判再登记（基线先登记再判、被拒的连接仍留在集合里且已是登录态，限额可被绕过）：本会话已在该账号名单里 → 续期、放行；
     * 否则名单里已有 {@code max} 个别的会话 → 拒绝、什么都不改；否则登记、放行。
     *
     * @param sessionKey 会话键（{@link #sessionKey}）
     * @return true = 放行
     */
    boolean admit(String account, String sessionKey);

    /**
     * 登录成功、gate 将把会话绑到 {@code account}：会话之前绑的是别的账号就从那个账号的名单里注销（只在成功之后做：被拒的换号登录
     * 不改 gate 的绑定，旧账号的登记必须留着），并记下会话当前计在哪个账号下（断线时按它注销）。
     *
     * @param previousAccount gate 记的会话原账号（空 = 没有）
     */
    void bind(String account, String sessionKey, String previousAccount);

    /** 撤销本次新登记（登记之后取 / 建账号失败，gate 不会绑这个账号）。 */
    void revoke(String account, String sessionKey);

    /**
     * 注销会话：按记下的账号注销，另外按 gate 给的账号注销（两者不同或记录已过期时都覆盖到）。
     *
     * @param accountHint gate 记的会话账号（可空）
     */
    void leave(String sessionKey, String accountHint);

    /** 会话键：gate 进程实例 + 会话号（节点号会被复用，实例 uuid 才唯一）。 */
    static String sessionKey(String gateInstanceId, long sessionId) {
        return gateInstanceId + "/" + Long.toUnsignedString(sessionId);
    }
}
