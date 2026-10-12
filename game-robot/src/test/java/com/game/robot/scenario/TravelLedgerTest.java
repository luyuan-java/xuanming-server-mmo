package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.MessageContent;
import com.game.robot.client.Received;
import com.game.robot.scenario.TravelLedger.Entry;
import com.game.robot.scenario.TravelLedger.Window;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 留底的窗口判据（{@link TravelLedger#problems}）：每个窗口恰好几条 124、窗口里的 79 都在 124 之后、124 之后要落地、窗口外的 124 另报。
 * 时间线手工拼：到达时刻是假的纳秒数，跨连接只按它比先后。
 */
class TravelLedgerTest {

    private static final int REDIRECT = 124;
    private static final int ENTER = 79;
    private static final int OTHER = 21;

    /** (连接, 连接内序号, 到达时刻, 消息号)。 */
    private static Entry entry(int connection, int index, long nanos, int messageId) {
        return new Entry(connection, "c" + connection, new Received(index, nanos, MessageContent.newBuilder().setMessageId(messageId).build()));
    }

    private static List<Entry> sorted(Entry... entries) {
        List<Entry> list = new ArrayList<>(List.of(entries));
        list.sort(TravelLedger.ORDER);
        return list;
    }

    @Test
    void 一跳的窗口_124在旧连接_79在新连接且更晚_通过() {
        List<Entry> timeline = sorted(entry(1, 0, 10, ENTER), entry(1, 5, 120, REDIRECT), entry(2, 0, 150, ENTER), entry(2, 1, 151, OTHER));
        List<Window> windows = List.of(Window.none("登录", 0, 100), Window.hop("去程", 100, 200), Window.none("之后", 200, Long.MAX_VALUE));

        assertThat(TravelLedger.problems(timeline, windows, REDIRECT, ENTER)).isEmpty();
        assertThat(TravelLedger.count(timeline, REDIRECT)).isEqualTo(1);
    }

    @Test
    void 窗口里79早于124_报出哪条连接第几条() {
        List<Entry> timeline = sorted(entry(1, 3, 110, ENTER), entry(1, 4, 120, REDIRECT), entry(2, 0, 150, ENTER));

        List<String> problems = TravelLedger.problems(timeline, List.of(Window.hop("去程", 100, 200)), REDIRECT, ENTER);

        assertThat(problems).singleElement().asString().contains("去程", "79 早于 124", "#1(c1)第 3 条", "#1(c1)第 4 条");
    }

    @Test
    void 同一时刻到达_按连接序号再按连接内序号定先后() {
        // 79 与 124 同一纳秒：79 在连接 1、124 在连接 2，按连接序号 79 算在前
        List<Entry> timeline = sorted(entry(2, 0, 120, REDIRECT), entry(1, 0, 120, ENTER), entry(3, 0, 130, ENTER));

        assertThat(TravelLedger.problems(timeline, List.of(Window.hop("去程", 100, 200)), REDIRECT, ENTER))
                .singleElement().asString().contains("79 早于 124");
    }

    @Test
    void 一个窗口两条124_或期望一条却没有_或期望没有却来了一条() {
        List<Entry> twice = sorted(entry(1, 0, 120, REDIRECT), entry(1, 1, 121, REDIRECT), entry(2, 0, 150, ENTER));
        assertThat(TravelLedger.problems(twice, List.of(Window.hop("去程", 100, 200)), REDIRECT, ENTER))
                .singleElement().asString().contains("有 2 条 124（期望恰好 1 条）", "#1(c1)第 0 条", "#1(c1)第 1 条");

        List<Entry> none = sorted(entry(1, 0, 120, ENTER));
        assertThat(TravelLedger.problems(none, List.of(Window.hop("去程", 100, 200)), REDIRECT, ENTER))
                .singleElement().asString().contains("有 0 条 124（期望恰好 1 条）");

        List<Entry> stray = sorted(entry(1, 0, 20, REDIRECT));
        assertThat(TravelLedger.problems(stray, List.of(Window.none("登录", 0, 100)), REDIRECT, ENTER))
                .singleElement().asString().contains("「登录」有 1 条 124（期望恰好 0 条）");
    }

    @Test
    void 跟随的窗口124之后没有79_判没落地_不跟随的窗口不要求() {
        List<Entry> timeline = sorted(entry(1, 0, 120, REDIRECT));

        assertThat(TravelLedger.problems(timeline, List.of(Window.hop("去程", 100, 200)), REDIRECT, ENTER))
                .singleElement().asString().contains("之后没有 79");
        assertThat(TravelLedger.problems(timeline, List.of(Window.unfollowed("去程（不跟随）", 100, 200)), REDIRECT, ENTER)).isEmpty();
    }

    @Test
    void 不在任何窗口里的124另报_窗口起点含终点不含() {
        List<Entry> timeline = sorted(entry(1, 0, 100, REDIRECT), entry(2, 0, 150, ENTER), entry(2, 1, 200, REDIRECT));

        List<String> problems = TravelLedger.problems(timeline, List.of(Window.hop("去程", 100, 200)), REDIRECT, ENTER);

        assertThat(problems).singleElement().asString().contains("不在任何窗口里", "#2(c2)第 1 条");
    }

    @Test
    void 窗口只期望0或1条() {
        assertThatThrownBy(() -> new Window("x", 0, 1, 2, true)).isInstanceOf(IllegalArgumentException.class);
    }
}
