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

    /** 验签通过后建会话的结局（P2-4：单票单连，被占与号段耗尽都不建会话、不发号）。 */
    public enum OpenStatus {
        OK,
        TICKET_IN_USE,
        ID_EXHAUSTED
    }

    public record OpenOutcome(OpenStatus status, ClientSession session) {
        static OpenOutcome ok(ClientSession session) {
            return new OpenOutcome(OpenStatus.OK, session);
        }

        static OpenOutcome ticketInUse() {
            return new OpenOutcome(OpenStatus.TICKET_IN_USE, null);
        }

        static OpenOutcome exhausted() {
            return new OpenOutcome(OpenStatus.ID_EXHAUSTED, null);
        }
    }

    private final SessionIdAllocator allocator;
    private final ConcurrentHashMap<Integer, ClientSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClientSession> activeTickets = new ConcurrentHashMap<>();
    private long fallbackGeneration;

    public SessionRegistry(SessionIdAllocator allocator) {
        this.allocator = allocator;
    }

    /** 为新连接分配会话号并登记（dev 免验签或纯测试直建会话）；号段耗尽返回 null（调用方关闭连接）。 */
    public synchronized ClientSession open(Channel channel, String clientIp) {
        return open(channel, clientIp, ++fallbackGeneration);
    }

    /** 指定连接代次分配会话号并登记（不占票）。 */
    public synchronized ClientSession open(Channel channel, String clientIp, long connectionGeneration) {
        OptionalInt id = allocator.next(sessions::containsKey);
        if (id.isEmpty()) {
            return null;
        }
        ClientSession session = new ClientSession(id.getAsInt(), channel, clientIp, connectionGeneration, null);
        sessions.put(session.sessionId(), session);
        return session;
    }

    /**
     * P2-4 验签通过后发号、占票并建会话：
     * <ul>
     *   <li>若 {@code ticketKey} 已被另一条在线连接占用，直接回 {@link OpenStatus#TICKET_IN_USE}，不发号、不建会话；</li>
     *   <li>发号仍经 {@link SessionIdAllocator#next} 跳过在用号与保留哨兵 {@code 0xFFFFFFFF}；</li>
     *   <li>新建会话继承建连时分配的 {@code connectionGeneration}。</li>
     * </ul>
     */
    public synchronized OpenOutcome openVerified(Channel channel, String clientIp, long connectionGeneration,
                                                 String ticketKey) {
        if (ticketKey != null && !ticketKey.isEmpty() && activeTickets.containsKey(ticketKey)) {
            return OpenOutcome.ticketInUse();
        }
        OptionalInt id = allocator.next(sessions::containsKey);
        if (id.isEmpty()) {
            return OpenOutcome.exhausted();
        }
        ClientSession session = new ClientSession(id.getAsInt(), channel, clientIp, connectionGeneration, ticketKey);
        sessions.put(session.sessionId(), session);
        if (ticketKey != null && !ticketKey.isEmpty()) {
            activeTickets.put(ticketKey, session);
        }
        return OpenOutcome.ok(session);
    }

    /** 释放会话号与票据占用。只移除同一个会话对象，不会误删号被复用后的新会话。 */
    public synchronized void release(ClientSession session) {
        sessions.remove(session.sessionId(), session);
        String key = session.ticketKey();
        if (key != null && !key.isEmpty()) {
            activeTickets.remove(key, session);
        }
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
