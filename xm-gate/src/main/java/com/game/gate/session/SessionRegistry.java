package com.game.gate.session;

import io.netty.channel.Channel;
import java.util.Collection;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线会话表：会话号 → 会话。
 *
 * <p>线程模型：{@link #open} 来自各连接的 EventLoop（加锁分配号）；{@link #get} / {@link #all} 供节点链路线程
 * 查找下行目标，查到后必须经 {@link ClientSession#execute} 回到会话所属线程再读写会话状态。
 */
public final class SessionRegistry {

    private final SessionIdAllocator allocator;
    private final ConcurrentHashMap<Integer, ClientSession> sessions = new ConcurrentHashMap<>();

    public SessionRegistry(SessionIdAllocator allocator) {
        this.allocator = allocator;
    }

    /** 为新连接分配会话号并登记；号段耗尽返回 null（调用方关闭连接）。 */
    public synchronized ClientSession open(Channel channel, String clientIp) {
        OptionalInt id = allocator.next(sessions::containsKey);
        if (id.isEmpty()) {
            return null;
        }
        ClientSession session = new ClientSession(id.getAsInt(), channel, clientIp);
        sessions.put(session.sessionId(), session);
        return session;
    }

    /** 释放会话号。只移除同一个会话对象，不会误删号被复用后的新会话。 */
    public void release(ClientSession session) {
        sessions.remove(session.sessionId(), session);
    }

    public ClientSession get(int sessionId) {
        return sessions.get(sessionId);
    }

    /** 当前全部会话的弱一致视图。 */
    public Collection<ClientSession> all() {
        return sessions.values();
    }

    public int size() {
        return sessions.size();
    }

    /** 关闭全部客户端连接（进程退出时）；各会话的断线流程照常在各自线程上跑完后才从表里移除。 */
    public void closeAll() {
        for (ClientSession session : sessions.values()) {
            session.close();
        }
    }
}
