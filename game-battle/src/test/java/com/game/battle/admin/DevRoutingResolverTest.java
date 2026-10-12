package com.game.battle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.battle.admin.DevRoutingResolver.LookupException;
import com.game.battle.admin.DevRoutingResolver.Resolution;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.proto.AddObserverRequest;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleRouting;
import com.game.proto.CreateBattleRequest;
import org.junit.jupiter.api.Test;

/**
 * dev 接口的快照路由补全（battle-node-spec §7.12）：gate 部分取在线目录、scene 部分取位置记录（只认 o）+ scene 目录的实例；已带路由的原样保留；
 * 观众只补 gate 部分；补不全逐人给原因；读 Redis 失败是故障（不是补不全）。
 */
class DevRoutingResolverTest {

    private final FakeLookups lookups = new FakeLookups()
            .online(101, 1, 2, "gate-inst", 33, 4, "scene-inst")
            .online(102, 1, 2, "gate-inst", 34, 5, "scene-inst-5");
    private final DevRoutingResolver resolver = new DevRoutingResolver(lookups);

    private static BattlePlayerSnapshot player(long id) {
        return BattlePlayerSnapshot.newBuilder().setPlayerId(id).build();
    }

    @Test
    void 参战者路由_gate取在线目录_scene取位置记录与目录实例() throws Exception {
        Resolution<CreateBattleRequest> r = resolver.fillCreate(CreateBattleRequest.newBuilder().setBattleId(9)
                .addPlayers(player(101)).addPlayers(player(102)).build());

        assertThat(r.ok()).isTrue();
        assertThat(r.request().getPlayers(0).getRouting()).isEqualTo(BattleRouting.newBuilder()
                .setSessionId(33).setGateNodeId(2).setGateInstanceId("gate-inst").setZoneId(1)
                .setSceneNodeId(4).setSceneInstanceId("scene-inst").build());
        assertThat(r.request().getPlayers(1).getRouting().getSceneInstanceId()).isEqualTo("scene-inst-5");
        assertThat(r.request().getBattleId()).isEqualTo(9);
    }

    @Test
    void 已带gate实例的快照原样保留() throws Exception {
        BattleRouting given = BattleRouting.newBuilder().setGateInstanceId("explicit").build();
        Resolution<CreateBattleRequest> r = resolver.fillCreate(CreateBattleRequest.newBuilder()
                .addPlayers(player(555).toBuilder().setRouting(given)).build());

        assertThat(r.ok()).isTrue();
        assertThat(r.request().getPlayers(0).getRouting()).isEqualTo(given);
    }

    @Test
    void 补不全逐人给原因_不在线_位置不是在线_scene目录没有_player为0() throws Exception {
        lookups.online(103, 1, 2, "gate-inst", 35, 6, "x");
        lookups.locations.put(103L, new HolderRead(LocationStatus.RECONNECT_LEASE, null, null));
        lookups.online(104, 1, 2, "gate-inst", 36, 7, "y");
        lookups.scenes.remove("1/7");

        Resolution<CreateBattleRequest> r = resolver.fillCreate(CreateBattleRequest.newBuilder()
                .addPlayers(player(101)).addPlayers(player(999)).addPlayers(player(103)).addPlayers(player(104))
                .addPlayers(player(0)).build());

        assertThat(r.ok()).isFalse();
        assertThat(r.unresolved()).hasSize(4);
        assertThat(r.unresolved().get(0)).contains("999").contains("不在线");
        assertThat(r.unresolved().get(1)).contains("103").contains("RECONNECT_LEASE");
        assertThat(r.unresolved().get(2)).contains("scene 目录里没有").contains("104");
        assertThat(r.unresolved().get(3)).contains("player_id 为 0");
    }

    @Test
    void 读失败与位置记录状态未知是故障() {
        lookups.failure = new IllegalStateException("redis down");
        assertThatThrownBy(() -> resolver.fillCreate(CreateBattleRequest.newBuilder().addPlayers(player(101)).build()))
                .isInstanceOf(LookupException.class).hasMessageContaining("在线目录");

        lookups.failure = null;
        lookups.locations.put(101L, new HolderRead(LocationStatus.ERROR, null, "记录损坏"));
        assertThatThrownBy(() -> resolver.fillCreate(CreateBattleRequest.newBuilder().addPlayers(player(101)).build()))
                .isInstanceOf(LookupException.class).hasMessageContaining("记录损坏");
    }

    @Test
    void 观众只补gate部分_scene字段为0_已带路由原样_不在线补不全() throws Exception {
        Resolution<AddObserverRequest> r = resolver.fillObserver(AddObserverRequest.newBuilder().setBattleId(9)
                .setObserverPlayerId(102).build());
        assertThat(r.ok()).isTrue();
        assertThat(r.request().getRouting()).isEqualTo(BattleRouting.newBuilder().setSessionId(34).setGateNodeId(2)
                .setGateInstanceId("gate-inst").setZoneId(1).build());

        AddObserverRequest given = AddObserverRequest.newBuilder().setObserverPlayerId(7)
                .setRouting(BattleRouting.newBuilder().setGateInstanceId("g")).build();
        assertThat(resolver.fillObserver(given).request()).isEqualTo(given);

        Resolution<AddObserverRequest> offline = resolver.fillObserver(AddObserverRequest.newBuilder()
                .setObserverPlayerId(999).build());
        assertThat(offline.ok()).isFalse();
        assertThat(offline.unresolved()).singleElement().asString().contains("不在线");
    }
}
