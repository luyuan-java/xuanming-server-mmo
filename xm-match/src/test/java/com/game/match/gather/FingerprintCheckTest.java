package com.game.match.gather;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.match.gather.FingerprintCheck.Member;
import com.game.match.gather.FingerprintCheck.Result;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 配表指纹比对（match-spec §3.2 第 7 步、§15.1）：一致才透传；warn 下不一致或部分为空照常开局、不透传；enforce 的肇事者是名单里第一个与多数派
 * 不一致的人；全员为空记第一位；off 不比对不透传。对照基线 {@code TestCheckTableFingerprintsPicksMinorityAsOffender} 与
 * {@code gather_fingerprint_test.go:122-237} 各模式的判定部分。
 */
class FingerprintCheckTest {

    /** 成员号从 100 起，按给的指纹顺序。 */
    private static List<Member> group(String... fingerprints) {
        List<Member> out = new ArrayList<>();
        for (int i = 0; i < fingerprints.length; i++) {
            out.add(new Member(100 + i, fingerprints[i]));
        }
        return out;
    }

    @Test
    void 全员非空且一致_两种模式都放行并透传这个指纹_不算不一致() {
        for (FingerprintMode mode : List.of(FingerprintMode.WARN, FingerprintMode.ENFORCE)) {
            Result result = FingerprintCheck.check(mode, group("A", "A", "A"));

            assertThat(result.proceed()).as(mode.name()).isTrue();
            assertThat(result.passThrough()).isEqualTo("A");
            assertThat(result.offender()).isZero();
            assertThat(result.mismatch()).isFalse();
            assertThat(result.suspect()).isZero();
        }
    }

    @Test
    void enforce_三人两同一异_异者是肇事者_不开局不透传() {
        Result result = FingerprintCheck.check(FingerprintMode.ENFORCE, group("A", "B", "A"));

        assertThat(result.proceed()).isFalse();
        assertThat(result.offender()).as("与多数派 A 不一致的第二人").isEqualTo(101);
        assertThat(result.passThrough()).isEmpty();
        assertThat(result.mismatch()).isTrue();
        assertThat(result.majority()).isEqualTo("A");
    }

    @Test
    void enforce_空指纹不能凑成多数派_不回报的人是肇事者() {
        Result result = FingerprintCheck.check(FingerprintMode.ENFORCE, group("", "B", "B"));

        assertThat(result.proceed()).isFalse();
        assertThat(result.offender()).isEqualTo(100);
        assertThat(result.majority()).isEqualTo("B");
        // 两个空对一个非空：空再多也不是多数派，肇事者是第一个空的
        Result twoEmpty = FingerprintCheck.check(FingerprintMode.ENFORCE, group("", "", "B"));
        assertThat(twoEmpty.majority()).isEqualTo("B");
        assertThat(twoEmpty.offender()).isEqualTo(100);
    }

    @Test
    void enforce_全员为空没有多数派_肇事者是名单第一位() {
        Result result = FingerprintCheck.check(FingerprintMode.ENFORCE, group("", ""));

        assertThat(result.proceed()).isFalse();
        assertThat(result.offender()).isEqualTo(100);
        assertThat(result.majority()).isEmpty();
        assertThat(result.mismatch()).isTrue();
    }

    @Test
    void enforce_两人平票_多数派是名单靠前的那位_肇事者是后一位() {
        Result result = FingerprintCheck.check(FingerprintMode.ENFORCE, group("A", "B"));

        assertThat(result.majority()).isEqualTo("A");
        assertThat(result.offender()).isEqualTo(101);
    }

    @Test
    void enforce_平票时多数派是先达到该票数的指纹_逐字照搬基线的计数方式() {
        // A, B, B, A：B 先到 2 票，之后 A 也到 2 票但没有严格超过 → 多数派保持 B，肇事者是第一个 A
        Result result = FingerprintCheck.check(FingerprintMode.ENFORCE, group("A", "B", "B", "A"));

        assertThat(result.majority()).isEqualTo("B");
        assertThat(result.offender()).isEqualTo(100);
    }

    @Test
    void warn_不一致照常开局_不透传任何一方的指纹_没有肇事者_但算一次不一致() {
        Result result = FingerprintCheck.check(FingerprintMode.WARN, group("A", "B"));

        assertThat(result.proceed()).isTrue();
        assertThat(result.passThrough()).as("不一致时不能透传任何一方的指纹").isEmpty();
        assertThat(result.offender()).isZero();
        assertThat(result.mismatch()).isTrue();
        assertThat(result.suspect()).as("只作排障线索").isEqualTo(101);
    }

    @Test
    void warn_部分为空同样算不一致_不透传() {
        Result result = FingerprintCheck.check(FingerprintMode.WARN, group("A", ""));

        assertThat(result.proceed()).isTrue();
        assertThat(result.passThrough()).isEmpty();
        assertThat(result.mismatch()).isTrue();
        assertThat(result.suspect()).isEqualTo(101);
    }

    @Test
    void off_不比对不透传不计数_一致也不透传_不一致也放行() {
        Result same = FingerprintCheck.check(FingerprintMode.OFF, group("A", "A"));
        Result different = FingerprintCheck.check(FingerprintMode.OFF, group("A", "B"));

        for (Result result : List.of(same, different)) {
            assertThat(result.proceed()).isTrue();
            assertThat(result.passThrough()).isEmpty();
            assertThat(result.mismatch()).isFalse();
            assertThat(result.offender()).isZero();
        }
    }

    @Test
    void 单人_有指纹就透传_没指纹在enforce下他自己是肇事者() {
        assertThat(FingerprintCheck.check(FingerprintMode.ENFORCE, group("A")).passThrough()).isEqualTo("A");

        Result empty = FingerprintCheck.check(FingerprintMode.ENFORCE, group(""));
        assertThat(empty.proceed()).isFalse();
        assertThat(empty.offender()).isEqualTo(100);
        assertThat(FingerprintCheck.check(FingerprintMode.WARN, group("")).proceed()).isTrue();
    }

    @Test
    void 名单为空_直接放行_null指纹按空串() {
        assertThat(FingerprintCheck.check(FingerprintMode.ENFORCE, List.of()).proceed()).isTrue();
        assertThat(new Member(1, null).fingerprint()).isEmpty();
    }
}
