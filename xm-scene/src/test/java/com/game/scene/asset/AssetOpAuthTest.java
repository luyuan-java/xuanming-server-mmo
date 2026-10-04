package com.game.scene.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.AssetAuth;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetItem;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetStream;
import com.game.scene.asset.AssetOpAuth.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** 验签，用例对应 mmorpg asset_op_auth_test.cpp；规范串 golden 与基线逐字节相同（同一组输入）。 */
class AssetOpAuthTest {

    /** 夹具密钥（33 字节，只在测试里存在）。 */
    static final String SECRET = "asset-op-test-secret-0123456789ab";
    private static final String TOO_SHORT = "asset-op-test-secret-0123456789";
    private static final String OTHER_SECRET = "asset-op-other-secret-0123456789ab";
    private static final long NOW = 1_700_000_000_123L;

    private static AssetOpRequest.Builder golden() {
        return AssetOpRequest.newBuilder()
                .setPlayerId(42)
                .setStream(AssetStream.ASSET_STREAM_GUILD_DEBIT)
                .setSeq(7)
                .setCorrelationId(99)
                .setTxType(24)
                .setStreamEpoch(1_700_000_000_000L)
                .setBundle(AssetBundle.newBuilder().addCurrencies(AssetCurrency.newBuilder().setCurrencyType(1)
                        .setAmount(30)))
                .setAuth(AssetAuth.newBuilder().setCaller("guild").setTimestampMs(NOW));
    }

    /** 按 rpc 签好（签名必须在所有参与规范串的字段都填好之后算）。 */
    static AssetOpRequest signed(String rpc, AssetOpRequest.Builder request, String secret) {
        AssetOpRequest unsigned = request.build();
        return request.setAuth(request.getAuth().toBuilder()
                .setSignatureHex(AssetOpAuth.sign(secret, AssetOpAuth.canonical(rpc, unsigned)))).build();
    }

    private static Verdict verify(String rpc, AssetOpRequest request, Function<String, String> lookup) {
        return new AssetOpAuth(lookup).verify(rpc, request, NOW);
    }

    private static Verdict verify(AssetOpRequest request) {
        return verify("debit", request, caller -> SECRET);
    }

    @Test
    void 规范串golden_与基线逐字节相同() {
        assertThat(AssetOpAuth.canonical("debit", golden().build())).isEqualTo(
                "mmorpg-asset-op/v1\nguild\ndebit\n42\n1\n1700000000000\n7\n99\n24\nc=1:30;i=;u=;p=0\n1700000000123");
    }

    @Test
    void 空包的写法() {
        AssetOpRequest request = AssetOpRequest.newBuilder().setPlayerId(7).setStream(AssetStream.ASSET_STREAM_GUILD_DEBIT)
                .setSeq(1).setStreamEpoch(2).setAuth(AssetAuth.newBuilder().setCaller("guild").setTimestampMs(5)).build();
        assertThat(AssetOpAuth.canonical("abort_debit", request))
                .isEqualTo("mmorpg-asset-op/v1\nguild\nabort_debit\n7\n1\n2\n1\n0\n0\nc=;i=;u=;p=0\n5");
    }

    @Test
    void 多条货币物品按请求顺序拼_不排序() {
        AssetOpRequest request = AssetOpRequest.newBuilder().setPlayerId(1).setStream(AssetStream.ASSET_STREAM_TRADE_CREDIT)
                .setSeq(2).setStreamEpoch(3)
                .setBundle(AssetBundle.newBuilder()
                        .addCurrencies(AssetCurrency.newBuilder().setCurrencyType(9).setAmount(5))
                        .addCurrencies(AssetCurrency.newBuilder().setCurrencyType(2).setAmount(7))
                        .addItems(AssetItem.newBuilder().setConfigId(300).setCount(2))
                        .addItems(AssetItem.newBuilder().setConfigId(100).setCount(1)))
                .setAuth(AssetAuth.newBuilder().setCaller("trade").setTimestampMs(11)).build();
        assertThat(AssetOpAuth.canonical("credit", request))
                .isEqualTo("mmorpg-asset-op/v1\ntrade\ncredit\n1\n4\n3\n2\n0\n0\nc=9:5,2:7;i=300:2,100:1;u=;p=0\n11");
    }

    @Test
    void 实例号与宝宝号进规范串_无符号字段按无符号写() {
        AssetOpRequest request = AssetOpRequest.newBuilder().setPlayerId(-1L).setStream(AssetStream.ASSET_STREAM_TRADE_DEBIT)
                .setSeq(-1L).setStreamEpoch(-1L).setCorrelationId(-1L).setTxType(-1)
                .setBundle(AssetBundle.newBuilder().addItemUuids(5).addItemUuids(-1L).setPetId(-1L)
                        .addCurrencies(AssetCurrency.newBuilder().setCurrencyType(-1).setAmount(-1L))
                        .addItems(AssetItem.newBuilder().setConfigId(-1).setCount(-1)))
                .setAuth(AssetAuth.newBuilder().setCaller("trade").setTimestampMs(-1L)).build();
        String max64 = "18446744073709551615";
        String max32 = "4294967295";
        assertThat(AssetOpAuth.canonical("debit", request)).isEqualTo("mmorpg-asset-op/v1\ntrade\ndebit\n" + max64 + "\n3\n"
                + max64 + "\n" + max64 + "\n" + max64 + "\n" + max32 + "\nc=" + max32 + ":" + max64 + ";i=" + max32 + ":"
                + max32 + ";u=5," + max64 + ";p=" + max64 + "\n" + max64);
    }

    @Test
    void 未知流按有符号十进制写() {
        AssetOpRequest request = golden().setStreamValue(-3).build();
        assertThat(AssetOpAuth.canonical("debit", request)).contains("\n42\n-3\n");
    }

    @Test
    void HMAC是标准的HmacSHA256小写十六进制() {
        // RFC 4231 测试用例 2
        assertThat(AssetOpAuth.sign("Jefe", "what do ya want for nothing?"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void 签好的请求通过_每条自有流都通过() {
        assertThat(verify(signed("debit", golden(), SECRET))).isEqualTo(Verdict.OK);
        assertThat(verify("abort_debit", signed("abort_debit", golden(), SECRET), c -> SECRET)).isEqualTo(Verdict.OK);
        record Case(AssetStream stream, String caller, String rpc) {
        }
        for (Case c : List.of(new Case(AssetStream.ASSET_STREAM_GUILD_DEBIT, "guild", "debit"),
                new Case(AssetStream.ASSET_STREAM_GUILD_CREDIT, "guild", "credit"),
                new Case(AssetStream.ASSET_STREAM_TRADE_DEBIT, "trade", "debit"),
                new Case(AssetStream.ASSET_STREAM_TRADE_CREDIT, "trade", "credit"))) {
            AssetOpRequest.Builder b = golden().setStream(c.stream());
            b.getAuthBuilder().setCaller(c.caller());
            assertThat(verify(c.rpc(), signed(c.rpc(), b, SECRET), x -> SECRET)).as(c.toString()).isEqualTo(Verdict.OK);
        }
    }

    @Test
    void 改任何一个签进去的字段都验不过() {
        AssetOpRequest ok = signed("debit", golden(), SECRET);
        List<AssetOpRequest> tampered = List.of(
                ok.toBuilder().setPlayerId(43).build(),
                ok.toBuilder().setSeq(8).build(),
                ok.toBuilder().setStreamEpoch(1).build(),
                ok.toBuilder().setCorrelationId(98).build(),
                ok.toBuilder().setTxType(25).build(),
                ok.toBuilder().setBundle(ok.getBundle().toBuilder().setCurrencies(0,
                        AssetCurrency.newBuilder().setCurrencyType(1).setAmount(31))).build(),
                ok.toBuilder().setBundle(ok.getBundle().toBuilder().addItemUuids(1)).build(),
                ok.toBuilder().setBundle(ok.getBundle().toBuilder().setPetId(1)).build(),
                ok.toBuilder().setAuth(ok.getAuth().toBuilder().setTimestampMs(NOW + 1)).build());
        for (AssetOpRequest r : tampered) {
            assertThat(verify(r)).isEqualTo(Verdict.SIGNATURE_MISMATCH);
        }
        assertThat(verify("credit", ok, c -> SECRET)).as("rpc 进串：拿扣款签名调发放不行").isEqualTo(Verdict.SIGNATURE_MISMATCH);
        assertThat(verify(ok.toBuilder().setAuth(ok.getAuth().toBuilder().setSignatureHex(
                ok.getAuth().getSignatureHex().toUpperCase())).build())).as("大写十六进制不认").isEqualTo(Verdict.SIGNATURE_MISMATCH);
        assertThat(verify(ok.toBuilder().setAuth(ok.getAuth().toBuilder().setSignatureHex("")).build()))
                .isEqualTo(Verdict.SIGNATURE_MISMATCH);
    }

    @Test
    void 密钥先去首尾空白再当HMAC_key() {
        assertThat(verify("debit", signed("debit", golden(), SECRET), c -> "  \t" + SECRET + "\n "))
                .isEqualTo(Verdict.OK);
    }

    @Test
    void 调用方白名单_SYSTEM_CREDIT一律拒() {
        for (String caller : List.of("", "trade", "Guild", " guild", "guild ", "gm")) {
            AssetOpRequest.Builder b = golden();
            b.getAuthBuilder().setCaller(caller);
            assertThat(verify(signed("debit", b, SECRET))).as("caller='%s'", caller).isEqualTo(Verdict.CALLER_NOT_ALLOWED);
        }
        for (String caller : List.of("guild", "trade", "system", "gm")) {
            AssetOpRequest.Builder b = golden().setStream(AssetStream.ASSET_STREAM_SYSTEM_CREDIT);
            b.getAuthBuilder().setCaller(caller);
            assertThat(verify("credit", signed("credit", b, SECRET), c -> SECRET)).isEqualTo(Verdict.CALLER_NOT_ALLOWED);
        }
        assertThat(verify(signed("debit", golden().setStream(AssetStream.ASSET_STREAM_UNSPECIFIED), SECRET)))
                .isEqualTo(Verdict.CALLER_NOT_ALLOWED);
        assertThat(verify(signed("debit", golden().setStreamValue(77), SECRET))).isEqualTo(Verdict.CALLER_NOT_ALLOWED);
    }

    @Test
    void 不可信的caller不会被拿去查密钥() {
        List<String> asked = new ArrayList<>();
        AssetOpRequest.Builder bad = golden();
        bad.getAuthBuilder().setCaller("../../etc");
        assertThat(verify("debit", signed("debit", bad, SECRET), c -> {
            asked.add(c);
            return SECRET;
        })).isEqualTo(Verdict.CALLER_NOT_ALLOWED);
        assertThat(asked).isEmpty();
        assertThat(verify("debit", signed("debit", golden(), SECRET), c -> {
            asked.add(c);
            return SECRET;
        })).isEqualTo(Verdict.OK);
        assertThat(asked).containsExactly("guild");
    }

    @Test
    void 密钥缺失_过短_全空白都视同未配置() {
        AssetOpRequest ok = signed("debit", golden(), SECRET);
        assertThat(verify("debit", ok, c -> null)).isEqualTo(Verdict.SECRET_MISSING);
        assertThat(verify("debit", ok, c -> "")).isEqualTo(Verdict.SECRET_MISSING);
        assertThat(verify("debit", ok, c -> TOO_SHORT)).isEqualTo(Verdict.SECRET_MISSING);
        assertThat(verify("debit", ok, c -> " ".repeat(40))).isEqualTo(Verdict.SECRET_MISSING);
        assertThat(TOO_SHORT.length()).isEqualTo(AssetOpAuth.MIN_SECRET_BYTES - 1);
    }

    @Test
    void 每个调用方一把密钥_互不通用() {
        Function<String, String> perCaller = c -> c.equals("guild") ? SECRET : OTHER_SECRET;
        AssetOpRequest.Builder trade = golden().setStream(AssetStream.ASSET_STREAM_TRADE_DEBIT).setTxType(3);
        trade.getAuthBuilder().setCaller("trade");
        assertThat(verify("debit", signed("debit", trade.clone(), SECRET), perCaller)).isEqualTo(Verdict.SIGNATURE_MISMATCH);
        assertThat(verify("debit", signed("debit", trade, OTHER_SECRET), perCaller)).isEqualTo(Verdict.OK);
    }

    @Test
    void 时间窗边界() {
        AssetOpAuth auth = new AssetOpAuth(c -> SECRET);
        AssetOpRequest ok = signed("debit", golden(), SECRET);
        assertThat(auth.verify("debit", ok, NOW + 300_000)).isEqualTo(Verdict.OK);
        assertThat(auth.verify("debit", ok, NOW - 300_000)).isEqualTo(Verdict.OK);
        assertThat(auth.verify("debit", ok, NOW + 300_001)).isEqualTo(Verdict.CLOCK_SKEW);
        assertThat(auth.verify("debit", ok, NOW - 300_001)).isEqualTo(Verdict.CLOCK_SKEW);
        assertThat(auth.verify("debit", ok, -1)).isEqualTo(Verdict.CLOCK_SKEW);
        AssetOpRequest.Builder absurd = golden();
        absurd.getAuthBuilder().setTimestampMs(-1L);
        assertThat(auth.verify("debit", signed("debit", absurd, SECRET), NOW)).as("2^64-1 毫秒").isEqualTo(Verdict.CLOCK_SKEW);
        assertThat(AssetOpAuth.withinClockSkew(Long.MAX_VALUE, 0)).isFalse();
    }
}
