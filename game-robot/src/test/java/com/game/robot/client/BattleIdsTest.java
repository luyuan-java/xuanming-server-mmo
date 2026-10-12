package com.game.robot.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.contract.MessageIdRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** battle 场景用到的消息号按「服务裸名 + 方法名」从同步来的契约解析，数值与 battle-node-spec §5.1 的表一致（契约同步改了号这里会先红）。 */
class BattleIdsTest {

    @Test
    void 消息号从契约解析_与规格表一致() {
        BattleIds ids = BattleIds.resolve(MessageIdRegistry.loadFromClasspath());
        assertThat(List.of(ids.getBattleState(), ids.submitAction(), ids.setAutoBattle(), ids.stopWatch()))
                .as("四条直连上行（白名单）").containsExactly(140, 149, 162, 165);
        assertThat(List.of(ids.turnResult(), ids.battleStart(), ids.battleEnd(), ids.spectateTurnResult(), ids.spectateState(),
                ids.spectateEnd(), ids.battleAssigned())).containsExactly(139, 143, 150, 158, 161, 166, 177);
        assertThat(ids.sendTip()).isEqualTo(23);
        assertThat(ids.notWhitelisted()).as("合法契约号但不在直连白名单（MatchService.JoinQueue）").isEqualTo(157);
    }
}
