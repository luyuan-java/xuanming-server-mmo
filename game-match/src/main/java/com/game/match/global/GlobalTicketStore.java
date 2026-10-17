package com.game.match.global;

import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.match.ticket.*;
import java.util.*;

/** 既有整队、活动、观战预检都读同一份 SQL 票据。旧 Redis 凑单原语在全球模式直接拒绝，禁止形成第二权威。 */
public final class GlobalTicketStore implements TicketStore {
    private final GlobalMatchStore store;
    private final RedisKeys keys;
    public GlobalTicketStore(GlobalMatchStore store,RedisKeys keys){this.store=store;this.keys=keys;}
    private static void budget(Deadline d){if(d.expired())throw new Deadline.DependencyException("全球票据请求预算耗尽");}
    private static UnsupportedOperationException old(){return new UnsupportedOperationException("全球票据必须经原子成局事务");}
    @Override public Optional<Ticket> read(long player,Deadline d){budget(d);var row=store.ticket(player);
        if(row.getState()<1||row.getState()>3)return Optional.empty();
        return Optional.of(new Ticket(row.getTicket(),row.getMode(),row.getConfigId(),row.getState()==1?TicketState.QUEUED:
                row.getState()==2?TicketState.MATCHED:TicketState.READY,row.getEnqueuedMs(),row.getZoneId(),
                new QueueRef(row.getMode(),row.getConfigId()).queueKey(keys),row.getRatingCenti(),row.getTeamId(),row.getFormationId(),row.getNotBeforeMs()));}
    @Override public Map<Long,Ticket> readAll(Collection<Long> players,Deadline d){Map<Long,Ticket> out=new HashMap<>();for(long p:players)read(p,d).ifPresent(t->out.put(p,t));return out;}
    @Override public Status status(long player,Deadline d){return new Status(read(player,d),store.now());}
    @Override public boolean heal(long player,Ticket seen,HealMode mode,Deadline d){return read(player,d).isEmpty();}
    @Override public JoinResult enqueue(long p,String t,QueueRef q,int z,long r,long ttl,Deadline d){throw old();}
    @Override public JoinResult createMatched(long p,String t,int m,int c,int z,long r,long ttl,Deadline d){throw old();}
    @Override public OptionalLong createGroup(List<GroupMember> members,int mode,int config,long team,long ttl,Deadline d){
        throw old();}
    @Override public boolean cancel(long player,String ticket,QueueRef queue,Deadline d){budget(d);return store.cancel(player,ticket);}
    @Override public QueueSnapshot snapshot(QueueRef q,int limit,Deadline d){throw old();}
    @Override public boolean drop(QueueRef q,long p,DropReason reason,String seen,Deadline d){budget(d);return reason!=DropReason.INVALID&&store.cancel(p,seen);}
    @Override public boolean dropMalformed(QueueRef q,String member,Deadline d){throw old();}
    @Override public PopResult pop(QueueRef q,String token,List<TicketRef> members,long ttl,Deadline d){throw old();}
    @Override public boolean markReady(TicketRef t,long battle,long ttl,Deadline d){var current=read(t.playerId(),d);return current.isPresent()&&current.get().ticketId().equals(t.ticketId())&&current.get().state()==TicketState.READY&&current.get().battleId()==battle;}
    @Override public int extendMatched(List<TicketRef> tickets,long ttl,Deadline d){throw old();}
    @Override public boolean delete(TicketRef t,Deadline d){budget(d);return store.cancel(t.playerId(),t.ticketId());}
    @Override public int deleteGroup(List<TicketRef> tickets,Deadline d){int n=0;for(var t:tickets)if(delete(t,d))n++;return n;}
    @Override public int requeueFront(QueueRef q,String token,List<TicketRef> members,long ttl,long delay,Deadline d){throw old();}
    @Override public Set<String> queueIndex(Deadline d){budget(d);return Set.of();}
    @Override public long queueLength(QueueRef q,Deadline d){throw old();}
    @Override public boolean pruneIfEmpty(QueueRef q,Deadline d){throw old();}
    @Override public boolean tryLockQueue(QueueRef q,String instance,long ttl,Deadline d){throw old();}
    @Override public void unlockQueue(QueueRef q,String instance,Deadline d){throw old();}
}
