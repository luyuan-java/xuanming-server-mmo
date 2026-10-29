package com.game.data.merge.retire;

import static org.assertj.core.api.Assertions.*;
import com.game.common.GameNamespace;
import com.game.data.merge.pb.ZoneMergeRunRow;
import com.game.discovery.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RetirementDeploymentTest {
    private final RedisKeys keys=new RedisKeys(GameNamespace.of("retiretest"));
    private final String text="namespace=retiretest\nsource-zone=4294967295\nrelease-commit="+"a".repeat(40)
            +"\ndirectory-protocol=2\nnode-types=gate,scene\nold-writers-stopped=true\n";
    private RetirementDeployment parse(String input)throws Exception {
        byte[] bytes=input.getBytes(StandardCharsets.ISO_8859_1);
        return RetirementDeployment.parse(bytes,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
    @Test void 外部证据绑定原字节和全部升级类型_支持uint32区号()throws Exception {
        var proof=parse(text);assertThat(proof.namespace()).isEqualTo("retiretest");assertThat(proof.sourceZone()).isEqualTo(-1);
        assertThat(proof.nodeTypes()).containsExactlyInAnyOrder("gate","scene");
        assertThatThrownBy(()->RetirementDeployment.parse(text.getBytes(StandardCharsets.ISO_8859_1),"0".repeat(64))).hasMessageContaining("摘要不符");
    }
    @Test void 缺证据旧协议未退出写者未知字段重复类型和全局区均拒绝() {
        for(String invalid:new String[]{text.replace("directory-protocol=2","directory-protocol=1"),text.replace("old-writers-stopped=true","old-writers-stopped=false"),
                text.replace("node-types=gate,scene","node-types=scene,scene"),text.replace("source-zone=4294967295","source-zone=0"),
                text.replace("release-commit="+"a".repeat(40)+"\n",""),text+"extra=true\n"}){
            assertThatThrownBy(()->parse(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void 清理白名单不含永久原件版本水位玩家位置与节点租约() {
        for(String hot:Set.of(keys.worldChannels(-1),keys.worldDesired(-1),keys.worldCooldown(-1),keys.worldChannelDirectory(-1,7),
                keys.worldReservations(-1,-1),keys.zoneNodeDirectory("gate",-1),keys.zoneNodeExpiry("scene",-1)))
            assertThat(RetiredZoneRedis.isHotKey(keys,-1,hot)).as(hot).isTrue();
        for(String keep:Set.of(keys.worldRetirement(-1),keys.worldPlanVersion(-1),keys.worldLeader(-1),keys.zoneDirectoryFormat(-1),
                keys.zoneNodeSeen("scene",-1),keys.zoneNodeTypes(-1),keys.nodeId("scene",-1,1),keys.nodeIdEpoch("scene",-1,1),keys.worldZones(),
                keys.worldChannels(1),keys.worldZoneKeysPrefix(-1)+"unknown"))
            assertThat(RetiredZoneRedis.isHotKey(keys,-1,keep)).as(keep).isFalse();
        assertThat(RetiredZoneRedis.isHotKey(keys,0,keys.worldChannels(0))).isFalse();
    }
    @Test void 原件绑定合区世代计划和已审部署字节() {
        var run=ZoneMergeRunRow.newBuilder().setRunId("run").setGeneration("generation").setSourceZone(-1).setTargetZone(2).build();
        String original=RetiredZoneRedis.identity(run,"a".repeat(64));
        assertThat(original).contains("4294967295|2");
        assertThat(RetiredZoneRedis.identity(run,"b".repeat(64))).isNotEqualTo(original);
        assertThat(RetiredZoneRedis.identity(run.toBuilder().setGeneration("other").build(),"a".repeat(64))).isNotEqualTo(original);
    }
}
