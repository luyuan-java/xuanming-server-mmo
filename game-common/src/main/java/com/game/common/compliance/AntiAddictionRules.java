package com.game.common.compliance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 未成年人防沉迷纯规则（对齐 C++/Go {@code shared/antiaddiction}）：
 * 从国家网络游戏防沉迷实名认证系统 {@code pi} 解出生日期、算周岁、可玩时段、充值档位、身份证号与姓名本地预检。
 * 无任何 I/O，供 {@code game-payment}、{@code game-login}、{@code game-gate} 共用。
 */
public final class AntiAddictionRules {

    /** 全部防沉迷规则统一按东八区北京时间计算（无夏令时，不依赖容器 tzdata）。 */
    public static final ZoneOffset BEIJING = ZoneOffset.ofHours(8);

    /** 成年周岁阈值。 */
    public static final int ADULT_AGE = 18;

    /** 国家系统用户唯一标识 pi 长度：前 6 位 26 进制出生日期 + 后 32 位系统编码。 */
    public static final int PI_LENGTH = 38;

    private static final String PI_DIGITS = "0123456789abcdefghijklmnop";
    private static final int PLAY_START_HOUR = 20;
    private static final int PLAY_END_HOUR = 21;
    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final String ID_CHECK_CODES = "10X98765432";

    private AntiAddictionRules() {}

    /**
     * 从 {@code pi} 前 6 位（26 进制编码的 {@code YYYYMMDD}）解出北京出生日期；格式非法或日期无效返回 {@code null}。
     */
    public static LocalDate birthDate(String pi) {
        if (pi == null || pi.length() != PI_LENGTH) {
            return null;
        }
        for (int i = 0; i < pi.length(); i++) {
            char c = pi.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z'))) {
                return null;
            }
        }
        int value = 0;
        for (int i = 0; i < 6; i++) {
            int digit = PI_DIGITS.indexOf(pi.charAt(i));
            if (digit < 0) {
                return null;
            }
            value = value * 26 + digit;
        }
        return dateOf(value / 10000, (value / 100) % 100, value % 100);
    }

    /** 将出生日期编码为 {@code pi} 前 6 位（{@link #birthDate} 的逆运算，供沙箱 mock 与测试构造 pi）。 */
    public static String piBirthPrefix(int year, int month, int day) {
        int value = year * 10000 + month * 100 + day;
        char[] digits = new char[6];
        for (int i = digits.length - 1; i >= 0; i--) {
            digits[i] = PI_DIGITS.charAt(value % 26);
            value /= 26;
        }
        return new String(digits);
    }

    /** 报告 {@code pi} 的格式与出生日期段是否合法。 */
    public static boolean validPi(String pi) {
        return birthDate(pi) != null;
    }

    /** 计算 {@code now} 时刻按北京日期的周岁（生日当天满一岁）。 */
    public static int ageAt(LocalDate birth, Instant now) {
        LocalDate today = now.atOffset(BEIJING).toLocalDate();
        int age = today.getYear() - birth.getYear();
        if (today.getMonthValue() < birth.getMonthValue()
                || (today.getMonthValue() == birth.getMonthValue() && today.getDayOfMonth() < birth.getDayOfMonth())) {
            age--;
        }
        return age;
    }

    /** 北京时间当月 1 日 00:00:00 的 Unix 毫秒。 */
    public static long monthStartMs(Instant now) {
        LocalDate today = now.atOffset(BEIJING).toLocalDate();
        return LocalDate.of(today.getYear(), today.getMonthValue(), 1)
                .atStartOfDay(BEIJING).toInstant().toEpochMilli();
    }

    /** 北京时间当日 00:00:00 的 Unix 毫秒。 */
    public static long dayStartMs(Instant now) {
        LocalDate today = now.atOffset(BEIJING).toLocalDate();
        return today.atStartOfDay(BEIJING).toInstant().toEpochMilli();
    }

    /**
     * 年龄段充值上限（单位：分）。
     * {@code unlimited == true} 时不限；否则 {@code singleFen == 0} 表示不得充值。
     */
    public record SpendLimit(boolean unlimited, long singleFen, long dailyFen, long monthlyFen) {
        public static final SpendLimit UNLIMITED = new SpendLimit(true, 0, 0, 0);
        public static final SpendLimit DENIED = new SpendLimit(false, 0, 0, 0);
    }

    /**
     * 返回指定周岁的充值上限：未满 8 周岁禁止；8–15 单次与单日 50 元、每月 200 元；16–17 单次与单日 100 元、每月 400 元；≥18 不限。
     */
    public static SpendLimit spendLimitAt(int age) {
        if (age >= ADULT_AGE) {
            return SpendLimit.UNLIMITED;
        }
        if (age >= 16) {
            return new SpendLimit(false, 100_00L, 100_00L, 400_00L);
        }
        if (age >= 8) {
            return new SpendLimit(false, 50_00L, 50_00L, 200_00L);
        }
        return SpendLimit.DENIED;
    }

    /**
     * 未成年人可玩日历：周五、周六、周日 + 法定节假日额外可玩日（北京时间），仅 20:00–21:00 可玩。
     */
    public static final class Calendar {
        public static final Calendar DEFAULT = new Calendar(Set.of());
        private final Set<LocalDate> extraDays;

        private Calendar(Set<LocalDate> extraDays) {
            this.extraDays = extraDays;
        }

        /** 解析额外可玩日（严格 {@code YYYY-MM-DD}）；任一条非法抛 {@link IllegalArgumentException}。 */
        public static Calendar of(Collection<String> extraDayStrings) {
            if (extraDayStrings == null || extraDayStrings.isEmpty()) {
                return DEFAULT;
            }
            Set<LocalDate> parsed = new HashSet<>(extraDayStrings.size() * 2);
            for (String day : extraDayStrings) {
                if (day == null) {
                    throw new IllegalArgumentException("invalid extra play day null: want YYYY-MM-DD");
                }
                LocalDate d;
                try {
                    d = LocalDate.parse(day, DateTimeFormatter.ISO_LOCAL_DATE);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("invalid extra play day \"" + day + "\": want YYYY-MM-DD", e);
                }
                if (!d.format(DateTimeFormatter.ISO_LOCAL_DATE).equals(day)) {
                    throw new IllegalArgumentException("invalid extra play day \"" + day + "\": want YYYY-MM-DD");
                }
                parsed.add(d);
            }
            return new Calendar(Set.copyOf(parsed));
        }

        /** 若此刻处于可玩时段（可玩日 20:00–21:00）返回本次可玩截止时刻（当日 21:00）；否则返回 {@code null}。 */
        public Instant playDeadline(Instant now) {
            ZonedDateTime bj = now.atZone(BEIJING);
            LocalDate day = bj.toLocalDate();
            if (!isPlayDay(day)) {
                return null;
            }
            Instant start = day.atTime(PLAY_START_HOUR, 0).toInstant(BEIJING);
            Instant end = day.atTime(PLAY_END_HOUR, 0).toInstant(BEIJING);
            if (now.isBefore(start) || !now.isBefore(end)) {
                return null;
            }
            return end;
        }

        private boolean isPlayDay(LocalDate day) {
            return switch (day.getDayOfWeek()) {
                case FRIDAY, SATURDAY, SUNDAY -> true;
                default -> extraDays.contains(day);
            };
        }
    }

    /** 去掉首尾空白并将末尾校验码 x 规范为大写 X。 */
    public static String normalizeIdNumber(String id) {
        return id == null ? "" : id.strip().toUpperCase(java.util.Locale.ROOT);
    }

    /** 预检 18 位中国居民身份证号（GB 11643 校验码 + 出生日期合法且不晚于当日北京日期）。传入前先调用 {@link #normalizeIdNumber}。 */
    public static boolean validIdNumber(String id, Instant now) {
        if (id == null || id.length() != 18) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char c = id.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            sum += (c - '0') * ID_WEIGHTS[i];
        }
        if (ID_CHECK_CODES.charAt(sum % 11) != id.charAt(17)) {
            return false;
        }
        int year = Integer.parseInt(id.substring(6, 10));
        int month = Integer.parseInt(id.substring(10, 12));
        int day = Integer.parseInt(id.substring(12, 14));
        LocalDate birth = dateOf(year, month, day);
        return birth != null && !birth.isAfter(now.atOffset(BEIJING).toLocalDate());
    }

    /** 预检姓名：去首尾空白后 1–32 个码点且不含控制字符。 */
    public static boolean validName(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        int count = 0;
        for (int i = 0; i < trimmed.length(); ) {
            int cp = trimmed.codePointAt(i);
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.CONTROL) {
                return false;
            }
            count++;
            if (count > 32) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    private static LocalDate dateOf(int year, int month, int day) {
        if (year < 1900 || month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        try {
            return LocalDate.of(year, month, day);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }
}
