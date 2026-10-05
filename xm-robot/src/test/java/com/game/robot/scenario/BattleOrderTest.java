package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.MessageContent;
import com.game.robot.client.BattleFrame;
import com.game.robot.client.BattleInbox;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 直连帧序列的判读（battle-node-spec §5.8）：挂机尾部的两种合法形状与各种违约、观众尾部、短序列。 */
class BattleOrderTest {

    private static final int TURN = 139;
    private static final int END = 150;
    private static final int AUTO = 162;
    private static final int SPECTATE_TURN = 158;
    private static final int SPECTATE_END = 166;
    private static final long MS = 1_000_000L;

    /** 按「标签@毫秒」造一串记录：push:N / reply:N / fin / reset。 */
    private static List<BattleFrame> frames(String... specs) {
        BattleInbox inbox = new BattleInbox();
        for (String spec : specs) {
            String[] parts = spec.split("@");
            long at = Long.parseLong(parts[1]) * MS;
            String label = parts[0];
            if (label.equals("fin") || label.equals("reset")) {
                inbox.markClosed(label, at);
            } else if (label.startsWith("push:")) {
                inbox.addContent(MessageContent.newBuilder().setMessageId(Integer.parseInt(label.substring(5))).build(), at);
            } else {
                inbox.addContent(MessageContent.newBuilder().setMessageId(Integer.parseInt(label.substring(6))).setId(1).build(), at);
            }
        }
        return inbox.snapshot(0);
    }

    private static String auto(List<BattleFrame> tail) {
        return BattleOrder.autoTailProblem(tail, TURN, END, AUTO);
    }

    @Test
    void 挂机_翻转那一回合就打完_139_150_应答_FIN() {
        assertThat(auto(frames("push:139@0", "push:150@1", "reply:162@1", "fin@5"))).isNull();
    }

    @Test
    void 挂机_翻转后定时器每2秒一回合_150后紧跟FIN() {
        assertThat(auto(frames("push:139@0", "reply:162@1", "push:139@2000", "push:139@4000", "push:150@4000", "fin@4010"))).isNull();
        assertThat(auto(frames("push:139@0", "reply:162@1", "push:139@2000", "push:150@2000", "fin@2010"))).as("只有一条定时回合").isNull();
    }

    @Test
    void 挂机_违约都能指出() {
        assertThat(auto(frames("reply:162@0", "push:139@1", "push:150@1", "fin@2"))).contains("先于 162 的应答");
        assertThat(auto(frames("push:139@0", "push:150@1", "reply:162@1", "reset@5"))).contains("FIN");
        assertThat(auto(frames("push:139@0", "push:150@1", "fin@5"))).contains("应答应恰好一条");
        assertThat(auto(frames("push:139@0", "reply:162@1", "fin@5"))).contains("150 应恰好一条");
        assertThat(auto(frames("push:139@0", "reply:162@1", "push:139@1000", "push:150@1000", "fin@1001"))).contains("约 2 s");
        assertThat(auto(frames("push:139@0", "reply:162@1", "push:139@2000", "push:139@6000", "push:150@6000", "fin@6001")))
                .contains("4000 ms");
        assertThat(auto(frames("push:139@0", "reply:162@1", "push:150@1", "fin@2"))).as("应答之后没有定时回合就打完了").contains("至少还有一条");
        assertThat(auto(frames("push:139@0", "reply:162@1", "push:139@2000", "push:150@2000", "push:139@2001", "fin@2002")))
                .contains("150 之后应紧跟 FIN");
        assertThat(auto(frames("push:139@0", "push:150@0", "reply:162@0", "fin@1600"))).contains("1600 ms 才 FIN");
        assertThat(auto(frames())).contains("第一条应是");
    }

    @Test
    void 观众尾部_每回合一条158然后166然后FIN() {
        assertThat(BattleOrder.spectatorTailProblem(frames("push:158@0", "push:158@2000", "push:166@2000", "fin@2001"), 2,
                SPECTATE_TURN, SPECTATE_END)).isNull();
        assertThat(BattleOrder.spectatorTailProblem(frames("push:158@0", "push:166@2000", "fin@2001"), 2, SPECTATE_TURN, SPECTATE_END))
                .contains("观众应依次收到");
    }

    @Test
    void 短序列与计数() {
        List<BattleFrame> tail = frames("push:150@0", "fin@1");
        assertThat(BattleOrder.exactly(tail, List.of("push:150", BattleOrder.FIN))).isNull();
        assertThat(BattleOrder.exactly(tail, List.of(BattleOrder.FIN))).contains("实际 [push:150, closed:fin]");
        assertThat(BattleOrder.count(frames("push:139@0", "push:139@1", "push:150@2"), "push:139")).isEqualTo(2);
        assertThat(BattleSupport.onlyClosed(frames("fin@0"))).isTrue();
        assertThat(BattleSupport.onlyClosed(tail)).isFalse();
    }
}
