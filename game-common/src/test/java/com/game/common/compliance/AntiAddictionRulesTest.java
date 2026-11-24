package com.game.common.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AntiAddictionRulesTest {

    @Test
    void pi编解码与周岁边界() {
        String prefix = AntiAddictionRules.piBirthPrefix(2010, 11, 23);
        String pi = prefix + "0123456789abcdef0123456789abcdef";
        assertThat(pi).hasSize(AntiAddictionRules.PI_LENGTH);
        assertThat(AntiAddictionRules.validPi(pi)).isTrue();
        LocalDate birth = AntiAddictionRules.birthDate(pi);
        assertThat(birth).isEqualTo(LocalDate.of(2010, 11, 23));

        Instant dayBeforeBirthday = LocalDateTime.of(2026, 11, 22, 23, 59, 59).toInstant(AntiAddictionRules.BEIJING);
        Instant onBirthday = LocalDateTime.of(2026, 11, 23, 0, 0, 0).toInstant(AntiAddictionRules.BEIJING);
        assertThat(AntiAddictionRules.ageAt(birth, dayBeforeBirthday)).isEqualTo(15);
        assertThat(AntiAddictionRules.ageAt(birth, onBirthday)).isEqualTo(16);

        assertThat(AntiAddictionRules.validPi("short")).isFalse();
        assertThat(AntiAddictionRules.validPi("ZZZZZZ" + "0".repeat(32))).isFalse();
    }

    @Test
    void 充值档位按法定年龄段划分() {
        assertThat(AntiAddictionRules.spendLimitAt(7)).isEqualTo(AntiAddictionRules.SpendLimit.DENIED);
        assertThat(AntiAddictionRules.spendLimitAt(8)).isEqualTo(new AntiAddictionRules.SpendLimit(false, 5000, 5000, 20000));
        assertThat(AntiAddictionRules.spendLimitAt(15)).isEqualTo(new AntiAddictionRules.SpendLimit(false, 5000, 5000, 20000));
        assertThat(AntiAddictionRules.spendLimitAt(16)).isEqualTo(new AntiAddictionRules.SpendLimit(false, 10000, 10000, 40000));
        assertThat(AntiAddictionRules.spendLimitAt(17)).isEqualTo(new AntiAddictionRules.SpendLimit(false, 10000, 10000, 40000));
        assertThat(AntiAddictionRules.spendLimitAt(18)).isEqualTo(AntiAddictionRules.SpendLimit.UNLIMITED);
    }

    @Test
    void 可玩日历覆盖周五到周日及节假日且仅20到21点放行() {
        var cal = AntiAddictionRules.Calendar.of(List.of("2026-10-01")); // 周四国庆
        Instant holiday2000 = LocalDateTime.of(2026, 10, 1, 20, 0, 0).toInstant(AntiAddictionRules.BEIJING);
        Instant holiday2100 = LocalDateTime.of(2026, 10, 1, 21, 0, 0).toInstant(AntiAddictionRules.BEIJING);
        Instant normalThu2000 = LocalDateTime.of(2026, 10, 8, 20, 0, 0).toInstant(AntiAddictionRules.BEIJING);
        Instant friday2030 = LocalDateTime.of(2026, 10, 9, 20, 30, 0).toInstant(AntiAddictionRules.BEIJING);

        assertThat(cal.playDeadline(holiday2000)).isEqualTo(holiday2100);
        assertThat(cal.playDeadline(holiday2100)).isNull();
        assertThat(cal.playDeadline(normalThu2000)).isNull();
        assertThat(cal.playDeadline(friday2030))
                .isEqualTo(LocalDateTime.of(2026, 10, 9, 21, 0, 0).toInstant(AntiAddictionRules.BEIJING));

        assertThatThrownBy(() -> AntiAddictionRules.Calendar.of(List.of("2026-2-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 身份证校验码与姓名预检() {
        Instant now = LocalDateTime.of(2026, 11, 23, 12, 0).toInstant(AntiAddictionRules.BEIJING);
        assertThat(AntiAddictionRules.validIdNumber(AntiAddictionRules.normalizeIdNumber(" 11010519491231002x "), now)).isTrue();
        assertThat(AntiAddictionRules.validIdNumber("110105194912310021", now)).isFalse();
        assertThat(AntiAddictionRules.validName("张三")).isTrue();
        assertThat(AntiAddictionRules.validName("   ")).isFalse();
        assertThat(AntiAddictionRules.validName("a\nb")).isFalse();
        assertThat(AntiAddictionRules.validName("名".repeat(33))).isFalse();
    }
}
