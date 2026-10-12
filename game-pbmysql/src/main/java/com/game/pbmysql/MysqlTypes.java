package com.game.pbmysql;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 线上列类型（information_schema.COLUMNS.COLUMN_TYPE）与 proto 目标列类型的兼容性判定，逐条对齐 Go 版
 * parseMySQLType / isTypeMatch / narrowingSuppressed / alignedColumnType / isRenameConvertible。
 *
 * <p>口径统一为「线上装得下目标就不动它」：线上更宽 → 兼容（不收窄）；线上更窄 → 需要拓宽；跨族（int ↔ mediumtext）
 * 与整数有无符号不同 → 不兼容且不能自动转换。
 */
final class MysqlTypes {

    /** 解析后的类型：基础类型名（小写）、括号里的长度 / 小数位、是否 unsigned。 */
    record TypeInfo(String baseType, int length, int decimal, boolean unsigned) {
    }

    private record TypeFamily(Map<String, Integer> ranks, boolean integer) {
    }

    /** 等价写法折叠到同一个名字再比较。 */
    private static final Map<String, String> BASE_TYPE_ALIASES = Map.ofEntries(
            Map.entry("bool", "tinyint"),
            Map.entry("integer", "int"),
            Map.entry("mediumtext", "mediumtext"),
            Map.entry("text", "text"),
            Map.entry("blob", "blob"),
            Map.entry("mediumblob", "mediumblob"),
            Map.entry("datetime", "datetime"),
            Map.entry("timestamp", "datetime"),
            Map.entry("varchar", "varchar"),
            Map.entry("char", "char"));

    /** 同族容量阶梯；族内按 rank 比宽窄，跨族不可比。 */
    private static final List<TypeFamily> FAMILIES = List.of(
            new TypeFamily(Map.of("tinyint", 1, "smallint", 2, "mediumint", 3, "int", 4, "bigint", 5), true),
            new TypeFamily(Map.of("char", 1, "varchar", 2, "tinytext", 3, "text", 4, "mediumtext", 5, "longtext", 6), false),
            new TypeFamily(Map.of("binary", 1, "varbinary", 2, "tinyblob", 3, "blob", 4, "mediumblob", 5, "longblob", 6), false),
            new TypeFamily(Map.of("float", 1, "double", 2), false));

    private MysqlTypes() {
    }

    /** 同 Go parseMySQLType：按空白切分，第一段是基础类型（可带括号参数），其后出现 unsigned 即无符号。 */
    static TypeInfo parse(String columnType) {
        String[] parts = columnType.toLowerCase(Locale.ROOT).trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return new TypeInfo("", 0, 0, false);
        }
        String basePart = parts[0];
        String baseType = basePart;
        int length = 0;
        int decimal = 0;
        int paren = basePart.indexOf('(');
        if (paren != -1) {
            baseType = basePart.substring(0, paren);
            String params = trimParens(basePart.substring(paren));
            if (params.contains(",")) {
                String[] p = params.split(",", -1);
                length = atoi(p[0].trim());
                if (p.length >= 2) {
                    decimal = atoi(p[1].trim());
                }
            } else {
                length = atoi(params);
            }
        }
        boolean unsigned = false;
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].equals("unsigned")) {
                unsigned = true;
                break;
            }
        }
        return new TypeInfo(baseType, length, decimal, unsigned);
    }

    /** 线上类型 current 能否原样承接 proto 目标类型 target（不需要 ALTER）。 */
    static boolean isTypeMatch(String currentType, String targetType) {
        TypeInfo current = parse(currentType);
        TypeInfo target = parse(targetType);
        String currentBase = normalizeBaseType(current.baseType());
        String targetBase = normalizeBaseType(target.baseType());

        if (!currentBase.equals(targetBase)) {
            for (TypeFamily family : FAMILIES) {
                Integer currentRank = family.ranks().get(currentBase);
                Integer targetRank = family.ranks().get(targetBase);
                if (currentRank == null || targetRank == null) {
                    continue;
                }
                if (family.integer() && current.unsigned() != target.unsigned()) {
                    return false;
                }
                return currentRank >= targetRank;
            }
            return false;
        }

        return switch (currentBase) {
            case "varchar", "char", "varbinary", "binary" -> current.length() >= target.length();
            case "tinyint", "smallint", "mediumint", "int", "bigint" -> current.unsigned() == target.unsigned();
            case "float", "double" -> current.decimal() >= target.decimal();
            case "datetime" -> current.length() >= target.length();
            default -> true;
        };
    }

    /** 两侧都是整数族、但有无符号不同：值域互不包含，任何方向的自动 ALTER 都可能丢数据。 */
    static boolean unsafeIntegerSignednessChange(String currentType, String targetType) {
        TypeInfo current = parse(currentType);
        TypeInfo target = parse(targetType);
        String currentBase = normalizeBaseType(current.baseType());
        String targetBase = normalizeBaseType(target.baseType());
        for (TypeFamily family : FAMILIES) {
            if (!family.integer()) {
                continue;
            }
            return family.ranks().containsKey(currentBase) && family.ranks().containsKey(targetBase)
                    && current.unsigned() != target.unsigned();
        }
        return false;
    }

    /** 本次「判为兼容」是不是因为挡下了一次收窄（类型确实不同，且线上那一侧更宽）；只用于留痕。 */
    static boolean narrowingSuppressed(String currentType, String targetType) {
        TypeInfo current = parse(currentType);
        TypeInfo target = parse(targetType);
        String currentBase = normalizeBaseType(current.baseType());
        String targetBase = normalizeBaseType(target.baseType());
        if (!currentBase.equals(targetBase)) {
            for (TypeFamily family : FAMILIES) {
                Integer c = family.ranks().get(currentBase);
                Integer t = family.ranks().get(targetBase);
                if (c == null || t == null) {
                    continue;
                }
                if (family.integer() && current.unsigned() != target.unsigned()) {
                    return false;
                }
                return c > t;
            }
            return false;
        }
        return switch (currentBase) {
            case "varchar", "char", "varbinary", "binary", "datetime" -> current.length() > target.length();
            case "float", "double" -> current.decimal() > target.decimal();
            default -> false;
        };
    }

    /**
     * 一条对齐语句里该写哪个列类型：默认是 proto 目标类型；目标比线上窄（或有无符号不同）时保留线上的类型本体，
     * 只带上目标的属性（NOT NULL / DEFAULT / AUTO_INCREMENT 是 proto 侧的决定）。
     */
    static String alignedColumnType(String currentType, String targetType) {
        if (unsafeIntegerSignednessChange(currentType, targetType)) {
            return replaceColumnBaseType(targetType, currentType);
        }
        if (!narrowingSuppressed(currentType, targetType)) {
            return targetType;
        }
        return replaceColumnBaseType(targetType, currentType);
    }

    /** 把列定义 targetType 的类型本体换成 baseSpec，其余属性原样保留（目标紧跟本体的 unsigned 一并丢掉）。 */
    static String replaceColumnBaseType(String targetType, String baseSpec) {
        String trimmed = targetType.trim();
        if (trimmed.isEmpty()) {
            return baseSpec;
        }
        String[] parts = trimmed.split("\\s+");
        int from = 1;
        if (parts.length > 1 && parts[1].equalsIgnoreCase("unsigned")) {
            from = 2;
        }
        if (from >= parts.length) {
            return baseSpec;
        }
        return baseSpec + " " + String.join(" ", java.util.Arrays.asList(parts).subList(from, parts.length));
    }

    /** 线上旧列的类型能否安全承接改名后的新类型：同基础类型或同族才算（跨族多半是字段号被复用）。 */
    static boolean isRenameConvertible(String currentType, String targetType) {
        String currentBase = normalizeBaseType(parse(currentType).baseType());
        String targetBase = normalizeBaseType(parse(targetType).baseType());
        if (currentBase.equals(targetBase)) {
            return true;
        }
        for (TypeFamily family : FAMILIES) {
            if (family.ranks().containsKey(currentBase) && family.ranks().containsKey(targetBase)) {
                return true;
            }
        }
        return false;
    }

    static String normalizeBaseType(String baseType) {
        return BASE_TYPE_ALIASES.getOrDefault(baseType, baseType);
    }

    /** 同 Go {@code strings.Trim(s, "()")}：去掉首尾所有括号字符。 */
    private static String trimParens(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == '(' || s.charAt(start) == ')')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == '(' || s.charAt(end - 1) == ')')) {
            end--;
        }
        return s.substring(start, end);
    }

    /** 同 Go {@code strconv.Atoi} 出错时取 0 的用法。 */
    private static int atoi(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
