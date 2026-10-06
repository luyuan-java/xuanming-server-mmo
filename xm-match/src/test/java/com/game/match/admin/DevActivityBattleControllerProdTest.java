package com.game.match.admin;

import static com.game.match.admin.DevActivityBattleTestApp.OPERATOR;
import static com.game.match.admin.DevActivityBattleTestApp.post;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakeMemberPrecheck;
import com.game.match.testing.InMemoryTicketStore;
import com.game.proto.BattleActivityContext;
import com.game.proto.eBattleActivityKind;
import com.game.proto.match.StartActivityBattleRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * dev 活动开战接口在 prod 运行模式下（match-spec §7.2 末条、§9.10）：一律 403，先于解析请求体与操作人检查，不预检、不建票、不开局——
 * 这个接口能给任意玩家开一场照常结算发奖的战斗，生产上绝不能开。
 */
@SpringBootTest(classes = DevActivityBattleTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "test.run-mode=prod"})
class DevActivityBattleControllerProdTest {

    @LocalServerPort
    int port;

    @Autowired
    InMemoryTicketStore tickets;

    @Autowired
    FakeMemberPrecheck precheck;

    @Autowired
    FakeGatherLauncher gather;

    @Test
    void 运行模式不是dev或test_一律403_先于解析_零副作用() throws Exception {
        byte[] valid = StartActivityBattleRequest.newBuilder().setBattleConfigId(1).addAllMemberPlayerIds(List.of(9901L, 9902L))
                .setActivityContext(BattleActivityContext.newBuilder().setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL).setGuildId(1)
                        .setActivityId(2).setPeriodKey(3).setGuildPeriodKey(4).setInitiatorPlayerId(9901))
                .build().toByteArray();

        HttpResponse<byte[]> forbidden = post(port, OPERATOR, valid);

        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(new String(forbidden.body(), StandardCharsets.UTF_8)).contains("dev / test", "prod");
        assertThat(post(port, OPERATOR, new byte[] {(byte) 0xFF}).statusCode()).as("先于解析请求体").isEqualTo(403);
        assertThat(post(port, null, valid).statusCode()).as("先于操作人检查").isEqualTo(403);
        assertThat(precheck.rosters).isEmpty();
        assertThat(tickets.calls).isEmpty();
        assertThat(gather.plans).isEmpty();
    }
}
