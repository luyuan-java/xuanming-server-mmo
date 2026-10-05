package com.game.trade.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.table.CommonErrorTip;
import com.game.table.TradeErrorTip;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * tip 码护栏（基线 constants_test.go:24-99：段、唯一、fault 定性、不手写码值）与每个发生点的码（trade-spec §0.5、§9.1 TradeTipsTest）。
 * 另钉住 trade 的形状纪律：<b>任何 tip 都不带 parameters</b>（§0.3）。
 */
class TradeTipsTest {

    /** 段声明之外的 int 常量（不是 tip 码）。 */
    private static final Set<String> SEGMENT_CONSTANTS = Set.of("SEGMENT_BASE", "SEGMENT_WIDTH");
    /** 取自通用段的码。 */
    private static final Set<String> COMMON_CODES = Set.of("INVALID_PARAMETER", "SERVICE_UNAVAILABLE", "FEATURE_UNAVAILABLE",
            "MESSAGE_ID_NOT_FOUND");

    /** trade 段的全部码（基线 tipCodes 的 trade 那一半）。 */
    private static Map<String, Integer> tradeCodes() {
        Map<String, Integer> codes = new LinkedHashMap<>();
        codes.put("LISTING_NOT_FOUND", TradeTips.LISTING_NOT_FOUND);
        codes.put("HOME_ZONE_UNKNOWN", TradeTips.HOME_ZONE_UNKNOWN);
        codes.put("FAVORITE_LIMIT_REACHED", TradeTips.FAVORITE_LIMIT_REACHED);
        codes.put("FEATURE_DISABLED", TradeTips.FEATURE_DISABLED);
        return codes;
    }

    @Test
    void trade码都在段内且覆盖生成枚举的全部值() {
        Set<Integer> covered = new HashSet<>();
        tradeCodes().forEach((name, code) -> {
            assertThat(code).as("%s 落在 trade 段 [20000,21000) 之外", name).isBetween(20000, 20999);
            assertThat(TradeTips.isTradeCode(code)).as(name).isTrue();
            assertThat(TradeErrorTip.trade_error.forNumber(code)).as("%s = %d 未被生成枚举识别", name, code).isNotNull();
            covered.add(code);
        });
        for (TradeErrorTip.trade_error e : TradeErrorTip.trade_error.values()) {
            if (e != TradeErrorTip.trade_error.UNRECOGNIZED && e.getNumber() != 0) {
                assertThat(covered).as("生成枚举 %s 没有对应的 TradeTips 常量", e).contains(e.getNumber());
            }
        }
        assertThat(TradeTips.isTradeCode(19999)).isFalse();
        assertThat(TradeTips.isTradeCode(20000)).isTrue();
        assertThat(TradeTips.isTradeCode(20999)).isTrue();
        assertThat(TradeTips.isTradeCode(21000)).isFalse();
    }

    /** 码值逐个对拍 spec §0.5 的表（与 mmorpg Tip.xlsx 同源；改码要先改 mmorpg 再同步契约）。 */
    @Test
    void 码值与spec的码表一致() {
        assertThat(TradeTips.LISTING_NOT_FOUND).isEqualTo(20000);
        assertThat(TradeTips.HOME_ZONE_UNKNOWN).isEqualTo(20001);
        assertThat(TradeTips.FAVORITE_LIMIT_REACHED).isEqualTo(20002);
        assertThat(TradeTips.FEATURE_DISABLED).isEqualTo(20003);
        assertThat(TradeTips.SERVICE_UNAVAILABLE).isEqualTo(1003);
        assertThat(TradeTips.INVALID_PARAMETER).isEqualTo(1005);
        assertThat(TradeTips.FEATURE_UNAVAILABLE).isEqualTo(CommonErrorTip.common_error.kFeatureUnavailable.getNumber());
        assertThat(TradeTips.MESSAGE_ID_NOT_FOUND).isEqualTo(CommonErrorTip.common_error.kMessageIdNotFound.getNumber());
    }

    @Test
    void 码互不相同_且通用码不在trade段() {
        Map<Integer, String> seen = new LinkedHashMap<>();
        Map<String, Integer> all = new LinkedHashMap<>(tradeCodes());
        all.put("INVALID_PARAMETER", TradeTips.INVALID_PARAMETER);
        all.put("SERVICE_UNAVAILABLE", TradeTips.SERVICE_UNAVAILABLE);
        all.put("FEATURE_UNAVAILABLE", TradeTips.FEATURE_UNAVAILABLE);
        all.put("MESSAGE_ID_NOT_FOUND", TradeTips.MESSAGE_ID_NOT_FOUND);
        all.forEach((name, code) -> {
            String previous = seen.put(code, name);
            assertThat(previous).as("tip 码 %d 被 %s 与 %s 同时使用", code, previous, name).isNull();
        });
        for (String common : COMMON_CODES) {
            assertThat(TradeTips.isTradeCode(all.get(common))).as(common).isFalse();
        }
    }

    /** 基线 fault 列只有 kServiceUnavailable（constants_test.go:24-33、TestTipClassifier）；Java 写死 FAULTS = {1003}。 */
    @Test
    void 只有1003是故障() {
        assertThat(TradeTips.FAULTS).containsExactly(1003);
        assertThat(TradeTips.isFault(TradeTips.SERVICE_UNAVAILABLE)).isTrue();
        assertThat(TradeTips.isFault(TradeTips.INVALID_PARAMETER)).isFalse();
        tradeCodes().forEach((name, code) -> assertThat(TradeTips.isFault(code)).as(name).isFalse());
        assertThat(TradeTips.isFault(0)).isFalse();
    }

    /** 每个码都必须直接写成生成枚举的 {@code _VALUE} 引用，不能手写数字、别名或引用其他码轴（同基线 TestNoHandWrittenTipCodes）。 */
    @Test
    void 不手写码值_常量与护栏表一一对应() throws IOException {
        Path source = Path.of("src/main/java/com/game/trade/rules/TradeTips.java");
        assertThat(source).exists();
        String text = Files.readString(source, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("static\\s+final\\s+int\\s+(\\w+)\\s*=\\s*([^;]+);").matcher(text);
        Pattern tradeRef = Pattern.compile("TradeErrorTip\\.trade_error\\.kTrade\\w+_VALUE");
        Pattern commonRef = Pattern.compile("CommonErrorTip\\.common_error\\.k\\w+_VALUE");
        Set<String> seen = new HashSet<>();
        while (m.find()) {
            String name = m.group(1);
            if (SEGMENT_CONSTANTS.contains(name)) {
                continue;
            }
            seen.add(name);
            Pattern want = COMMON_CODES.contains(name) ? commonRef : tradeRef;
            assertThat(want.matcher(m.group(2).trim()).matches())
                    .as("%s 必须直接写成生成枚举的 _VALUE 引用，实际是 %s", name, m.group(2).trim())
                    .isTrue();
        }
        Set<String> expected = new HashSet<>(tradeCodes().keySet());
        expected.addAll(COMMON_CODES);
        assertThat(seen).as("TradeTips 的码常量与护栏表必须一一对应（新增码要同步纳入 tradeCodes）")
                .containsExactlyInAnyOrderElementsOf(expected);

        Set<String> declared = new HashSet<>();
        for (Field f : TradeTips.class.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (f.getType() == int.class && Modifier.isStatic(mod) && Modifier.isFinal(mod)
                    && !SEGMENT_CONSTANTS.contains(f.getName())) {
                declared.add(f.getName());
            }
        }
        assertThat(declared).containsExactlyInAnyOrderElementsOf(expected);
    }

    /** 护栏本身不是摆设：手写数字、别的码轴、别名都必须被拒（基线 TestIsGeneratedTipRefRejectsOtherShapes）。 */
    @Test
    void 护栏拒绝其他写法() {
        Pattern tradeRef = Pattern.compile("TradeErrorTip\\.trade_error\\.kTrade\\w+_VALUE");
        assertThat(tradeRef.matcher("TradeErrorTip.trade_error.kTradeListingNotFound_VALUE").matches()).isTrue();
        for (String bad : new String[] {"20001", "GuildErrorTip.guild_error.kGuildNotFound_VALUE",
                "TradeErrorTip.trade_error.kTradeListingNotFound.getNumber()", "LISTING_NOT_FOUND", "(int) 20000L"}) {
            assertThat(tradeRef.matcher(bad).matches()).as(bad).isFalse();
        }
    }

    // ================================================================ 发生点

    /** 每个发生点的码（§0.5、§3）。新增 TradeTip 必须在这里登记。 */
    @Test
    void 每个发生点的码() {
        Map<TradeTip, Integer> want = new EnumMap<>(TradeTip.class);
        want.put(TradeTip.NO_SESSION, 1005);
        want.put(TradeTip.INVALID_BROWSE_REQUEST, 1005);
        want.put(TradeTip.LISTING_ID_ZERO, 1005);
        want.put(TradeTip.INVALID_SEED_REQUEST, 1005);
        want.put(TradeTip.LISTING_NOT_FOUND, 20000);
        want.put(TradeTip.LISTING_NOT_VISIBLE, 20000);
        want.put(TradeTip.HOME_ZONE_UNKNOWN, 20001);
        want.put(TradeTip.SELLER_HOME_ZONE_UNKNOWN, 20001);
        want.put(TradeTip.FAVORITE_LIMIT_REACHED, 20002);
        want.put(TradeTip.AUCTION_DISABLED, 20003);
        want.put(TradeTip.STORE_FAULT, 1003);
        want.put(TradeTip.HOME_ZONE_FAULT, 1003);
        want.put(TradeTip.SCOPE_UNSPECIFIED, 1003);
        want.put(TradeTip.LISTING_ID_UNAVAILABLE, 1003);
        want.put(TradeTip.OVERLOADED, 1003);
        want.put(TradeTip.INTERNAL_ERROR, 1003);
        assertThat(want.keySet()).as("每个 TradeTip 都要登记").containsExactlyInAnyOrder(TradeTip.values());
        want.forEach((tip, code) -> {
            assertThat(tip.code()).as(tip.name()).isEqualTo(code);
            assertThat(tip.fault()).as(tip.name()).isEqualTo(code == 1003);
            assertThat(tip.reason()).as(tip.name()).isNotBlank();
        });
    }

    /** §0.3 / §5.9 第 6 条：tip 永不带 parameters（friend 塞英文原因串、guild 带英文原因，trade 两样都不照抄）。 */
    @Test
    void tip永不带parameters() {
        for (TradeTip tip : TradeTip.values()) {
            assertThat(tip.proto().getId()).as(tip.name()).isEqualTo(tip.code());
            assertThat(tip.proto().getParametersCount()).as("%s 的 TipInfoMessage 不许带 parameters", tip.name()).isZero();
            assertThat(tip.proto().getParametersList()).doesNotContain(tip.reason());
        }
    }
}
