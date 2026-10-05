package com.game.api.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.AssetAuth;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetItem;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 规范串与签名的唯一出处（guild-economy-spec E5）。golden 与基线逐字节相同（{@code go/shared/assetop/auth_test.go} 的
 * TestCanonicalGolden、C++ {@code asset_op_auth_test.cpp} 同一组输入）；xm-scene 的 {@code AssetOpAuthTest} 用 scene 验签再钉一次
 * 同一条 golden 签名——两侧各钉一次。
 */
class AssetOpSignaturesTest {

    /** 夹具密钥（33 字节，只在测试里存在）。 */
    static final String SECRET = "asset-op-test-secret-0123456789ab";
    private static final long NOW = 1_700_000_000_123L;

    /**
     * golden 请求的签名（密钥 {@link #SECRET}、rpc debit），用 openssl 独立算出：
     * {@code printf '<golden 规范串>' | openssl dgst -sha256 -hmac '<SECRET>'}。scene 侧 AssetOpAuthTest 钉同一个值。
     */
    static final String GOLDEN_SIGNATURE = "67eeb8b92b33c5a4cd7416c88614c0ded3cc50ec777a73e66c00f27d15bfdd29";

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

    @Test
    void 规范串golden_与基线逐字节相同() {
        assertThat(AssetOpSignatures.canonical(AssetRpc.DEBIT, golden().build())).isEqualTo(
                "mmorpg-asset-op/v1\nguild\ndebit\n42\n1\n1700000000000\n7\n99\n24\nc=1:30;i=;u=;p=0\n1700000000123");
    }

    @Test
    void 三个入口的规范串第3行() {
        assertThat(AssetRpc.DEBIT.wireName()).isEqualTo("debit");
        assertThat(AssetRpc.ABORT_DEBIT.wireName()).isEqualTo("abort_debit");
        assertThat(AssetRpc.CREDIT.wireName()).isEqualTo("credit");
        for (AssetRpc rpc : AssetRpc.values()) {
            assertThat(AssetOpSignatures.canonical(rpc, golden().build()).split("\n")[2]).isEqualTo(rpc.wireName());
        }
    }

    @Test
    void 空包的写法() {
        AssetOpRequest request = AssetOpRequest.newBuilder().setPlayerId(7).setStream(AssetStream.ASSET_STREAM_GUILD_DEBIT)
                .setSeq(1).setStreamEpoch(2).setAuth(AssetAuth.newBuilder().setCaller("guild").setTimestampMs(5)).build();
        assertThat(AssetOpSignatures.canonical("abort_debit", request))
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
        assertThat(AssetOpSignatures.canonical("credit", request))
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
        assertThat(AssetOpSignatures.canonical("debit", request)).isEqualTo("mmorpg-asset-op/v1\ntrade\ndebit\n" + max64
                + "\n3\n" + max64 + "\n" + max64 + "\n" + max64 + "\n" + max32 + "\nc=" + max32 + ":" + max64 + ";i=" + max32
                + ":" + max32 + ";u=5," + max64 + ";p=" + max64 + "\n" + max64);
    }

    @Test
    void 未知流按有符号十进制写() {
        assertThat(AssetOpSignatures.canonical("debit", golden().setStreamValue(-3).build())).contains("\n42\n-3\n");
    }

    @Test
    void HMAC是标准的HmacSHA256小写十六进制() {
        // RFC 4231 测试用例 2
        assertThat(AssetOpSignatures.hmacHex("Jefe", "what do ya want for nothing?"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void 签名写入caller与发包时刻_入参不变() {
        AssetOpRequest unsigned = golden().clearAuth().build();
        AssetOpRequest signed = AssetOpSignatures.sign(AssetRpc.DEBIT, unsigned, "guild", SECRET, NOW);
        assertThat(unsigned.hasAuth()).as("入参不变（克隆后签）").isFalse();
        assertThat(signed.getAuth().getCaller()).isEqualTo("guild");
        assertThat(signed.getAuth().getTimestampMs()).isEqualTo(NOW);
        assertThat(signed.getAuth().getSignatureHex()).isEqualTo(GOLDEN_SIGNATURE).hasSize(64)
                .matches("[0-9a-f]{64}");
        assertThat(signed.toBuilder().clearAuth().build()).isEqualTo(unsigned);
        assertThat(AssetOpSignatures.signatureMatches("debit", signed, SECRET)).isTrue();
        assertThat(AssetOpSignatures.sign("debit", unsigned, "guild", SECRET, NOW)).as("按规范串名签与按枚举签相同")
                .isEqualTo(signed);
    }

    @Test
    void 每次发包重签_时间不同签名不同_旧签名换了rpc或改字段都对不上() {
        AssetOpRequest first = AssetOpSignatures.sign(AssetRpc.DEBIT, golden().build(), "guild", SECRET, NOW);
        AssetOpRequest again = AssetOpSignatures.sign(AssetRpc.DEBIT, first, "guild", SECRET, NOW + 100);
        assertThat(again.getAuth().getSignatureHex()).isNotEqualTo(first.getAuth().getSignatureHex());
        assertThat(AssetOpSignatures.signatureMatches("debit", again, SECRET)).isTrue();
        assertThat(AssetOpSignatures.signatureMatches("abort_debit", first, SECRET)).as("rpc 进串").isFalse();
        List<AssetOpRequest> tampered = List.of(
                first.toBuilder().setSeq(8).build(),
                first.toBuilder().setBundle(first.getBundle().toBuilder().addItemUuids(1)).build(),
                first.toBuilder().setBundle(first.getBundle().toBuilder().setPetId(1)).build(),
                first.toBuilder().setAuth(first.getAuth().toBuilder().setSignatureHex(
                        first.getAuth().getSignatureHex().toUpperCase())).build());
        for (AssetOpRequest r : tampered) {
            assertThat(AssetOpSignatures.signatureMatches("debit", r, SECRET)).isFalse();
        }
        assertThat(AssetOpSignatures.signatureMatches("debit", first, "asset-op-other-secret-0123456789ab")).isFalse();
    }

    @Test
    void 密钥规则_去首尾空白后至少32字节_错误文本不含密钥() {
        assertThat(AssetOpSignatures.normalizeSecret(null)).isEmpty();
        assertThat(AssetOpSignatures.normalizeSecret(" \t" + SECRET + "\n ")).isEqualTo(SECRET);
        assertThat(AssetOpSignatures.usableSecret(SECRET)).isTrue();
        assertThat(AssetOpSignatures.usableSecret("x".repeat(31))).isFalse();
        assertThat(AssetOpSignatures.usableSecret("x".repeat(32))).isTrue();
        assertThat(AssetOpSignatures.requireSecret("XM_ASSET_OP_SECRET_GUILD", "  " + SECRET + "  ")).isEqualTo(SECRET);
        String weak = "short-secret-value";
        assertThatThrownBy(() -> AssetOpSignatures.requireSecret("XM_ASSET_OP_SECRET_GUILD", weak))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("XM_ASSET_OP_SECRET_GUILD")
                .hasMessageNotContaining(weak);
        assertThatThrownBy(() -> AssetOpSignatures.requireSecret("XM_ASSET_OP_SECRET_GUILD", " ".repeat(40)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AssetOpSignatures.sign(AssetRpc.DEBIT, golden().build(), "guild", weak, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(weak);
        assertThatThrownBy(() -> AssetOpSignatures.sign(AssetRpc.DEBIT, golden().build(), " ", SECRET, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 时间窗边界() {
        assertThat(AssetOpSignatures.withinClockSkew(NOW, NOW + 300_000)).isTrue();
        assertThat(AssetOpSignatures.withinClockSkew(NOW, NOW - 300_000)).isTrue();
        assertThat(AssetOpSignatures.withinClockSkew(NOW, NOW + 300_001)).isFalse();
        assertThat(AssetOpSignatures.withinClockSkew(NOW, NOW - 300_001)).isFalse();
        assertThat(AssetOpSignatures.withinClockSkew(-1L, NOW)).as("2^64-1 毫秒").isFalse();
        assertThat(AssetOpSignatures.withinClockSkew(NOW, -1)).isFalse();
        assertThat(AssetOpSignatures.withinClockSkew(Long.MAX_VALUE, 0)).isFalse();
    }
}
