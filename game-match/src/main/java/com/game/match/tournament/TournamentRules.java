package com.game.match.tournament;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 全服赛季战队锦标赛《群仙问道赛》V1 纯规则核（与 Go 侧 {@code internal/tournament/rules.go} 逐条同构）。
 * 纯函数、无状态、线程安全。
 */
public final class TournamentRules {

    public static final int MIN_SQUAD_NAME_CODEPOINTS = 2;
    public static final int MAX_SQUAD_NAME_CODEPOINTS = 12;

    private TournamentRules() {
    }

    public record NormalizedName(String display, String norm) {
    }

    public record StandingEntry(long squadId, int qualifierPoints, int qualifierWins,
                                int qualifierDraws, long registeredMs) {
    }

    public record KnockoutTieMetrics(long squadId, int seedRank, int survivingUnits, long remainingHpSum) {
    }

    /** 校验并归一化战队名：去首尾空白、2..12 Unicode 字符、禁控制字符，归一化键为全小写去空白。 */
    public static Optional<NormalizedName> normalizeSquadName(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.strip();
        int codePoints = trimmed.codePointCount(0, trimmed.length());
        if (codePoints < MIN_SQUAD_NAME_CODEPOINTS || codePoints > MAX_SQUAD_NAME_CODEPOINTS) {
            return Optional.empty();
        }
        StringBuilder norm = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); ) {
            int cp = trimmed.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || cp == 0xFFFD) {
                return Optional.empty();
            }
            if (Character.isWhitespace(cp)) {
                continue;
            }
            norm.appendCodePoint(Character.toLowerCase(cp));
        }
        if (norm.codePointCount(0, norm.length()) < MIN_SQUAD_NAME_CODEPOINTS) {
            return Optional.empty();
        }
        return Optional.of(new NormalizedName(trimmed, norm.toString()));
    }

    /** 按海选有效队数动态确定淘汰赛规模（0 / 2 / 4 / 8 / 16），冷启动低人口绝不流产。 */
    public static int knockoutBracketSize(int qualifiedCount, int maxTeams) {
        if (qualifiedCount < 2) {
            return 0;
        }
        int cap = Math.max(2, Math.min(16, maxTeams));
        int effective = Math.min(qualifiedCount, cap);
        int size = 2;
        while (size * 2 <= effective) {
            size *= 2;
        }
        if (effective > size && size * 2 <= cap) {
            size *= 2;
        }
        return size;
    }

    public static int totalKnockoutRounds(int bracketSize) {
        int rounds = 0;
        for (int s = bracketSize; s >= 2; s /= 2) {
            rounds++;
        }
        return rounds;
    }

    /** 标准单败淘汰赛首轮种子对位（1-based 种子号），保证 1/2 号种子分守上下半区。 */
    public static int[][] seedPairs(int bracketSize) {
        return switch (bracketSize) {
            case 2 -> new int[][]{{1, 2}};
            case 4 -> new int[][]{{1, 4}, {2, 3}};
            case 8 -> new int[][]{{1, 8}, {4, 5}, {2, 7}, {3, 6}};
            case 16 -> new int[][]{
                    {1, 16}, {8, 9}, {4, 13}, {5, 12},
                    {2, 15}, {7, 10}, {3, 14}, {6, 11}
            };
            default -> new int[0][0];
        };
    }

    /** 确定性海选排名比较器：积分降序 -> 胜场降序 -> 平局降序 -> 锁定时间升序 -> 队 ID 升序。 */
    public static final Comparator<StandingEntry> STANDING_ORDER = Comparator
            .comparingInt(StandingEntry::qualifierPoints).reversed()
            .thenComparing(Comparator.comparingInt(StandingEntry::qualifierWins).reversed())
            .thenComparing(Comparator.comparingInt(StandingEntry::qualifierDraws).reversed())
            .thenComparingLong(StandingEntry::registeredMs)
            .thenComparing((a, b) -> Long.compareUnsigned(a.squadId(), b.squadId()));

    /** 淘汰赛打满回合判平时四级确定性决胜：返回 0 表示 A 方晋级，1 表示 B 方晋级。 */
    public static int resolveKnockoutDrawWinner(KnockoutTieMetrics a, KnockoutTieMetrics b) {
        if (a.survivingUnits() != b.survivingUnits()) {
            return a.survivingUnits() > b.survivingUnits() ? 0 : 1;
        }
        if (a.remainingHpSum() != b.remainingHpSum()) {
            return Long.compareUnsigned(a.remainingHpSum(), b.remainingHpSum()) > 0 ? 0 : 1;
        }
        if (a.seedRank() != b.seedRank() && a.seedRank() > 0 && b.seedRank() > 0) {
            return a.seedRank() < b.seedRank() ? 0 : 1;
        }
        return Long.compareUnsigned(a.squadId(), b.squadId()) <= 0 ? 0 : 1;
    }

    /** 计算在淘汰赛第 roundIndex(1-based) 轮落败战队的最终名次下界（与 Go 侧完全一致）。 */
    public static int rankForEliminatedRound(int bracketSize, int roundIndex) {
        int total = totalKnockoutRounds(bracketSize);
        if (total == 0 || roundIndex < 1 || roundIndex > total) {
            return 0;
        }
        int remaining = bracketSize;
        for (int r = 1; r < roundIndex; r++) {
            remaining /= 2;
        }
        return remaining / 2 + 1;
    }

    public static byte[] encodeLineup(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new byte[0];
        }
        ByteBuffer buf = ByteBuffer.allocate(ids.size() * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (long id : ids) {
            buf.putLong(id);
        }
        return buf.array();
    }

    public static List<Long> decodeLineup(byte[] raw) {
        if (raw == null || raw.length == 0 || (raw.length % 8) != 0) {
            return List.of();
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int count = raw.length / 8;
        List<Long> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(buf.getLong());
        }
        return out;
    }
}
