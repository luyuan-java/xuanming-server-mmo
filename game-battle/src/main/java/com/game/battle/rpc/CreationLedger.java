package com.game.battle.rpc;

import com.game.battle.BattleIdentity;
import com.game.battle.GlobalBattleProperties;
import com.game.common.token.NetworkProbes;
import com.game.proto.*;
import java.util.*;

/** 只在 battle 逻辑线程访问。已确认销毁的操作保留墓碑，迟到创建不能复活；进程更换由实例 UUID 拒绝。 */
public final class CreationLedger {
    private record Entry(String operation,byte[] canonical,long expiry,boolean aborted) { }
    private final Map<Long,Entry> entries=new HashMap<>();
    private final GlobalBattleProperties config;
    private final BattleIdentity identity;
    public CreationLedger(GlobalBattleProperties config,BattleIdentity identity){this.config=config;this.identity=identity;}
    public boolean marked(CreateBattleRequest r){return !r.getPlacementOperationId().isEmpty();}
    public boolean admit(CreateBattleRequest r,long now,boolean exists) {
        return admit(r,now,exists,id->id==r.getBattleId()&&exists);
    }
    public boolean admit(CreateBattleRequest r,long now,boolean exists,java.util.function.LongPredicate liveRoom) {
        if(!marked(r))return !config.enabled();
        entries.entrySet().removeIf(e->e.getValue().expiry()<now&&!liveRoom.test(e.getKey()));
        if(!config.enabled()||r.getRequiredPhysicalRegion()!=config.physicalRegion()||!r.getTargetInstanceId().equals(identity.instanceId())
                ||r.getAdmitBeforeMs()<=now||r.getBattleId()==0)return false;
        var old=entries.get(r.getBattleId());
        return old==null||(!old.aborted()&&exists&&old.operation().equals(r.getPlacementOperationId())
                &&Arrays.equals(old.canonical(),NetworkProbes.bytes(r)));
    }
    public void created(CreateBattleRequest r){if(marked(r))entries.put(r.getBattleId(),new Entry(r.getPlacementOperationId(),NetworkProbes.bytes(r),Math.max(r.getDeadlineMs(),r.getAdmitBeforeMs())+60_000,false));}
    public boolean destroy(DestroyBattleRequest r,long now,boolean exists) {
        var old=entries.get(r.getBattleId());
        if(r.getPlacementOperationId().isEmpty()) {
            if(old!=null||!r.getTargetInstanceId().isEmpty())throw new IllegalArgumentException("全球创建操作不允许无身份销毁");
            return true;
        }
        if(!config.enabled()||!r.getTargetInstanceId().equals(identity.instanceId())
                ||old!=null&&!old.operation().equals(r.getPlacementOperationId()))throw new IllegalArgumentException("创建操作或执行实例不符");
        // 和建房同一逻辑线程：旧恢复任务只能对缺席房间立墓碑，不能误杀已成功的原操作。
        if(r.getReason().equals("global_match_abort_if_absent")&&exists)return false;
        entries.put(r.getBattleId(),new Entry(r.getPlacementOperationId(),old==null?new byte[0]:old.canonical(),
                Math.max(now+360_000,old==null?0:old.expiry()),true));
        return true;
    }
    public QueryBattleCreationResponse query(QueryBattleCreationRequest r,boolean exists) {
        var state=BattleCreationState.BATTLE_CREATION_UNKNOWN;
        if(config.enabled()&&!r.getPlacementOperationId().isEmpty()&&r.getTargetInstanceId().equals(identity.instanceId())) {
            var old=entries.get(r.getBattleId());
            if(old==null)state=BattleCreationState.BATTLE_CREATION_ABSENT;
            else if(old.operation().equals(r.getPlacementOperationId()))state=old.aborted()?BattleCreationState.BATTLE_CREATION_ABORTED:
                    exists?BattleCreationState.BATTLE_CREATION_PRESENT:BattleCreationState.BATTLE_CREATION_ABSENT;
        }
        return QueryBattleCreationResponse.newBuilder().setState(state).build();
    }
}
