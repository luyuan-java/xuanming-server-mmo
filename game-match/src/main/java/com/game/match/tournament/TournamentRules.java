package com.game.match.tournament;

import com.game.proto.BattleSettlementData;
import com.game.proto.TournamentLeylineElement;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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
    public static final int MIN_SPECTATE_CHEER_PHRASE_ID = 1;
    public static final int MAX_SPECTATE_CHEER_PHRASE_ID = 6;
    public static final int DEFAULT_INTERMISSION_SEC = 60;
    public static final int MIN_SQUAD_EMBLEM_ID = 1;
    public static final int MAX_SQUAD_EMBLEM_ID = 8;
    public static final int DEFAULT_EMBLEM_ID = 1;
    // V4 模块 J：战队单赛季纯荣誉铭牌位掩码（零战力加成，单调累加）。
    public static final int HONOR_BIT_DEBUT = 1 << 0;     // 问道初成：海选完赛达标
    public static final int HONOR_BIT_STREAK = 1 << 1;    // 连胜破竹：海选累计胜 >= 3
    public static final int HONOR_BIT_UNBEATEN = 1 << 2;  // 不败金身：海选完赛 >= 3 且 0 负
    public static final int HONOR_BIT_COMEBACK = 1 << 3;  // 逆转乾坤：赢下打满 3 局的 BO3 淘汰赛
    public static final int HONOR_BIT_SWEEP = 1 << 4;     // 零封强敌：BO3 淘汰赛 2:0 零封晋级
    public static final int HONOR_BIT_CHAMPION = 1 << 5;  // 问鼎群仙：夺得本赛季总冠军
    private static final ZoneOffset UTC_PLUS_8 = ZoneOffset.ofHours(8);

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

    /** 判定淘汰赛某一轮是否为 BO3（半决赛与总决赛，即该轮参赛队数 remaining <= 4）。 */
    public static int bestOfForKnockout(int bracketSize, int roundIndex, boolean bo3Top4Enabled) {
        if (!bo3Top4Enabled || bracketSize < 2 || roundIndex < 1) {
            return 1;
        }
        int remaining = bracketSize >> (roundIndex - 1);
        return (remaining >= 2 && remaining <= 4) ? 3 : 1;
    }

    private static long splitMix64(long x) {
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    /** 校验五行灵脉枚举是否为 1..5（金/木/水/火/土）。 */
    public static boolean isValidLeylineElement(TournamentLeylineElement e) {
        return e != null
                && e.getNumber() >= TournamentLeylineElement.TOURNAMENT_LEYLINE_METAL_VALUE
                && e.getNumber() <= TournamentLeylineElement.TOURNAMENT_LEYLINE_EARTH_VALUE;
    }

    /** 确定性无放回抽取 BO3 五行灵脉顺序（与 Go 侧 DrawLeylines 逐字节同源）。 */
    public static List<TournamentLeylineElement> drawLeylines(int seasonId, long matchId, int bestOf) {
        return drawLeylinesWithBans(seasonId, matchId, bestOf,
                TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED,
                TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED);
    }

    /** V4 模块 H：剔除双方队长禁选（UNSPECIFIED 表示未禁）后以 SplitMix64 确定性无放回抽取 bestOf 条灵脉（与 Go DrawLeylinesWithBans 逐字节同源）。 */
    public static List<TournamentLeylineElement> drawLeylinesWithBans(
            int seasonId, long matchId, int bestOf,
            TournamentLeylineElement bannedA, TournamentLeylineElement bannedB) {
        if (bestOf <= 1) {
            return List.of();
        }
        TournamentLeylineElement[] all = new TournamentLeylineElement[]{
                TournamentLeylineElement.TOURNAMENT_LEYLINE_METAL,
                TournamentLeylineElement.TOURNAMENT_LEYLINE_WOOD,
                TournamentLeylineElement.TOURNAMENT_LEYLINE_WATER,
                TournamentLeylineElement.TOURNAMENT_LEYLINE_FIRE,
                TournamentLeylineElement.TOURNAMENT_LEYLINE_EARTH
        };
        List<TournamentLeylineElement> poolList = new ArrayList<>(all.length);
        for (TournamentLeylineElement e : all) {
            if ((isValidLeylineElement(bannedA) && e == bannedA) || (isValidLeylineElement(bannedB) && e == bannedB)) {
                continue;
            }
            poolList.add(e);
        }
        TournamentLeylineElement[] pool = poolList.isEmpty()
                ? all
                : poolList.toArray(new TournamentLeylineElement[0]);
        int count = Math.min(bestOf, pool.length);
        long state = (Integer.toUnsignedLong(seasonId) << 32) ^ matchId;
        if (isValidLeylineElement(bannedA) || isValidLeylineElement(bannedB)) {
            int banA = isValidLeylineElement(bannedA) ? bannedA.getNumber() : 0;
            int banB = isValidLeylineElement(bannedB) ? bannedB.getNumber() : 0;
            state ^= (Integer.toUnsignedLong(banA) << 56) ^ (Integer.toUnsignedLong(banB) << 48);
        }
        List<TournamentLeylineElement> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            state = splitMix64(state + (i + 1L));
            int rem = pool.length - i;
            int pick = i + (int) Long.remainderUnsigned(state, rem);
            TournamentLeylineElement tmp = pool[i];
            pool[i] = pool[pick];
            pool[pick] = tmp;
            out.add(pool[i]);
        }
        return List.copyOf(out);
    }

    /** V4 模块 J：海选每场结束后单调累加战队荣耀铭牌（问道初成/连胜破竹/不败金身）。 */
    public static int computeQualifierHonorBits(int existing, int played, int wins, int losses, int minParticipation) {
        int bits = existing;
        int minReq = minParticipation > 0 ? minParticipation : 2;
        if (played >= minReq) {
            bits |= HONOR_BIT_DEBUT;
        }
        if (wins >= 3) {
            bits |= HONOR_BIT_STREAK;
        }
        if (played >= 3 && losses == 0) {
            bits |= HONOR_BIT_UNBEATEN;
        }
        return bits;
    }

    /** V4 模块 J：淘汰赛大场决胜后单调累加胜方战队荣耀铭牌（逆转乾坤/零封强敌/问鼎群仙）。 */
    public static int computeKnockoutSeriesHonorBits(int existing, int bestOf, int winnerWins, int loserWins, boolean isChampion) {
        int bits = existing;
        if (bestOf >= 3) {
            if (winnerWins + loserWins >= 3) {
                bits |= HONOR_BIT_COMEBACK;
            }
            if (winnerWins >= 2 && loserWins == 0) {
                bits |= HONOR_BIT_SWEEP;
            }
        }
        if (isChampion) {
            bits |= HONOR_BIT_CHAMPION;
        }
        return bits;
    }

    public record HighlightClass(int tag, String label, int priority) {
    }

    /** V4 模块 I：为已完赛的公开淘汰赛判定名局录标签与确定性排序优先级（0 最高，与 Go ClassifyHighlightTag 同源）。 */
    public static HighlightClass classifyHighlightTag(
            boolean isThirdPlace, int roundIndex, int maxRound, int bestOf, int winsA, int winsB) {
        if (!isThirdPlace && maxRound > 0 && roundIndex >= maxRound) {
            return new HighlightClass(1, "总决赛巅峰局", 0);
        }
        if (bestOf >= 3 && winsA + winsB >= 3) {
            return new HighlightClass(2, "决胜三局激战", 1);
        }
        if (isThirdPlace) {
            return new HighlightClass(3, "季军争夺战", 2);
        }
        return new HighlightClass(4, "高光问鼎局", 3);
    }

    public static String encodeLeylineSequence(List<TournamentLeylineElement> seq) {
        if (seq == null || seq.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (TournamentLeylineElement e : seq) {
            if (e != null && e != TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED) {
                if (!sb.isEmpty()) {
                    sb.append(',');
                }
                sb.append(e.getNumber());
            }
        }
        return sb.toString();
    }

    public static List<TournamentLeylineElement> decodeLeylineSequence(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.trim().split(",");
        List<TournamentLeylineElement> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            try {
                int v = Integer.parseInt(p.trim());
                if (v >= 1 && v <= 5) {
                    TournamentLeylineElement el = TournamentLeylineElement.forNumber(v);
                    if (el != null) {
                        out.add(el);
                    }
                }
            } catch (NumberFormatException ignored) {
                // 跳过坏项
            }
        }
        return List.copyOf(out);
    }

    public static TournamentLeylineElement leylineForGame(List<TournamentLeylineElement> seq, int gameIndex) {
        if (seq == null || gameIndex < 1 || gameIndex > seq.size()) {
            return TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED;
        }
        return seq.get(gameIndex - 1);
    }

    /** 将 Unix 毫秒转换为 UTC+8 自然日键 YYYYMMDD（每日免费观道致敬重置口径，与 Go Utc8DayKey 完全一致）。 */
    public static int utc8DayKey(long unixMs) {
        ZonedDateTime dt = Instant.ofEpochMilli(unixMs).atZone(UTC_PLUS_8);
        return dt.getYear() * 10000 + dt.getMonthValue() * 100 + dt.getDayOfMonth();
    }

    public static boolean isValidSpectateCheerPhrase(int phraseId) {
        return phraseId >= MIN_SPECTATE_CHEER_PHRASE_ID && phraseId <= MAX_SPECTATE_CHEER_PHRASE_ID;
    }

    public static boolean isValidSpectateCheerSide(int side) {
        return side == 0 || side == 1;
    }

    /** 校验官方预设道家队徽白名单（1..8）。 */
    public static boolean isValidEmblemId(int id) {
        return id >= MIN_SQUAD_EMBLEM_ID && id <= MAX_SQUAD_EMBLEM_ID;
    }

    /** 建队时将 0 归一化为默认 1 青剑；非 0 须落在 1..8，否则返回 empty。 */
    public static Optional<Integer> normalizeEmblemId(int id) {
        if (id == 0) {
            return Optional.of(DEFAULT_EMBLEM_ID);
        }
        return isValidEmblemId(id) ? Optional.of(id) : Optional.empty();
    }

    /** 返回淘汰赛命中神机预测对应的慧眼分权重：16强=1，8强=2，半决赛=3，季军战=3，总决赛=5。 */
    public static int predictionWeightForRound(int bracketSize, int roundIndex, boolean isThirdPlace) {
        if (isThirdPlace) {
            return 3;
        }
        int total = totalKnockoutRounds(bracketSize);
        if (total == 0 || roundIndex < 1 || roundIndex > total) {
            return 1;
        }
        int remaining = bracketSize >> (roundIndex - 1);
        return switch (remaining) {
            case 2 -> 5;
            case 4 -> 3;
            case 8 -> 2;
            default -> 1;
        };
    }

    public record MatchMvpResult(long playerId, int teamIndex, int score, long damage, long healing, int controls) {
    }

    /** 基于 BattleSettlementData 权威累计统计确定性评定本局 MVP（与 Go ComputeMatchMvp 逐条同构）。 */
    public static Optional<MatchMvpResult> computeMatchMvp(List<BattleSettlementData> settlements, int winnerTeamIndex) {
        if (settlements == null || settlements.isEmpty()) {
            return Optional.empty();
        }
        MatchMvpResult best = null;
        boolean bestWin = false;
        boolean bestAlive = false;
        long bestContrib = 0L;
        for (BattleSettlementData s : settlements) {
            if (s == null || s.getPlayerId() == 0L) {
                continue;
            }
            long dmg = s.getTotalDamageDealt();
            long heal = s.getTotalHealingDone();
            int ctrl = s.getTotalControlsLanded();
            boolean alive = !s.getIsDead() && !s.getFled();
            boolean onWin = winnerTeamIndex >= 0 && s.getPlayerTeamIndex() == winnerTeamIndex;
            long raw = Long.divideUnsigned(dmg, 100L)
                    + Long.divideUnsigned(heal, 80L)
                    + Integer.toUnsignedLong(ctrl) * 60L
                    + (alive ? 120L : 0L)
                    + (onWin ? 180L : 0L);
            if (Long.compareUnsigned(raw, 100_000L) > 0) {
                raw = 100_000L;
            }
            int score = (int) raw;
            long contrib = dmg + heal;
            MatchMvpResult cand = new MatchMvpResult(s.getPlayerId(), s.getPlayerTeamIndex(), score, dmg, heal, ctrl);
            boolean better = best == null
                    || Integer.compareUnsigned(cand.score(), best.score()) > 0
                    || (cand.score() == best.score() && Long.compareUnsigned(contrib, bestContrib) > 0)
                    || (cand.score() == best.score() && contrib == bestContrib && onWin && !bestWin)
                    || (cand.score() == best.score() && contrib == bestContrib && onWin == bestWin && alive && !bestAlive)
                    || (cand.score() == best.score() && contrib == bestContrib && onWin == bestWin && alive == bestAlive
                        && Long.compareUnsigned(cand.playerId(), best.playerId()) < 0);
            if (better) {
                best = cand;
                bestWin = onWin;
                bestAlive = alive;
                bestContrib = contrib;
            }
        }
        return Optional.ofNullable(best);
    }
}
