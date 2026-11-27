package com.game.common.killswitch;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.killswitch.KillSwitch.Rule;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class KillSwitchTest {

    private static final Rule DENY = new Rule(true, "", 0);
    private static final Rule ALLOW = new Rule(false, "", 0);

    @Test
    void 规则值解析同基线() {
        assertThat(KillSwitch.parseRule("")).contains(ALLOW);
        assertThat(KillSwitch.parseRule("  \n\t ")).contains(ALLOW);
        assertThat(KillSwitch.parseRule("{\"deny\":true}")).contains(DENY);
        assertThat(KillSwitch.parseRule("{\"deny\":true,\"reason\":\"db 过载临时降级\",\"code\":9}"))
                .contains(new Rule(true, "db 过载临时降级", 9));
        assertThat(KillSwitch.parseRule("{\"deny\":false}")).contains(ALLOW);
        assertThat(KillSwitch.parseRule("{}")).as("字段拼错 / 漏写都不该误关停").contains(ALLOW);
        assertThat(KillSwitch.parseRule("{\"denied\":true}")).contains(ALLOW);
        for (String on : List.of("true", "1", "ON", "deny", "yes", "  true\n")) {
            assertThat(KillSwitch.parseRule(on)).as(on).contains(DENY);
        }
        for (String off : List.of("false", "0", "off", "no", "allow")) {
            assertThat(KillSwitch.parseRule(off)).as(off).contains(ALLOW);
        }
        assertThat(KillSwitch.parseRule("{\"deny\":tru")).as("坏 JSON 整条丢弃").isEmpty();
        assertThat(KillSwitch.parseRule("关掉它")).isEmpty();
        assertThat(KillSwitch.parseRule("{\"deny\":\"yes\"}")).as("类型不对算写坏").isEmpty();
        assertThat(KillSwitch.parseRule("{\"code\":-1}")).isEmpty();
        assertThat(KillSwitch.parseRule("[true]")).isEmpty();
        for (String trailing : List.of("{\"deny\":true} garbage", "{\"deny\":true}}", "{\"deny\":true}]",
                "{\"deny\":true}\n{\"deny\":true}", "{\"deny\":true}{\"deny\":false}")) {
            assertThat(KillSwitch.parseRule(trailing)).as("顶层值后面还有内容算写坏：" + trailing).isEmpty();
        }
        assertThat(KillSwitch.parseRule("{\"deny\":true}  \n")).as("尾随空白不算").contains(DENY);
    }

    @Test
    void 规则键规范形_去首尾空白与开头斜杠() {
        assertThat(KillSwitch.normalizePattern(" //friendpb.ClientPlayerFriend/AddFriend \n"))
                .isEqualTo("friendpb.ClientPlayerFriend/AddFriend");
        assertThat(KillSwitch.normalizePattern("ClientPlayerChat/*")).isEqualTo("ClientPlayerChat/*");
        assertThat(KillSwitch.normalizePattern("///")).isEmpty();
        assertThat(KillSwitch.normalizePattern(null)).isEmpty();
    }

    @Test
    void 匹配键顺序_精确全名_精确短名_服务通配全名_服务通配短名_全局() {
        assertThat(KillSwitch.matchKeys("/login.LoginService/Login")).containsExactly(
                "login.LoginService/Login", "LoginService/Login", "login.LoginService/*", "LoginService/*", "*");
        assertThat(KillSwitch.matchKeys("/LoginService/Login")).containsExactly("LoginService/Login", "LoginService/*", "*");
        assertThat(KillSwitch.matchKeys("/a.b.C/M")).containsExactly("a.b.C/M", "C/M", "a.b.C/*", "C/*", "*");
        assertThat(KillSwitch.matchKeys("")).containsExactly("*");
        assertThat(KillSwitch.matchKeys("/weird")).containsExactly("weird", "*");
        assertThat(KillSwitch.matchKeys("/svc/")).containsExactly("svc/", "*");
    }

    @Test
    void 命中规则即关停_精确规则可豁免_没有规则放行() {
        KillSwitch ks = new KillSwitch(-1, System::nanoTime);
        assertThat(ks.blocked("/friendpb.ClientPlayerFriend/AddFriend")).isEmpty();
        ks.setRules(Map.of("ClientPlayerFriend/*", new Rule(true, "止血", 0)));
        assertThat(ks.blocked("/friendpb.ClientPlayerFriend/AddFriend")).contains(new Rule(true, "止血", 0));
        assertThat(ks.blocked("/chatpb.ClientPlayerChat/SendChat")).isEmpty();
        ks.setRules(Map.of("*", DENY, "friendpb.ClientPlayerFriend/AddFriend", ALLOW));
        assertThat(ks.blocked("/friendpb.ClientPlayerFriend/AddFriend")).isEmpty();
        assertThat(ks.blocked("/friendpb.ClientPlayerFriend/Block")).isPresent();
        assertThat(ks.ruleCount()).isEqualTo(2);
    }

    @Test
    void 规则源失联超过作废时长_快照作废_整体放行() {
        AtomicLong now = new AtomicLong(1_000);
        KillSwitch ks = new KillSwitch(100, now::get);
        ks.setRules(Map.of("*", DENY));
        assertThat(ks.blocked("/a.S/M")).isPresent();
        now.addAndGet(100);
        assertThat(ks.blocked("/a.S/M")).isPresent();
        now.addAndGet(1);
        assertThat(ks.blocked("/a.S/M")).isEmpty();
        assertThat(ks.ruleCount()).isZero();
        ks.setRules(Map.of("*", DENY)); // 同步恢复
        assertThat(ks.blocked("/a.S/M")).isPresent();
    }

    @Test
    void 全局实例与短路回调() {
        KillSwitch ks = new KillSwitch(-1, System::nanoTime);
        KillSwitch.installGlobal(ks);
        try {
            assertThat(KillSwitch.global()).contains(ks);
            StringBuilder seen = new StringBuilder();
            ks.onBlocked(seen::append);
            ks.recordBlocked("S/M");
            assertThat(seen).hasToString("S/M");
            ks.onBlocked(m -> {
                throw new IllegalStateException("指标坏了");
            });
            ks.recordBlocked("S/M"); // 不抛
        } finally {
            KillSwitch.installGlobal(null);
        }
        assertThat(KillSwitch.global()).isEqualTo(Optional.empty());
    }

    @Test
    void 节点范围优先级_同层相反规则取关停_受保护方法只认精确_只计本进程适用规则() {
        KillSwitch ks = new KillSwitch(-1, System::nanoTime);
        ks.addNodeType("SceneNodeService");
        ks.addNodeType("MatchNodeService");
        ks.addInstance("11111111-1111-1111-1111-111111111111");
        ks.protectMethods(java.util.List.of("/com.game.api.SceneControlService/leaveScene"));
        ks.registerFeature("trade", java.util.List.of("/trade.ClientPlayerJubaozhai/BuyConsignment"));

        // 1. 范围优先级：instance > type > all；精确豁免生效
        ks.setRules(Map.of(
                "*", DENY,
                "type/SceneNodeService/*", ALLOW,
                "instance/11111111-1111-1111-1111-111111111111/scene.Svc/Move", DENY,
                "type/OtherNodeService/*", DENY,
                "instance/22222222-2222-2222-2222-222222222222/*", DENY,
                "type/", DENY,
                "instance/bad", DENY));
        assertThat(ks.blocked("/scene.Svc/Move")).contains(DENY);
        assertThat(ks.blocked("/scene.Svc/Cast")).isEmpty();
        // 只计适用于本进程的 3 条规则（*、type/SceneNodeService/*、instance/1111...），不计其他节点与残缺键
        assertThat(ks.ruleCount()).isEqualTo(3);

        // 2. 同层多身份相反规则取关停
        ks.setRules(Map.of(
                "type/SceneNodeService/pkg.Svc/Act", ALLOW,
                "type/MatchNodeService/pkg.Svc/Act", DENY));
        assertThat(ks.blocked("/pkg.Svc/Act")).contains(DENY);

        // 3. 受保护方法：* 与 Service/* 均关不掉，只有精确规则能关
        ks.setRules(Map.of(
                "*", DENY,
                "SceneControlService/*", DENY,
                "instance/11111111-1111-1111-1111-111111111111/*", DENY));
        assertThat(ks.blocked("/com.game.api.SceneControlService/leaveScene")).isEmpty();
        ks.setRules(Map.of(
                "instance/11111111-1111-1111-1111-111111111111/SceneControlService/leaveScene", DENY));
        assertThat(ks.blocked("/com.game.api.SceneControlService/leaveScene")).contains(DENY);

        // 4. feature/<name> 位于 Service/* 之后、* 之前
        ks.setRules(Map.of("feature/trade", DENY));
        assertThat(ks.blocked("/trade.ClientPlayerJubaozhai/BuyConsignment")).contains(DENY);
        assertThat(ks.blocked("/trade.ClientPlayerJubaozhai/BrowseListings")).isEmpty();
    }
}
