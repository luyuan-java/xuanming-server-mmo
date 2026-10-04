package com.game.login.testing;

import com.game.login.session.LoginDevices;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 内存版设备数登记（测试用，没有有效期；语义同 {@code RedisLoginDevices}）：可设为「存储故障」。 */
public final class InMemoryLoginDevices implements LoginDevices {

    public final Map<String, Set<String>> sessions = new HashMap<>();
    /** 会话 → 当前计在哪个账号下（bind 写、leave 读删）。 */
    public final Map<String, String> boundTo = new HashMap<>();
    private final int max;
    public boolean broken;

    public InMemoryLoginDevices(int max) {
        this.max = max;
    }

    @Override
    public synchronized boolean admit(String account, String sessionKey) {
        check();
        Set<String> set = sessions.computeIfAbsent(account, a -> new LinkedHashSet<>());
        if (!set.contains(sessionKey) && set.size() >= max) {
            return false;
        }
        set.add(sessionKey);
        return true;
    }

    @Override
    public synchronized void bind(String account, String sessionKey, String previousAccount) {
        check();
        if (!previousAccount.isEmpty() && !previousAccount.equals(account)) {
            remove(previousAccount, sessionKey);
        }
        boundTo.put(sessionKey, account);
    }

    @Override
    public synchronized void revoke(String account, String sessionKey) {
        check();
        remove(account, sessionKey);
    }

    @Override
    public synchronized void leave(String sessionKey, String accountHint) {
        check();
        String recorded = boundTo.remove(sessionKey);
        if (recorded != null) {
            remove(recorded, sessionKey);
        }
        if (!accountHint.isEmpty()) {
            remove(accountHint, sessionKey);
        }
    }

    public synchronized Set<String> of(String account) {
        return Set.copyOf(sessions.getOrDefault(account, Set.of()));
    }

    private void remove(String account, String sessionKey) {
        Set<String> set = sessions.get(account);
        if (set != null) {
            set.remove(sessionKey);
        }
    }

    private void check() {
        if (broken) {
            throw new IllegalStateException("设备登记存储不可达（测试注入）");
        }
    }
}
