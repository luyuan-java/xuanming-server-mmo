package com.game.scene.asset;

import com.game.api.asset.AssetOpSignatures;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资产通道的请求验签（同 mmorpg {@code asset_op_auth.cpp}）：调用方（帮会 / 交易服务）用<b>自己独有的密钥</b>对请求关键字段做
 * HMAC-SHA256，签名写在请求体的 {@code auth} 里；这边按同一规则重算并常数时间比对，不一致一律 UNKNOWN + 27008、不记账。
 * 不要 nonce：同一 (玩家, 流, 纪元, seq) 重放要么只读答复、要么就是那唯一一次应用；时间窗只限制截获包的寿命。
 *
 * <p>规范串、HMAC、密钥规范化与时间窗的<b>唯一出处</b>是 xm-api 的 {@link AssetOpSignatures}（调用方签名用的同一份代码，guild-economy-spec E5）；
 * 本类只管「哪个调用方可以碰哪条流」与判定顺序。
 *
 * <p>判定顺序固定：流 ↔ 调用方白名单（不可信的 caller 不会被拿去查密钥）→ 密钥（去首尾空白后 ≥ 32 字节，否则视同未配置）→
 * 时间窗（|now − timestamp_ms| ≤ 300 s）→ 签名。除通过外的每种判决对外都是同一个 27008（不告诉对方猜对了哪一半），区别只进 WARN 日志。
 *
 * <p>密钥只从环境变量注入（{@link AssetOpSignatures#SECRET_ENV_GUILD} / {@link AssetOpSignatures#SECRET_ENV_TRADE}），密钥值不进仓库、
 * 日志与错误文本；资产路径不做开发放行，本地开发值由启动脚本显式注入。某调用方的密钥缺失只报一次 ERROR（启动期配置错误，不刷屏）。
 * 线程：只在场景逻辑线程上调用；默认密钥来源按调用方缓存（并发安全的表，测试可换实现）。
 */
public final class AssetOpAuth {

    private static final Logger log = LoggerFactory.getLogger(AssetOpAuth.class);

    /** 验签判决（除 OK 外对外一律 27008）。 */
    public enum Verdict { OK, CALLER_NOT_ALLOWED, SECRET_MISSING, CLOCK_SKEW, SIGNATURE_MISMATCH }

    /** 调用方白名单：每个调用方一把密钥、独占自己的两条流。只列允许项，SYSTEM_CREDIT 没有合法调用方。 */
    private record CallerRule(String caller, String secretEnv, List<AssetStream> streams) {
    }

    private static final List<CallerRule> CALLERS = List.of(
            new CallerRule(AssetOpSignatures.CALLER_GUILD, AssetOpSignatures.SECRET_ENV_GUILD,
                    List.of(AssetStream.ASSET_STREAM_GUILD_DEBIT, AssetStream.ASSET_STREAM_GUILD_CREDIT)),
            new CallerRule(AssetOpSignatures.CALLER_TRADE, AssetOpSignatures.SECRET_ENV_TRADE,
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
     * 校验一次资产请求的签名。
     *
     * @param rpc   debit / abort_debit / credit（与进规范串的是同一个值，{@code AssetRpc.wireName()}）
     * @param nowMs 当前 Unix 毫秒
     */
    public Verdict verify(String rpc, AssetOpRequest request, long nowMs) {
        // 1. 白名单必须排在取密钥之前：caller 来自不可信请求；精确相等（不 trim、不忽略大小写）
        CallerRule rule = ruleForStream(request.getStream());
        String caller = request.getAuth().getCaller();
        if (rule == null || !rule.caller().equals(caller)) {
            return Verdict.CALLER_NOT_ALLOWED;
        }
        // 2. 密钥：去首尾空白后判长度（签名方拿去过空白的字节当 HMAC key；两侧同一个规范化函数）
        String secret = AssetOpSignatures.normalizeSecret(secretLookup.apply(rule.caller()));
        if (!AssetOpSignatures.usableSecret(secret)) {
            if (missingReported.putIfAbsent(rule.caller(), Boolean.TRUE) == null) {
                // 只写变量名与长度下限，不写密钥值、也不写实际长度
                log.error("[AssetOpAuth] 调用方密钥未配置或去空白后不足 {} 字节，该调用方的资产请求将全部被拒 caller={} env={}",
                        AssetOpSignatures.MIN_SECRET_BYTES, rule.caller(), rule.secretEnv());
            }
            return Verdict.SECRET_MISSING;
        }
        // 3. 时间窗：timestamp_ms 是不可信的 uint64，超出 long 正数范围（荒谬值）直接拒
        if (!AssetOpSignatures.withinClockSkew(request.getAuth().getTimestampMs(), nowMs)) {
            return Verdict.CLOCK_SKEW;
        }
        // 4. 签名：常数时间比对（长度不同直接不等）
        return AssetOpSignatures.signatureMatches(rpc, request, secret) ? Verdict.OK : Verdict.SIGNATURE_MISMATCH;
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
