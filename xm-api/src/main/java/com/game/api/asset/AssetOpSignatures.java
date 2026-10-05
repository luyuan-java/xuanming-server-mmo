package com.game.api.asset;

import com.game.api.proto.AssetAuth;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetItem;
import com.game.api.proto.AssetOpRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 资产通道请求签名的<b>唯一出处</b>（规范串、HMAC、密钥规则；同基线 {@code go/shared/assetop/auth.go} 与 C++ {@code asset_op_auth.cpp}，
 * 「本包唯一允许出现串格式知识的地方」{@code auth.go:72-74}）。调用方（xm-guild，以后的交易）签名与 scene 验签都只用这里的函数：
 * 两份实现一分叉，scene 就把全部请求判成验签失败（UNKNOWN 27008），行永远卡在 PENDING（guild-economy-spec E5）。
 *
 * <p>为什么签名在请求体里：Dubbo 调用方 MAC（{@code XM_DUBBO_SECRET}）不覆盖请求体（architecture.md §4.1 残余风险），
 * 请求体 HMAC 覆盖全部关键字段（含 {@code ;u=;p=}），两层互补（E3）。不要 nonce：同一 (玩家, 流, 纪元, seq) 重放要么只读答复、
 * 要么就是那唯一一次应用；时间窗只限制截获包的寿命。
 *
 * <p>密钥：每个调用方一把，只从环境变量注入（{@link #SECRET_ENV_GUILD} / {@link #SECRET_ENV_TRADE}），去首尾空白
 * （{@link #normalizeSecret}，签验两侧同一个函数——Go 的 TrimSpace 与 Java 的 trim 对 Unicode 空白不同，两侧都是 Java 后共用这一份即可）
 * 后至少 {@link #MIN_SECRET_BYTES} 字节，否则视同未配置。密钥值不进日志、指标与错误文本。
 *
 * <p>无状态，线程安全。
 */
public final class AssetOpSignatures {

    /** 规范串第一行，同时是协议版本（与基线同字面量：同一组输入的规范串逐字节相同，可直接对拍基线的 golden）。 */
    public static final String CANONICAL_VERSION = "mmorpg-asset-op/v1";
    /** scene 接受的签名时间窗（双向，毫秒；同基线 kAssetOpAuthMaxSkewMs / MaxClockSkewMs）。 */
    public static final long MAX_CLOCK_SKEW_MS = 300_000;
    /** 密钥去首尾空白后的最小字节数（同基线 MinSecretLen）。 */
    public static final int MIN_SECRET_BYTES = 32;

    /** 帮会：独占 GUILD_DEBIT / GUILD_CREDIT 两条流。 */
    public static final String CALLER_GUILD = "guild";
    /** 交易：独占 TRADE_DEBIT / TRADE_CREDIT 两条流。 */
    public static final String CALLER_TRADE = "trade";
    /** 帮会密钥的环境变量（scene 与 xm-guild 注入同一个值；基线 MMORPG_ASSET_OP_SECRET_GUILD，E4）。 */
    public static final String SECRET_ENV_GUILD = "XM_ASSET_OP_SECRET_GUILD";
    /** 交易密钥的环境变量。 */
    public static final String SECRET_ENV_TRADE = "XM_ASSET_OP_SECRET_TRADE";

    private AssetOpSignatures() {
    }

    /** {@link #canonical(String, AssetOpRequest)}，rpc 取 {@link AssetRpc#wireName()}。 */
    public static String canonical(AssetRpc rpc, AssetOpRequest request) {
        return canonical(rpc.wireName(), request);
    }

    /**
     * 待签名串：LF 分隔、末尾无换行、全部十进制（无符号字段按无符号写，流按有符号写——开放枚举可能带未知的负值）。
     * caller 与 timestamp_ms 取自请求里的 {@code auth}（签名方先写好再算）。
     * <pre>
     * mmorpg-asset-op/v1
     * caller
     * rpc            debit | abort_debit | credit（防止拿中止的签名去调发放）
     * player_id
     * stream
     * stream_epoch
     * seq
     * correlation_id
     * tx_type
     * bundle         c=类型:数额,…;i=配置:数量,…;u=实例号,…;p=宝宝号（全空为 c=;i=;u=;p=0；按请求顺序，不排序）
     * timestamp_ms
     * </pre>
     */
    public static String canonical(String rpc, AssetOpRequest request) {
        StringBuilder out = new StringBuilder(160)
                .append(CANONICAL_VERSION).append('\n')
                .append(request.getAuth().getCaller()).append('\n')
                .append(rpc).append('\n')
                .append(Long.toUnsignedString(request.getPlayerId())).append('\n')
                .append(request.getStreamValue()).append('\n')
                .append(Long.toUnsignedString(request.getStreamEpoch())).append('\n')
                .append(Long.toUnsignedString(request.getSeq())).append('\n')
                .append(Long.toUnsignedString(request.getCorrelationId())).append('\n')
                .append(Integer.toUnsignedString(request.getTxType())).append('\n');
        appendBundle(out, request.getBundle());
        return out.append('\n').append(Long.toUnsignedString(request.getAuth().getTimestampMs())).toString();
    }

    private static void appendBundle(StringBuilder out, AssetBundle bundle) {
        out.append("c=");
        for (int i = 0; i < bundle.getCurrenciesCount(); i++) {
            AssetCurrency currency = bundle.getCurrencies(i);
            if (i > 0) {
                out.append(',');
            }
            out.append(Integer.toUnsignedString(currency.getCurrencyType())).append(':')
                    .append(Long.toUnsignedString(currency.getAmount()));
        }
        out.append(";i=");
        for (int i = 0; i < bundle.getItemsCount(); i++) {
            AssetItem item = bundle.getItems(i);
            if (i > 0) {
                out.append(',');
            }
            out.append(Integer.toUnsignedString(item.getConfigId())).append(':')
                    .append(Integer.toUnsignedString(item.getCount()));
        }
        // 按实例扣发的两个字段也进串：不进的话截下一条合法签名、只换掉实例号就能扣走别的装备（auth.go:121-126）
        out.append(";u=");
        for (int i = 0; i < bundle.getItemUuidsCount(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(Long.toUnsignedString(bundle.getItemUuids(i)));
        }
        out.append(";p=").append(Long.toUnsignedString(bundle.getPetId()));
    }

    /** HMAC-SHA256(secret 的 UTF-8 字节, canonical 的 UTF-8 字节) 的小写十六进制。secret 应已经过 {@link #normalizeSecret}。 */
    public static String hmacHex(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK 缺 HmacSHA256", e);
        }
    }

    /** 密钥规范化：null 视同空串，去首尾空白（签验两侧都用这一个函数）。 */
    public static String normalizeSecret(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /** 规范化之后的密钥是否够长（≥ {@link #MIN_SECRET_BYTES} 字节 UTF-8）。 */
    public static boolean usableSecret(String normalized) {
        return normalized != null && normalized.getBytes(StandardCharsets.UTF_8).length >= MIN_SECRET_BYTES;
    }

    /**
     * 调用方启动时取密钥：规范化后不够长即抛出（拒绝启动）。异常文本只有变量名与长度下限，不含密钥值、也不含实际长度。
     *
     * @param envName  环境变量名（进错误文本）
     * @param rawValue 环境变量原值（可为 null）
     * @return 规范化后的密钥
     */
    public static String requireSecret(String envName, String rawValue) {
        String secret = normalizeSecret(rawValue);
        if (!usableSecret(secret)) {
            throw new IllegalStateException("资产通道调用方密钥 " + envName + " 未配置或去首尾空白后不足 " + MIN_SECRET_BYTES
                    + " 字节，拒绝启动");
        }
        return secret;
    }

    /**
     * 签名（同基线 {@code Signer.Sign}，{@code auth.go:151-162}）：返回带 {@code auth{caller, timestamp_ms, signature_hex}} 的新请求，
     * 入参不变。调用方必须传<b>这一次实际发包</b>的时刻：每次重投 / 重查都重签，否则时间窗会过期。
     *
     * @param caller 调用方名（{@link #CALLER_GUILD} 等；进规范串，也是 scene 的流白名单键）
     * @param secret 已规范化且够长的密钥（{@link #requireSecret}）
     * @param nowMs  发包时刻（Unix 毫秒）
     */
    public static AssetOpRequest sign(AssetRpc rpc, AssetOpRequest request, String caller, String secret, long nowMs) {
        return sign(Objects.requireNonNull(rpc, "rpc").wireName(), request, caller, secret, nowMs);
    }

    /** 同 {@link #sign(AssetRpc, AssetOpRequest, String, String, long)}，rpc 直接给规范串第 3 行（debit / abort_debit / credit）。 */
    public static AssetOpRequest sign(String rpc, AssetOpRequest request, String caller, String secret, long nowMs) {
        Objects.requireNonNull(rpc, "rpc");
        Objects.requireNonNull(request, "request");
        if (caller == null || caller.isBlank()) {
            throw new IllegalArgumentException("资产通道调用方名为空");
        }
        if (!usableSecret(secret)) {
            throw new IllegalArgumentException("资产通道调用方密钥不足 " + MIN_SECRET_BYTES + " 字节（caller=" + caller + "）");
        }
        AssetOpRequest withAuth = request.toBuilder()
                .setAuth(AssetAuth.newBuilder().setCaller(caller).setTimestampMs(nowMs))
                .build();
        String signature = hmacHex(secret, canonical(rpc, withAuth));
        return withAuth.toBuilder().setAuth(withAuth.getAuth().toBuilder().setSignatureHex(signature)).build();
    }

    /**
     * 签名是否与按 secret 重算的一致（常数时间比对，长度不同直接不等；大写十六进制不认）。只比签名，不管调用方白名单与时间窗——
     * 那两项由验签方（scene 的 {@code AssetOpAuth}）按自己的顺序先判。
     *
     * @param secret 已规范化且够长的密钥
     */
    public static boolean signatureMatches(String rpc, AssetOpRequest request, String secret) {
        byte[] expected = hmacHex(secret, canonical(rpc, request)).getBytes(StandardCharsets.UTF_8);
        byte[] actual = request.getAuth().getSignatureHex().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    /** |now − timestamp| ≤ 300 s。timestamp_ms 是不可信的 uint64：超出 long 正数范围（荒谬值）与负的 now 一律不在窗内。 */
    public static boolean withinClockSkew(long timestampMs, long nowMs) {
        if (nowMs < 0 || timestampMs < 0) {
            return false;
        }
        return Math.abs(nowMs - timestampMs) <= MAX_CLOCK_SKEW_MS;
    }
}
