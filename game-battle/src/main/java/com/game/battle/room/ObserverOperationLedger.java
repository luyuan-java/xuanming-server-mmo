package com.game.battle.room;

import com.game.proto.AddObserverRequest;
import com.game.proto.RemoveObserverRequest;
import com.google.protobuf.CodedOutputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * 单个battle进程、逻辑线程独占的观战操作账本。它位于房间表之上，房间不存在时也能先取消后拒绝迟到Add。
 * 只有已过准入截止且不在房中的项可回收；实例重启换UUID，不能继承或证明旧实例的操作。
 */
final class ObserverOperationLedger {
    static final long MAX_ADMISSION_MS=30_000;
    static final int DEFAULT_CAPACITY=100_000;
    static final class Full extends IllegalStateException { Full(){super("观战取消账本已满，拒绝新增操作");} }
    private static final class Entry {
        final String operation;final long battle,player,deadline;
        byte[] digest;boolean cancelled,active;
        /** 本操作首次被拒的 tip；0 = 不是被拒关闭，而是被 Remove / 回收封闭。 */
        int rejectTip;
        Entry(String operation,long battle,long player,long deadline){this.operation=operation;this.battle=battle;this.player=player;this.deadline=deadline;}
    }
    private final String instance;
    private final LongSupplier clock;
    private final int capacity;
    private long clockHighWater;
    private final Map<String,Entry> entries=new HashMap<>();
    private final PriorityQueue<Entry> expiry=new PriorityQueue<>(Comparator.comparingLong(e->e.deadline));
    ObserverOperationLedger(String instance,LongSupplier clock,int capacity){
        if(instance==null||instance.isEmpty()||capacity<1)throw new IllegalArgumentException("观战账本实例或容量无效");
        this.instance=instance;this.clock=Objects.requireNonNull(clock);this.capacity=capacity;
    }
    private long now(){clockHighWater=Math.max(clockHighWater,clock.getAsLong());return clockHighWater;}
    private void reap(long time){for(int count=0;count<128&&!expiry.isEmpty()&&expiry.peek().deadline<=time;count++){var e=expiry.remove();if(!e.active)entries.remove(e.operation,e);}}
    private void validate(String operation,long battle,long player,long deadline,String target){
        UUID id;try{id=UUID.fromString(operation);}catch(RuntimeException invalid){throw new IllegalArgumentException("观战原操作号必须为UUID");}
        if(!id.toString().equals(operation)||(id.getMostSignificantBits()==0&&id.getLeastSignificantBits()==0)||battle==0||player==0||deadline<=0||!instance.equals(target))throw new IllegalArgumentException("观战原操作或目标实例不符");
    }
    private Entry entry(String operation,long battle,long player,long deadline,long time){
        var e=entries.get(operation);
        if(e!=null){if(e.battle!=battle||e.player!=player||e.deadline!=deadline)throw new IllegalArgumentException("观战UUID不能更改原角色、房间或截止");return e;}
        if(deadline>time&&deadline-time>MAX_ADMISSION_MS)throw new IllegalArgumentException("观战准入截止超过30秒");
        if(entries.size()>=capacity)throw new Full();
        e=new Entry(operation,battle,player,deadline);entries.put(operation,e);expiry.add(e);return e;
    }
    /** false表示原操作已有取消墓碑；同UUID原Add任何字节改变都拒绝。 */
    boolean begin(AddObserverRequest request){
        validate(request.getSpectatorOperationId(),request.getBattleId(),request.getObserverPlayerId(),request.getAdmitBeforeMs(),request.getTargetInstanceId());
        long time=now();reap(time);if(request.getAdmitBeforeMs()<=time)throw new IllegalArgumentException("观战准入已过期");
        var e=entry(request.getSpectatorOperationId(),request.getBattleId(),request.getObserverPlayerId(),request.getAdmitBeforeMs(),time);byte[] digest=digest(request);
        if(e.digest!=null&&!MessageDigest.isEqual(e.digest,digest))throw new IllegalArgumentException("同观战UUID不得替换原Add内容");
        if(e.digest==null)e.digest=digest;return !e.cancelled;
    }
    void activate(String operation){var e=entries.get(operation);if(e==null||e.cancelled)throw new IllegalStateException("观战操作未获准入");e.active=true;}
    /** 先保存墓碑，再由调用者摘房内槽；失败时不能清房或回成功。 */
    boolean cancel(RemoveObserverRequest request){
        validate(request.getSpectatorOperationId(),request.getBattleId(),request.getObserverPlayerId(),request.getAdmitBeforeMs(),request.getTargetInstanceId());
        long time=now();reap(time);var e=entry(request.getSpectatorOperationId(),request.getBattleId(),request.getObserverPlayerId(),request.getAdmitBeforeMs(),time);e.cancelled=true;return true;
    }
    /** StopWatch、签票失败及房间终结使用已登记原件，不能制造新的准入身份。 */
    boolean cancelKnown(String operation){var e=entries.get(operation);if(e==null)return false;e.cancelled=true;return true;}
    /** AddObserver 的拒绝出口：写墓碑并记下首次拒绝码（先写为准）。{@code begin} 通过之后才调，原操作一定已登记。 */
    void reject(String operation,int tip){var e=entries.get(operation);if(e==null)throw new IllegalStateException("被拒观战缺少原操作账本");e.cancelled=true;if(e.rejectTip==0)e.rejectTip=tip;}
    /**
     * 这个原操作首次被拒的 tip，0 = 没有记录。match 的前台请求与后台恢复可能并发发同一个 op，或 SQL 提交失败后重发同一个 op：
     * 重放必须得到同一结论，否则首次回满员、重放回别的码，这次观战会被记成「无法观战」而不是转大厅档。
     */
    int firstRejection(String operation){var e=entries.get(operation);return e==null?0:e.rejectTip;}
    boolean queryValid(com.game.proto.QueryObserverRequest request){
        try{validate(request.getSpectatorOperationId(),request.getBattleId(),request.getObserverPlayerId(),1,request.getTargetInstanceId());}
        catch(IllegalArgumentException invalid){return false;}
        reap(now());var e=entries.get(request.getSpectatorOperationId());return e==null||e.battle==request.getBattleId()&&e.player==request.getObserverPlayerId();
    }
    void detached(String operation){var e=entries.get(operation);if(e==null)throw new IllegalStateException("观战槽缺少原操作账本");e.active=false;long time=now();if(e.deadline<=time)entries.remove(operation,e);reap(time);}
    String instance(){return instance;}
    int size(){reap(now());return entries.size();}
    private static byte[] digest(AddObserverRequest request){
        byte[] bytes=new byte[request.getSerializedSize()];var out=CodedOutputStream.newInstance(bytes);out.useDeterministicSerialization();
        try{request.writeTo(out);out.checkNoSpaceLeft();return MessageDigest.getInstance("SHA-256").digest(bytes);}
        catch(java.io.IOException|java.security.GeneralSecurityException failure){throw new IllegalStateException("观战原Add摘要失败",failure);}
    }
}
