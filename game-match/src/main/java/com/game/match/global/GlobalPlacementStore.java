package com.game.match.global;

import com.game.common.deadline.Deadline;
import com.game.match.placement.PlacementStore;
import com.game.match.proto.BattlePlacement;
import com.game.proto.CreateBattleRequest;

/** 观战与补签也从 SQL 成局定位；Redis 清空或投影失败不能把已建房误判成不存在。 */
public final class GlobalPlacementStore implements PlacementStore {
    private final GlobalMatchStore store;
    private final PlacementStore projection;
    public GlobalPlacementStore(GlobalMatchStore store,PlacementStore projection){this.store=store;this.projection=projection;}
    @Override public boolean write(BattlePlacement placement){return projection.write(placement);}
    @Override public void delete(long battle){projection.delete(battle);}
    @Override public Read read(long battle,Deadline deadline){
        if(deadline.expired())return new Read.Failed("SQL 落点读取预算耗尽");
        try{
            var f=store.formation(battle);if(f==null||f.getState()==5)return new Read.Absent();
            if(f.getCreateRequest().isEmpty())return new Read.Failed("成局备战尚未完成");
            var create=CreateBattleRequest.parseFrom(f.getCreateRequest());
            var placement=BattlePlacement.newBuilder().setBattleId(battle).setMode(f.getMode()).setBattleConfigId(f.getConfigId())
                    .setCreatedAtMs(f.getCreatedMs()).setDeadlineMs(f.getDeadlineMs()).setBattleNodeId(f.getNodeId()).setBattleInstanceId(f.getInstanceId())
                    .setRpcHost(f.getHost()).setRpcPort(f.getPort()).setAttempt(1).setSpectateVisibility(create.getSpectateVisibilityValue());
            create.getPlayersList().forEach(p->placement.addPlayerNames(p.getPlayerName()));return new Read.Found(placement.build());
        }catch(Exception e){return new Read.Failed("SQL 成局落点暂不可用");}
    }
}
