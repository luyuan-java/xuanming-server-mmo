package com.game.scene.asset;

import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetItem;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资产通道的请求验签（同 mmorpg {@code asset_op_auth.cpp}）：调用方（帮会 / 交易服务）用<b>自己独有的密钥</b>对请求关键字段做
 * HMAC-SHA256，签名写在请求体的 {@code auth} 里；这边按同一规则重算并常数时间比对，不一致一律 UNKNOWN + 27008、不记账。
 * 不要 nonce：同一 (玩家, 流, 纪元, seq) 重放要么只读答复、要么就是那唯一一次应用；时间窗只限制截获包的寿命。
 *
 * <p>判定顺序固定：流 ↔ 调用方白名单（不可信的 caller 不会被拿去查密钥）→ 密钥（去首尾空白后 ≥ 32 字节，否则视同未配置）→
 * 时间窗（|now − timestamp_ms| ≤ 300 s）→ 签名。除通过外的每种判决对外都是同一个 27008（不告诉对方猜对了哪一半），区别只进 WARN 日志。
 *
 * <p>密钥只从环境变量注入（{@code XM_ASSET_OP_SECRET_GUILD} / {@code XM_ASSET_OP_SECRET_TRADE}），密钥值不进仓库、日志与错误文本；
 * 资产路径不做开发放行，本地开发值由启动脚本显式注入。某调用方的密钥缺失只报一次 ERROR（启动期配置错误，不刷屏）。
 * 线程：只在场景逻辑线程上调用；默认密钥来源按调用方缓存（并发安全的表，测试可换实现）。
 */
public final class AssetOpAuth {

    private static final Logger log = LoggerFactory.getLogger(AssetOpAuth.class);

    /** 规范串第一行，同时是协议版本（与基线同字面量：同一组输入的规范串逐字节相同，可直接对拍基线的 golden）。 */
    public static final String CANONICAL_VERSION = "mmorpg-asset-op/v1";
    public static final long MAX_CLOCK_SKEW_MS = 300_000;
    public static final int MIN_SECRET_BYTES = 32;

    /** 验签判决（除 OK 外对外一律 27008）。 */
    public enum Verdict { OK, CALLER_NOT_ALLOWED, SECRET_MISSING, CLOCK_SKEW, SIGNATURE_MISMATCH }

    /** 调用方白名单：每个调用方一把密钥、独占自己的两条流。只列允许项，SYSTEM_CREDIT 没有合法调用方。 */
    private record CallerRule(String caller, String secretEnv, List<AssetStream> streams) {
    }

    private static final List<CallerRule> CALLERS = List.of(
            new CallerRule("guild", "XM_ASSET_OP_SECRET_GUILD",
                    List.of(AssetStream.ASSET_STREAM_GUILD_DEBIT, AssetStream.ASSET_STREAM_GUILD_CREDIT)),
            new CallerRule("trade", "XM_ASSET_OP_SECRET_TRADE",
                    List.of(AssetStream.ASSET_STREAM_TRADE_DEBIT, AssetStream.ASSET_STREAM_TRADE_CREDIT)));

    private final Function<String, String> secretLookup;
    /** 已报过「密钥未配置」的调用方。 */
    private final Map<String, Boolean> missingReported = new ConcurrentHashMap<>();

    /**
     * @param secretLookup 调用方名 → 密钥（null / 空 = 未配置）；只会被问到白名单里的调用方
     */
    public AssetOpAuth(Function<String, String> secretLookup) {
        this.secretLookup = secretLookup;
    }

    /** 生产实现：从环境变量读密钥并按调用方缓存（进程生命周期内不变）。 */
    public static AssetOpAuth fromEnvironment() {
        Map<String, String> cache = new ConcurrentHashMap<>();
        return new AssetOpAuth(caller -> cache.computeIfAbsent(caller, c -> {
            for (CallerRule rule : CALLERS) {
                if (rule.caller().equals(c)) {
                    String value = System.getenv(rule.secretEnv());
                    return value == null ? "" : value;
                }
            }
            return "";
        }));
    }

    /**
     * 待签名串：LF 分隔、末尾无换行、全部十进制（无符号字段按无符号写，流按有符号写——开放枚举可能带未知的负值）。
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
        // 按实例扣发的两个字段也进串：不进的话截下一条合法签名、只换掉实例号就能扣走别的装备
        out.append(";u=");
        for (int i = 0; i < bundle.getItemUuidsCount(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(Long.toUnsignedString(bundle.getItemUuids(i)));
        }
        out.append(";p=").append(Long.toUnsignedString(bundle.getPetId()));
    }

    /** HMAC-SHA256 的小写十六进制（签名方与测试用）。 */
    public static String sign(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK 缺 HmacSHA256", e);
        }
    }

    /**
     * 校验一次资产请求的签名。
     *
     * @param rpc   debit / abort_debit / credit（与进规范串的是同一个值）
     * @param nowMs 当前 Unix 毫秒
     */
    public Verdict verify(String rpc, AssetOpRequest request, long nowMs) {
        // 1. 白名单必须排在取密钥之前：caller 来自不可信请求；精确相等（不 trim、不忽略大小写）
        CallerRule rule = ruleForStream(request.getStream());
        String caller = request.getAuth().getCaller();
        if (rule == null || !rule.caller().equals(caller)) {
            return Verdict.CALLER_NOT_ALLOWED;
        }
        // 2. 密钥：去首尾空白后判长度（签名方拿去过空白的字节当 HMAC key）
        String raw = secretLookup.apply(rule.caller());
        String secret = raw == null ? "" : raw.trim();
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            if (missingReported.putIfAbsent(rule.caller(), Boolean.TRUE) == null) {
                // 只写变量名与长度下限，不写密钥值、也不写实际长度
                log.error("[AssetOpAuth] 调用方密钥未配置或去空白后不足 {} 字节，该调用方的资产请求将全部被拒 caller={} env={}",
                        MIN_SECRET_BYTES, rule.caller(), rule.secretEnv());
            }
            return Verdict.SECRET_MISSING;
        }
        // 3. 时间窗：timestamp_ms 是不可信的 uint64，超出 long 正数范围（荒谬值）直接拒
        if (!withinClockSkew(request.getAuth().getTimestampMs(), nowMs)) {
            return Verdict.CLOCK_SKEW;
        }
        // 4. 签名：常数时间比对（长度不同直接不等）
        byte[] expected = sign(secret, canonical(rpc, request)).getBytes(StandardCharsets.UTF_8);
        byte[] actual = request.getAuth().getSignatureHex().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual) ? Verdict.OK : Verdict.SIGNATURE_MISMATCH;
    }

    static boolean withinClockSkew(long timestampMs, long nowMs) {
        if (nowMs < 0 || timestampMs < 0) {
            return false;
        }
        return Math.abs(nowMs - timestampMs) <= MAX_CLOCK_SKEW_MS;
    }

    /** 这条流唯一允许的调用方；null = 没有合法调用方（含 UNSPECIFIED、SYSTEM_CREDIT 与未知值）。 */
    private static CallerRule ruleForStream(AssetStream stream) {
        for (CallerRule rule : CALLERS) {
            if (rule.streams().contains(stream)) {
                return rule;
            }
        }
        return null;
    }
}
