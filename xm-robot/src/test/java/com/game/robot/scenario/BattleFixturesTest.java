package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.net.client.ClientFrameException;
import com.game.net.client.ClientFrames;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleTicketPayload;
import com.game.proto.BattleTokenVerifyRequest;
import com.game.proto.ClientRequest;
import com.game.proto.CreateBattleRequest;
import com.game.proto.GetBattleStateRequest;
import com.game.proto.eBattleTicketRole;
import com.google.protobuf.ByteString;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** battle / battle-edge 场景的纯函数件：建房快照与请求、坏票、坏帧、凑体积请求、间隔。 */
class BattleFixturesTest {

    private static final ByteString SIGNATURE = ByteString.copyFrom("0123456789abcdef".repeat(4), StandardCharsets.US_ASCII);

    @Test
    void battle_id随机且是非0正数() {
        SplittableRandom random = new SplittableRandom(1);
        for (int i = 0; i < 1000; i++) {
            assertThat(BattleFixtures.newBattleId(random)).isPositive();
        }
    }

    @Test
    void 快照_路由一律留空由dev接口补全_打手一刀秒怪_沙包血厚攻低() {
        BattlePlayerSnapshot hero = BattleFixtures.hero(5, "A", 3);
        assertThat(hero.hasRouting()).isFalse();
        assertThat(hero.getTeamIndex()).isZero();
        assertThat(hero.getPhysicalAttack()).isEqualTo(1_000_000);
        assertThat(hero.getItemsList()).singleElement().satisfies(item -> {
            assertThat(item.getItemTableId()).isEqualTo(BattleFixtures.ITEM_HEAL);
            assertThat(item.getCount()).isEqualTo(3);
        });
        assertThat(BattleFixtures.hero(5, "A", 0).getItemsList()).isEmpty();

        BattlePlayerSnapshot tank = BattleFixtures.tank(6, "B", 1, 2);
        assertThat(tank.hasRouting()).isFalse();
        assertThat(tank.getTeamIndex()).isEqualTo(1);
        assertThat(tank.getMaxHealth()).isEqualTo(100_000_000);
        assertThat(tank.getBaseAttributes().getStrength()).isEqualTo(1);
        assertThat(tank.getItemsList()).singleElement().extracting(i -> i.getCount()).isEqualTo(2L);
    }

    @Test
    void 建房请求_PVE单人Dungeon1_PVP两人不带副本_种子固定() {
        CreateBattleRequest pve = BattleFixtures.pve(9, 1234, BattleFixtures.hero(5, "A", 0));
        assertThat(pve.getBattleId()).isEqualTo(9);
        assertThat(pve.getMatchMode()).isEqualTo(BattleFixtures.MATCH_MODE_PVE).isEqualTo(4);
        assertThat(pve.getBattleConfigId()).isEqualTo(BattleFixtures.DUNGEON_1).isEqualTo(1);
        assertThat(pve.getDeadlineMs()).isEqualTo(1234);
        assertThat(pve.getSeed()).isEqualTo(BattleFixtures.SEED);
        assertThat(pve.getPlayersCount()).isEqualTo(1);

        CreateBattleRequest pvp = BattleFixtures.pvp(10, 99, BattleFixtures.tank(5, "A", 0, 0), BattleFixtures.tank(6, "B", 1, 0));
        assertThat(pvp.getMatchMode()).isEqualTo(BattleFixtures.MATCH_MODE_PVP).isEqualTo(3);
        assertThat(pvp.getBattleConfigId()).isZero();
        assertThat(pvp.getPlayersList()).extracting(BattlePlayerSnapshot::getTeamIndex).containsExactly(0, 1);
        assertThat(pvp.getTableFingerprint()).as("指纹留空：不比").isEmpty();
    }

    @Test
    void 签名格式_64位小写hex() {
        assertThat(BattleFixtures.isLowerHex64(SIGNATURE)).isTrue();
        assertThat(BattleFixtures.isLowerHex64(BattleFixtures.upperCaseSignature(SIGNATURE))).isFalse();
        assertThat(BattleFixtures.isLowerHex64(SIGNATURE.substring(1))).isFalse();
        assertThat(BattleFixtures.isLowerHex64(SIGNATURE.concat(ByteString.copyFromUtf8("0")))).isFalse();
    }

    @Test
    void 坏票_翻转一个hex字符_改大写_payload追加未知字段仍能解析() throws Exception {
        ByteString flipped = BattleFixtures.flipFirstHexChar(SIGNATURE);
        assertThat(flipped.size()).isEqualTo(SIGNATURE.size());
        assertThat(BattleFixtures.isLowerHex64(flipped)).isTrue();
        int differing = 0;
        for (int i = 0; i < SIGNATURE.size(); i++) {
            differing += flipped.byteAt(i) == SIGNATURE.byteAt(i) ? 0 : 1;
        }
        assertThat(differing).isEqualTo(1);
        assertThat(BattleFixtures.flipFirstHexChar(ByteString.copyFromUtf8("abc")).toStringUtf8()).isEqualTo("bbc");
        assertThatThrownBy(() -> BattleFixtures.flipFirstHexChar(ByteString.EMPTY)).isInstanceOf(IllegalArgumentException.class);

        assertThat(BattleFixtures.upperCaseSignature(SIGNATURE).toString(StandardCharsets.US_ASCII))
                .isEqualTo(SIGNATURE.toString(StandardCharsets.US_ASCII).toUpperCase());

        BattleTicketPayload payload = BattleTicketPayload.newBuilder().setBattleId(1).setPlayerId(2).setBattleNodeId(3)
                .setBattleInstanceId("uuid").setExpireAtMs(4).setRole(eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT).build();
        ByteString tampered = BattleFixtures.tamperedPayload(payload.toByteString());
        assertThat(tampered).isNotEqualTo(payload.toByteString());
        assertThat(BattleTicketPayload.parseFrom(tampered)).as("字段值不变（多一个未知字段）").satisfies(p -> {
            assertThat(p.getBattleId()).isEqualTo(1);
            assertThat(p.getRole()).isEqualTo(eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
            assertThat(p.getUnknownFields().hasField(15)).isTrue();
        });
    }

    @Test
    void 帧编码可被解回_坏校验和解不开() throws Exception {
        BattleTokenVerifyRequest verify = BattleTokenVerifyRequest.newBuilder().setPayload(ByteString.copyFromUtf8("p"))
                .setSignature(SIGNATURE).build();
        byte[] frame = BattleFixtures.encodeFrame(verify);
        Map<String, com.google.protobuf.Message> accepted = Map.of("BattleTokenVerifyRequest", BattleTokenVerifyRequest.getDefaultInstance());
        assertThat(ClientFrames.decodeBody(Unpooled.wrappedBuffer(frame, 4, frame.length - 4), accepted)).isEqualTo(verify);

        byte[] bad = BattleFixtures.corruptChecksum(frame);
        assertThat(bad).hasSameSizeAs(frame);
        assertThat(frame[frame.length - 1]).as("不改原帧").isNotEqualTo(bad[bad.length - 1]);
        assertThatThrownBy(() -> ClientFrames.decodeBody(Unpooled.wrappedBuffer(bad, 4, bad.length - 4), accepted))
                .isInstanceOf(ClientFrameException.class)
                .satisfies(e -> assertThat(((ClientFrameException) e).reason()).isEqualTo(ClientFrameException.Reason.CHECKSUM));
    }

    @Test
    void 凑体积请求_整条恰好目标字节_体照常解析() throws Exception {
        GetBattleStateRequest body = GetBattleStateRequest.newBuilder().setBattleId(Long.MAX_VALUE).build();
        for (int size : List.of(1024, 1025)) {
            ClientRequest request = BattleFixtures.paddedRequest(77, 140, body, size);
            assertThat(request.getSerializedSize()).isEqualTo(size);
            assertThat(request.getId()).isEqualTo(77);
            assertThat(request.getMessageId()).isEqualTo(140);
            assertThat(GetBattleStateRequest.parseFrom(request.getBody()).getBattleId()).isEqualTo(Long.MAX_VALUE);
        }
        assertThatThrownBy(() -> BattleFixtures.paddedRequest(77, 140, body, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 相邻到达时刻的间隔() {
        assertThat(BattleFixtures.gapsMillis(List.of(0L, 2_000_000_000L, 4_500_000_000L))).containsExactly(2000L, 2500L);
        assertThat(BattleFixtures.gapsMillis(List.of(1L))).isEmpty();
    }
}
