package com.game.login.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DevPasswordRuleTest {

    private static final String SECRET = "unit-test-shared-secret";
    private final DevPasswordRule rule = new DevPasswordRule(SECRET, List.of("robot_", "dev_"));

    @Test
    void 白名单前缀加正确口令通过() {
        assertThat(rule.accepts("robot_0001", SECRET)).isTrue();
        assertThat(rule.accepts("dev_alice", SECRET)).isTrue();
        // 只做前缀匹配：账号恰好等于前缀也算（与 Go strings.HasPrefix 一致）。
        assertThat(rule.accepts("robot_", SECRET)).isTrue();
    }

    @Test
    void 口令不对拒绝() {
        assertThat(rule.accepts("robot_0001", "wrong")).isFalse();
        assertThat(rule.accepts("robot_0001", "")).isFalse();
        assertThat(rule.accepts("robot_0001", null)).isFalse();
        assertThat(rule.accepts("robot_0001", SECRET + "x")).isFalse();
    }

    @Test
    void 前缀不在白名单拒绝() {
        assertThat(rule.accepts("player_0001", SECRET)).isFalse();
        // 前缀区分大小写。
        assertThat(rule.accepts("ROBOT_0001", SECRET)).isFalse();
    }

    @Test
    void 账号为空或首尾有空白拒绝() {
        assertThat(rule.accepts("", SECRET)).isFalse();
        assertThat(rule.accepts(null, SECRET)).isFalse();
        assertThat(rule.accepts(" robot_0001", SECRET)).isFalse();
        assertThat(rule.accepts("robot_0001 ", SECRET)).isFalse();
        assertThat(rule.accepts("robot_0001　", SECRET)).isFalse();
        assertThat(rule.accepts("robot_0001\u0085", SECRET)).isFalse();
    }

    @Test
    void 账号超过64个码点拒绝() {
        String max = "robot_" + "a".repeat(DevPasswordRule.MAX_ACCOUNT_CHARS - "robot_".length());
        assertThat(rule.accepts(max, SECRET)).isTrue();
        assertThat(rule.accepts(max + "a", SECRET)).isFalse();
    }

    @Test
    void 构造时拒绝空密钥与空前缀() {
        assertThatThrownBy(() -> new DevPasswordRule("", List.of("robot_"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DevPasswordRule(null, List.of("robot_"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DevPasswordRule(SECRET, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DevPasswordRule(SECRET, List.of("robot_", "  "))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 前缀两端空白在构造时去掉() {
        DevPasswordRule trimmed = new DevPasswordRule(SECRET, List.of(" robot_ "));
        assertThat(trimmed.accepts("robot_1", SECRET)).isTrue();
    }
}
