package com.game.common.token;

import com.game.common.RunMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 观战凭证密钥的启动门禁（mmorpg battle-spectate-tiered-fanout.md §15.6、§15.7：口径同 battle 令牌）。纯函数，不打日志：拒启 / WARN 由调用点决定。
 *
 * <ul>
 *   <li>缺失或纯空白：prod 拒启；dev / test 放行并 WARN（gate 拒绝一切 SubscribeSpectate，match 不转中继档，只能走直连观战）——
 *       与 {@link BattleSecretPolicy} 的差别：battle 没密钥发不出任何票，观战中继没密钥只是少一档；</li>
 *   <li>去首尾空白后不足 {@value BattleSecretPolicy#MIN_SECRET_BYTES} 字节，或与同进程能看到的 gate / battle 令牌密钥相同：prod 拒启，dev / test 只 WARN。</li>
 * </ul>
 * 长度与空白的判定同 {@link BattleSecretPolicy}（按 UTF-8 字节、C {@code isspace} 的六个 ASCII 空白）。签名用密钥原始字节（{@link SpectateTickets#ofUtf8}）。
 */
public final class SpectateSecretPolicy {

    private SpectateSecretPolicy() {
    }

    /** 密钥的问题（与运行模式无关）。 */
    public enum Problem {
        NONE, MISSING, TOO_SHORT, SAME_AS_OTHER_DOMAIN
    }

    /** 调用点应采取的处置（语义同 {@link BattleSecretPolicy.Action}）。 */
    public record Verdict(Problem problem, BattleSecretPolicy.Action action) {

        public boolean refused() {
            return action == BattleSecretPolicy.Action.REFUSE;
        }

        /** 给日志 / 异常信息用的说明（不含密钥内容）。 */
        public String describe(String envName) {
            return switch (problem) {
                case NONE -> "观战凭证密钥检查通过";
                case MISSING -> envName + " 未配置或为空白";
                case TOO_SHORT -> envName + " 去首尾空白后不足 " + BattleSecretPolicy.MIN_SECRET_BYTES + " 字节";
                case SAME_AS_OTHER_DOMAIN -> envName + " 与 gate / battle 令牌密钥相同（信任域必须分开）";
            };
        }
    }

    /**
     * @param secret       观战凭证密钥（null = 未配置）
     * @param mode         运行模式（不认识的值调用方已按 prod 处理）
     * @param otherSecrets 同进程能看到的其它令牌密钥（null / 空的不比）
     */
    public static Verdict check(String secret, RunMode mode, String... otherSecrets) {
        byte[] trimmed = BattleSecretPolicy.trimAscii(utf8(secret));
        Problem problem = classify(trimmed, otherSecrets);
        if (problem == Problem.NONE) {
            return new Verdict(Problem.NONE, BattleSecretPolicy.Action.ACCEPT);
        }
        boolean nonProd = mode == RunMode.DEV || mode == RunMode.TEST;
        return new Verdict(problem, nonProd ? BattleSecretPolicy.Action.WARN : BattleSecretPolicy.Action.REFUSE);
    }

    private static Problem classify(byte[] trimmed, String... otherSecrets) {
        if (trimmed.length == 0) {
            return Problem.MISSING;
        }
        if (trimmed.length < BattleSecretPolicy.MIN_SECRET_BYTES) {
            return Problem.TOO_SHORT;
        }
        for (String other : otherSecrets) {
            byte[] bytes = utf8(other);
            if (bytes.length != 0 && Arrays.equals(trimmed, BattleSecretPolicy.trimAscii(bytes))) {
                return Problem.SAME_AS_OTHER_DOMAIN;
            }
        }
        return Problem.NONE;
    }

    private static byte[] utf8(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }
}
