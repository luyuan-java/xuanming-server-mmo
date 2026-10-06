package com.game.battle.admin;

import static com.game.battle.admin.DevBattleTestApp.OPERATOR;
import static com.game.battle.admin.DevBattleTestApp.TOKEN;
import static com.game.battle.admin.DevBattleTestApp.post;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.DevGatherMember;
import com.game.api.proto.DevGatherMode;
import com.game.api.proto.DevGatherRequest;
import com.game.battle.testing.StubBattleRoomService;
import com.game.proto.CancelBattlePrepareRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** dev gather 接口在 prod 运行模式下：鉴权通过后一律 403，先于解析请求体，不定位、不调 scene、不碰控制面；令牌仍是第一道闸（scene-battle-spec §7.18）。 */
@SpringBootTest(classes = DevGatherTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "test.run-mode=prod"})
class DevGatherControllerProdTest {

    @LocalServerPort
    int port;

    @Autowired
    StubBattleRoomService rooms;

    @Autowired
    DevGatherTest.FakeScenes scenes;

    @Autowired
    SimpleMeterRegistry meters;

    @Test
    void 非dev_test一律403_先于解析_零IO() throws Exception {
        byte[] gather = DevGatherRequest.newBuilder().setMode(DevGatherMode.DEV_GATHER_CREATE).setBattleId(1).setDeadlineMs(1)
                .addMembers(DevGatherMember.newBuilder().setPlayerId(101)).build().toByteArray();
        byte[] cancel = CancelBattlePrepareRequest.newBuilder().setPlayerId(101).setBattleId(1).build().toByteArray();

        assertThat(post(port, DevGatherController.GATHER, gather).statusCode()).isEqualTo(403);
        assertThat(post(port, DevGatherController.GATHER, TOKEN, OPERATOR, new byte[] {(byte) 0xFF}).statusCode()).isEqualTo(403);
        assertThat(post(port, DevGatherController.CANCEL_PREPARE, cancel).statusCode()).isEqualTo(403);
        assertThat(post(port, DevGatherController.GATHER, null, OPERATOR, gather).statusCode()).as("令牌仍是第一道闸").isEqualTo(401);
        assertThat(scenes.calls).isEmpty();
        assertThat(rooms.calls).isEmpty();
        assertThat(meters.get("xm.battle.dev.gather").tag("mode", "unknown").tag("result", "forbidden").counter().count())
                .as("两次 gather 403").isEqualTo(2);
    }
}
