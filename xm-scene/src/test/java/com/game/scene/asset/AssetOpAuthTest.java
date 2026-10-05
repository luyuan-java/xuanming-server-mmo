package com.game.scene.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.asset.AssetOpSignatures;
import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetAuth;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetStream;
import com.game.scene.asset.AssetOpAuth.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * 验签，用例对应 mmorpg asset_op_auth_test.cpp。规范串与 HMAC 的 golden 整表在 xm-api 的 {@code AssetOpSignaturesTest}（唯一出处，E5）；
 * 这里用 scene 验签再钉一次同一条 golden 签名（两侧各钉一次），其余钉白名单 / 密钥 / 时间窗 / 篡改的判定顺序。
 */
class AssetOpAuthTest {

    /** 夹具密钥（33 字节，只在测试里存在）。 */
    static final String SECRET = "asset-op-test-secret-0123456789ab";
    private static final String TOO_SHORT = "asset-op-test-secret-0123456789";
    private static final String OTHER_SECRET = "asset-op-other-secret-0123456789ab";
    private static final long NOW = 1_700_000_000_123L;
    /** golden 请求（密钥 {@link #SECRET}、rpc debit）的签名：与 xm-api AssetOpSignaturesTest.GOLDEN_SIGNATURE 同值（openssl 独立算出）。 */
    private static final String GOLDEN_SIGNATURE = "67eeb8b92b33c5a4cd7416c88614c0ded3cc50ec777a73e66c00f27d15bfdd29";

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

    /** 按 rpc 签好（签名必须在所有参与规范串的字段都填好之后算；caller / 时间戳取 builder 里已有的）。 */
    static AssetOpRequest signed(String rpc, AssetOpRequest.Builder request, String secret) {
        AssetOpRequest unsigned = request.build();
        return request.setAuth(request.getAuth().toBuilder()
                .setSignatureHex(AssetOpSignatures.hmacHex(secret, AssetOpSignatures.canonical(rpc, unsigned)))).build();
    }

    private static Verdict verify(String rpc, AssetOpRequest request, Function<String, String> lookup) {
        return new AssetOpAuth(lookup).verify(rpc, request, NOW);
    }

    private static Verdict verify(AssetOpRequest request) {
        return verify("debit", request, caller -> SECRET);
    }

    @Test
    void golden签名_scene验得过_与调用方签名器同一份代码() {
        AssetOpRequest request = golden().setAuth(golden().getAuth().toBuilder().setSignatureHex(GOLDEN_SIGNATURE)).build();
        assertThat(verify(request)).isEqualTo(Verdict.OK);
        assertThat(signed("debit", golden(), SECRET).getAuth().getSignatureHex()).isEqualTo(GOLDEN_SIGNATURE);
        AssetOpRequest bySigner = AssetOpSignatures.sign(AssetRpc.DEBIT, golden().clearAuth().build(),
                AssetOpSignatures.CALLER_GUILD, SECRET, NOW);
        assertThat(bySigner.getAuth().getSignatureHex()).isEqualTo(GOLDEN_SIGNATURE);
        assertThat(verify(bySigner)).isEqualTo(Verdict.OK);
        assertThat(verify("abort_debit", bySigner, c -> SECRET)).as("rpc 进串").isEqualTo(Verdict.SIGNATURE_MISMATCH);
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
        assertThat(TOO_SHORT.length()).isEqualTo(AssetOpSignatures.MIN_SECRET_BYTES - 1);
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
        assertThat(AssetOpSignatures.withinClockSkew(Long.MAX_VALUE, 0)).isFalse();
    }
}
