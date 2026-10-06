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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * dev gather 接口在运维令牌没配置的节点上（dev 运行模式；scene-battle-spec §7.18、§13.5「缺令牌 → 503」）：不提供无鉴权的运维面，
 * 带不带令牌头都一律 503，排在运行模式闸与请求体解析之前——不定位、不调 scene、不碰控制面、不计 gather 指标。
 */
@SpringBootTest(classes = DevGatherTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "test.run-mode=dev", "test.admin-token="})
class DevGatherControllerNoTokenTest {

    @LocalServerPort
    int port;

    @Autowired
    StubBattleRoomService rooms;

    @Autowired
    DevGatherTest.FakeScenes scenes;

    @Autowired
    SimpleMeterRegistry meters;

    @Test
    void 令牌没配置_gather与取消一律503_零IO_不计指标() throws Exception {
        scenes.online(101, 3, "scene-a");
        byte[] gather = DevGatherRequest.newBuilder().setMode(DevGatherMode.DEV_GATHER_CREATE).setBattleId(55).setDeadlineMs(1)
                .addMembers(DevGatherMember.newBuilder().setPlayerId(101)).build().toByteArray();
        byte[] cancel = CancelBattlePrepareRequest.newBuilder().setPlayerId(101).setBattleId(55).build().toByteArray();

        assertThat(post(port, DevGatherController.GATHER, gather).statusCode()).as("带了令牌头也没用").isEqualTo(503);
        assertThat(post(port, DevGatherController.GATHER, null, OPERATOR, gather).statusCode()).as("不带令牌头").isEqualTo(503);
        assertThat(post(port, DevGatherController.GATHER, TOKEN, null, gather).statusCode()).as("先于「缺操作人 400」").isEqualTo(503);
        assertThat(post(port, DevGatherController.CANCEL_PREPARE, cancel).statusCode()).isEqualTo(503);

        assertThat(scenes.calls).isEmpty();
        assertThat(rooms.calls).isEmpty();
        assertThat(meters.get("xm.battle.dev.gather").counters().stream().mapToDouble(Counter::count).sum())
                .as("请求没有走到控制器").isZero();
    }
}
