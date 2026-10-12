package com.game.match.ticket;

import com.game.common.deadline.Deadline;
import com.game.match.rating.RatingReader;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 票据 HASH（{@code RedisKeys.matchTicket(pid)}）与队列成员在 Redis 里的文本形状（match-spec §9.4），票据存储的 Lua 与 Java 两侧共用这一处定义。
 *
 * <p><b>字段</b>（值全部是 ASCII 文本；数字是十进制）：
 * <table>
 *   <caption>票据 HASH 的字段</caption>
 *   <tr><td>{@value #F_TICKET}</td><td>票号（UUIDv4 小写带连字符）。<b>必有</b>：HASH 存在却没有它 = 数据损坏，读一律按故障抛出</td></tr>
 *   <tr><td>{@value #F_MODE} / {@value #F_CONFIG}</td><td>{@code MatchMode} 的数值 / {@code battle_config_id} 的无符号十进制</td></tr>
 *   <tr><td>{@value #F_STATE}</td><td>{@code queued} / {@code matched} / {@code ready}（{@link TicketState#wire()}）</td></tr>
 *   <tr><td>{@value #F_ENQUEUED_AT_MS}</td><td>建票时刻，Redis {@code TIME} 的 Unix 毫秒；回队首不改</td></tr>
 *   <tr><td>{@value #F_ZONE_ID}</td><td>建票时玩家所在的 zone（只作观测）</td></tr>
 *   <tr><td>{@value #F_QUEUE_KEY}</td><td>入队时的队列键全文；不入队的票为空串</td></tr>
 *   <tr><td>{@value #F_RATING_CENTI}</td><td>建票时的评分 × 100；缺失按 {@link RatingReader#DEFAULT_CENTI}</td></tr>
 *   <tr><td>{@value #F_TEAM_ID}</td><td>整队开战的队伍号；只在非 0 时写</td></tr>
 *   <tr><td>{@value #F_BATTLE_ID}</td><td>置 ready 时写；回队首时删</td></tr>
 *   <tr><td>{@value #F_NOT_BEFORE_MS}</td><td>退避到点时刻（Redis 时间）；只在带退避回队首时写，不带退避回队首时删</td></tr>
 * </table>
 * 字段名在 {@link TicketScripts} 的 Lua 里是字面量（Lua 文本要能单独读懂），{@code TicketScriptsTest} 钉住两边一致。
 *
 * <p><b>队列成员</b>：玩家号的无符号十进制、不带前导零（{@link #member}）。玩家号是 64 位无符号、超出 Lua double 的精度，
 * 所以在脚本里<b>只当字符串</b>比较与传递，从不 {@code tonumber}；队伍号、战斗号同理。
 *
 * <p>解码对数字字段是宽松的（解析不了按 0 / 缺省，同基线 {@code loadTicket} 忽略 {@code strconv} 的错误）：这些字段只有本包的脚本写，
 * 写坏只可能来自人为改数据，而判定用的是票号、状态、队列键三个文本字段。
 */
public final class TicketCodec {

    public static final String F_TICKET = "ticket";
    public static final String F_MODE = "mode";
    public static final String F_CONFIG = "config";
    public static final String F_STATE = "state";
    public static final String F_ENQUEUED_AT_MS = "enqueued_at_ms";
    public static final String F_ZONE_ID = "zone_id";
    public static final String F_QUEUE_KEY = "queue_key";
    public static final String F_RATING_CENTI = "rating_centi";
    public static final String F_TEAM_ID = "team_id";
    public static final String F_BATTLE_ID = "battle_id";
    public static final String F_NOT_BEFORE_MS = "not_before_ms";

    /** 全部字段名（文档与测试用）。 */
    public static final List<String> FIELDS = List.of(F_TICKET, F_MODE, F_CONFIG, F_STATE, F_ENQUEUED_AT_MS, F_ZONE_ID, F_QUEUE_KEY,
            F_RATING_CENTI, F_TEAM_ID, F_BATTLE_ID, F_NOT_BEFORE_MS);

    private TicketCodec() {
    }

    /** 玩家号 → 队列成员串（无符号十进制）。 */
    public static String member(long playerId) {
        return Long.toUnsignedString(playerId);
    }

    /**
     * 队列成员串 → 玩家号。只认规范形：不带符号、不带前导零的非 0 uint64 十进制；其余（空串、{@code "0"}、{@code "007"}、{@code "-5"}、非数字、
     * 超出 uint64）一律为 0 = 非法成员（人为改数据才会出现，凑单用 {@code dropMalformed} 摘掉）。
     */
    public static long parseMember(String member) {
        if (member == null || member.isEmpty() || member.length() > 20 || member.charAt(0) == '0') {
            return 0;
        }
        for (int i = 0; i < member.length(); i++) {
            char c = member.charAt(i);
            if (c < '0' || c > '9') {
                return 0;
            }
        }
        try {
            return Long.parseUnsignedLong(member);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 评分镜像的分数（{@code ZSCORE} 的文本，Redis 按双精度输出）→ centi。空串（镜像里没有这个成员）、解析不了、非有限值为空——
     * 凑单按「镜像缺分」处理（用票里的评分、不回写）。
     */
    public static OptionalLong parseScore(String score) {
        if (score == null || score.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            double value = Double.parseDouble(score);
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(Math.round(value));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * 把一次 {@code HGETALL} 的回复（字段、值交替的扁平列表）解成票据。
     *
     * @param flat     {@code [字段, 值, 字段, 值, …]}；空列表 = 键不存在
     * @param playerId 这张票属于谁（只进报错信息）
     * @return 键不存在为空
     * @throws Deadline.DependencyException HASH 存在却没有票号、或回复的形状不对（数据损坏：绝不折成「没有票」）
     */
    public static Optional<Ticket> decode(List<?> flat, long playerId) {
        if (flat == null) {
            throw new Deadline.DependencyException("票据读回了空回复 player=" + member(playerId));
        }
        if (flat.isEmpty()) {
            return Optional.empty();
        }
        if (flat.size() % 2 != 0) {
            throw new Deadline.DependencyException("票据 HASH 的回复不成对 player=" + member(playerId) + " size=" + flat.size());
        }
        String ticketId = null;
        String mode = null;
        String config = null;
        String state = null;
        String enqueuedAt = null;
        String zone = null;
        String queueKey = null;
        String rating = null;
        String team = null;
        String battle = null;
        String notBefore = null;
        for (int i = 0; i < flat.size(); i += 2) {
            String field = text(flat.get(i));
            String value = text(flat.get(i + 1));
            if (field == null) {
                continue;
            }
            switch (field) {
                case F_TICKET -> ticketId = value;
                case F_MODE -> mode = value;
                case F_CONFIG -> config = value;
                case F_STATE -> state = value;
                case F_ENQUEUED_AT_MS -> enqueuedAt = value;
                case F_ZONE_ID -> zone = value;
                case F_QUEUE_KEY -> queueKey = value;
                case F_RATING_CENTI -> rating = value;
                case F_TEAM_ID -> team = value;
                case F_BATTLE_ID -> battle = value;
                case F_NOT_BEFORE_MS -> notBefore = value;
                default -> {
                    // 不认识的字段（将来的新字段 / 人为添加）：忽略
                }
            }
        }
        if (ticketId == null || ticketId.isEmpty()) {
            throw new Deadline.DependencyException("票据 HASH 损坏：存在但没有票号 player=" + member(playerId));
        }
        return Optional.of(new Ticket(ticketId, signedInt(mode), unsignedInt(config), TicketState.ofWire(state), unsignedLong(enqueuedAt, 0),
                unsignedInt(zone), queueKey == null ? "" : queueKey, signedLong(rating, RatingReader.DEFAULT_CENTI), unsignedLong(team, 0),
                unsignedLong(battle, 0), unsignedLong(notBefore, 0)));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static int signedInt(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int unsignedInt(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseUnsignedInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long signedLong(String value, long fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long unsignedLong(String value, long fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseUnsignedLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
