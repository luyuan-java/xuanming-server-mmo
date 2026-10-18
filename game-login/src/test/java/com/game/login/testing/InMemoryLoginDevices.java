package com.game.login.testing;

import com.game.login.session.LoginDevices;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 内存版设备数登记（测试用，没有有效期——关闭墓碑也不过期；语义同 {@code RedisLoginDevices}）：可设为「存储故障」。 */
public final class InMemoryLoginDevices implements LoginDevices {

    public final Map<String, Set<String>> sessions = new HashMap<>();
    /** 会话 → 当前计在哪个账号下（bind 写、leave 读删）。 */
    public final Map<String, String> boundTo = new HashMap<>();
    /** 留了关闭墓碑的会话（close 写，leave 不动）。 */
    public final Set<String> closed = new HashSet<>();
    private final int max;
    public boolean broken;

    public InMemoryLoginDevices(int max) {
        this.max = max;
    }

    @Override
    public synchronized Admission admit(String account, String sessionKey) {
        check();
        if (closed.contains(sessionKey)) {
            return Admission.CLOSED;
        }
        Set<String> set = sessions.computeIfAbsent(account, a -> new LinkedHashSet<>());
        if (!set.contains(sessionKey) && set.size() >= max) {
            return Admission.FULL;
        }
        set.add(sessionKey);
        return Admission.ADMITTED;
    }

    @Override
    public synchronized boolean bind(String account, String sessionKey, String previousAccount) {
        check();
        if (closed.contains(sessionKey)) {
            remove(account, sessionKey);
            return false;
        }
        if (!previousAccount.isEmpty() && !previousAccount.equals(account)) {
            remove(previousAccount, sessionKey);
        }
        boundTo.put(sessionKey, account);
        return true;
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

    @Override
    public synchronized void close(String sessionKey, String accountHint) {
        leave(sessionKey, accountHint);
        closed.add(sessionKey);
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
