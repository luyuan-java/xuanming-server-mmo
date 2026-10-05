package com.game.battle.admin;

import static com.game.battle.admin.DevBattleTestApp.OPERATOR;
import static com.game.battle.admin.DevBattleTestApp.TOKEN;
import static com.game.battle.admin.DevBattleTestApp.post;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.testing.StubBattleRoomService;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.CreateBattleRequest;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.IssueBattleTicketRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * dev 管理接口在 prod 运行模式下（接口总注册，battle-node-spec §7.12、§13.8）：鉴权通过后一律 403，<b>先于解析请求体</b>（坏请求体也是 403）、
 * 不读 Redis、不碰控制面；鉴权失败照样先是 401。
 */
@SpringBootTest(classes = DevBattleTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "test.run-mode=prod"})
class DevBattleControllerProdTest {

    @LocalServerPort
    int port;

    @Autowired
    StubBattleRoomService rooms;

    @Autowired
    FakeLookups lookups;

    @Test
    void 非dev_test一律403_先于解析_零IO() throws Exception {
        lookups.failure = new IllegalStateException("prod 下不该读 Redis");
        byte[] create = CreateBattleRequest.newBuilder().setBattleId(9)
                .addPlayers(BattlePlayerSnapshot.newBuilder().setPlayerId(101)).build().toByteArray();

        HttpResponse<byte[]> valid = post(port, DevBattleController.CREATE, create);
        HttpResponse<byte[]> garbage = post(port, DevBattleController.CREATE, TOKEN, OPERATOR, new byte[] {(byte) 0xFF, 0x01});
        HttpResponse<byte[]> destroy = post(port, DevBattleController.DESTROY, DestroyBattleRequest.getDefaultInstance().toByteArray());
        HttpResponse<byte[]> ticket = post(port, DevBattleController.ISSUE_TICKET,
                IssueBattleTicketRequest.getDefaultInstance().toByteArray());
        HttpResponse<byte[]> noToken = post(port, DevBattleController.CREATE, null, OPERATOR, create);

        assertThat(valid.statusCode()).isEqualTo(403);
        assertThat(garbage.statusCode()).isEqualTo(403);
        assertThat(destroy.statusCode()).isEqualTo(403);
        assertThat(ticket.statusCode()).isEqualTo(403);
        assertThat(noToken.statusCode()).as("令牌仍是第一道闸").isEqualTo(401);
        assertThat(rooms.calls).isEmpty();
    }
}
