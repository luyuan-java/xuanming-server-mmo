package com.game.common.token;

import com.game.common.RunMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * battle 票据密钥的启动门禁（基线 {@code main.cpp ValidateBattleClientEdgeConfigOrDie} + {@code battle_security.h ClassifySecretStrength} +
 * {@code token_security.h ClassifyTokenSecret}；battle-node-spec §6.3、§7.5）。纯函数，不打日志：拒启 / WARN 由调用点决定。
 *
 * <ul>
 *   <li>缺失或纯空白 → <b>任何运行模式</b>都拒启（Java 不放行空密钥，§11 N5；基线 dev / test 会跳过验签）；</li>
 *   <li>去首尾空白后不足 {@value #MIN_SECRET_BYTES} 字节 → prod 拒启，dev / test 只 WARN；</li>
 *   <li>与 gate 令牌密钥（进程能看到时；两边都去首尾空白后比较）相同 → prod 拒启，dev / test 只 WARN（信任域没分开）；
 *       gate 密钥不可见或为空时不比（Q11）。</li>
 * </ul>
 *
 * <p>「空白」与长度都按 UTF-8 字节、C 语言 {@code isspace} 的六个 ASCII 空白（空格、\t、\n、\v、\f、\r）计算，同基线 {@code TrimAscii}。
 * 签名用的是密钥原始字节（不去空白，见 {@link BattleTickets#ofUtf8}）；去空白只用于这里的判定。
 */
public final class BattleSecretPolicy {

    /** HMAC-SHA256 输出 32 字节；密钥短于它时暴力搜索空间塌到密钥长度上（基线 {@code kMinTokenSecretBytes}）。 */
    public static final int MIN_SECRET_BYTES = 32;

    private BattleSecretPolicy() {
    }

    /** 密钥的问题（与运行模式无关）。 */
    public enum Problem {
        /** 没有问题。 */
        NONE,
        /** 未配置或纯空白。 */
        MISSING,
        /** 去首尾空白后不足 {@value #MIN_SECRET_BYTES} 字节（基线 {@code kTooShort}）。 */
        TOO_SHORT,
        /** 与 gate 令牌密钥相同（基线 {@code kSameAsGate}）。 */
        SAME_AS_GATE;

        /** 日志用的名字：none / missing / too_short / same_as_gate。 */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 调用点应采取的处置。 */
    public enum Action {
        /** 照常启动。 */
        ACCEPT,
        /** 照常启动，但打醒目 WARN（只在 dev / test 出现）。 */
        WARN,
        /** 拒绝启动。 */
        REFUSE
    }

    /** 判定结果。 */
    public record Verdict(Problem problem, Action action) {

        public boolean refused() {
            return action == Action.REFUSE;
        }

        /** 给日志 / 异常信息用的说明（不含密钥内容）。 */
        public String describe() {
            return switch (problem) {
                case NONE -> "battle 票据密钥检查通过";
                case MISSING -> "XM_BATTLE_TOKEN_SECRET 未配置或为空白（任何运行模式都必填）";
                case TOO_SHORT -> "XM_BATTLE_TOKEN_SECRET 去首尾空白后不足 " + MIN_SECRET_BYTES + " 字节";
                case SAME_AS_GATE -> "XM_BATTLE_TOKEN_SECRET 与 XM_GATE_TOKEN_SECRET 相同（两个信任域必须分开）";
            };
        }
    }

    /**
     * 启动门禁。
     *
     * @param battleSecret     票据密钥（环境变量 {@code XM_BATTLE_TOKEN_SECRET}；null = 未配置）
     * @param gateSecretOrNull gate 令牌密钥（进程能看到 {@code XM_GATE_TOKEN_SECRET} 时传入；null / 空 = 不比）
     * @param mode             运行模式（不认识的值调用方已按 prod 处理）
     */
    public static Verdict check(String battleSecret, String gateSecretOrNull, RunMode mode) {
        byte[] trimmed = trimAscii(utf8(battleSecret));
        if (trimmed.length == 0) {
            return new Verdict(Problem.MISSING, Action.REFUSE);
        }
        Problem problem = classifyStrength(battleSecret, gateSecretOrNull);
        if (problem == Problem.NONE) {
            return new Verdict(Problem.NONE, Action.ACCEPT);
        }
        boolean nonProd = mode == RunMode.DEV || mode == RunMode.TEST;
        return new Verdict(problem, nonProd ? Action.WARN : Action.REFUSE);
    }

    /**
     * 密钥强度（基线 {@code ClassifySecretStrength}）：只在密钥非空白时有意义；gate 密钥为空 / null 时不判相同。
     * 返回 {@link Problem#NONE} / {@link Problem#TOO_SHORT} / {@link Problem#SAME_AS_GATE}（空白密钥按 TOO_SHORT）。
     */
    public static Problem classifyStrength(String battleSecret, String gateSecretOrNull) {
        byte[] trimmed = trimAscii(utf8(battleSecret));
        if (trimmed.length < MIN_SECRET_BYTES) {
            return Problem.TOO_SHORT;
        }
        byte[] gate = utf8(gateSecretOrNull);
        // 基线判的是 gateSecret 原值非空（不是去空白后非空）
        if (gate.length != 0 && Arrays.equals(trimmed, trimAscii(gate))) {
            return Problem.SAME_AS_GATE;
        }
        return Problem.NONE;
    }

    private static byte[] utf8(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

    /** 去掉首尾 C {@code isspace} 空白字节（' '、\t、\n、\v、\f、\r），同基线 {@code TrimAscii}。 */
    static byte[] trimAscii(byte[] bytes) {
        int begin = 0;
        int end = bytes.length;
        while (begin < end && isAsciiSpace(bytes[begin])) {
            begin++;
        }
        while (end > begin && isAsciiSpace(bytes[end - 1])) {
            end--;
        }
        return Arrays.copyOfRange(bytes, begin, end);
    }

    private static boolean isAsciiSpace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == 0x0B || b == '\f' || b == '\r';
    }
}
