package com.game.table.codegen;

import java.util.Set;

/**
 * 与 protoc Java 生成器一致的命名规则：生成代码要调用 protoc 生成的 getter，名字必须逐字一致。
 *
 * <p>出处：protobuf {@code src/google/protobuf/compiler/java/names.cc} 的 {@code UnderscoresToCamelCase}
 * 与禁用字段名表（与 {@code Object} / {@code MessageOrBuilder} 方法撞名的字段，protoc 在名字后加下划线）。
 * 同一消息内的访问器冲突（{@code x} 与 {@code x_count} 等）protoc 会追加字段号，这一条不在这里算，
 * 由注解处理器对照 protoc 实际生成的方法校正（{@code ConfigTableProcessor#resolveAccessors}）。
 */
final class JavaNames {

    /** protoc 的禁用词（UpperCamelCase，与驼峰后的字段名<b>精确</b>比较，同 protoc {@code IsForbidden}）。 */
    private static final Set<String> FORBIDDEN = Set.of(
            "Class",
            "DefaultInstanceForType",
            "ParserForType",
            "SerializedSize",
            "AllFields",
            "DescriptorForType",
            "InitializationErrorString",
            "UnknownFields",
            "CachedSize");

    private JavaNames() {
    }

    /** protoc 的 {@code UnderscoresToCamelCase}：字母前有下划线或数字时大写，首字母按 {@code capitalizeFirst} 决定。 */
    static String underscoresToCamelCase(String input, boolean capitalizeFirst) {
        StringBuilder out = new StringBuilder(input.length());
        boolean capNext = capitalizeFirst;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= 'a' && c <= 'z') {
                out.append(capNext ? (char) (c - 'a' + 'A') : c);
                capNext = false;
            } else if (c >= 'A' && c <= 'Z') {
                if (i == 0 && !capNext) {
                    out.append((char) (c - 'A' + 'a'));
                } else {
                    out.append(c);
                }
                capNext = false;
            } else if (c >= '0' && c <= '9') {
                out.append(c);
                capNext = true;
            } else {
                capNext = true;
            }
        }
        return out.toString();
    }

    /** 字段访问器里 {@code get} 之后的部分：{@code skill_type} → {@code SkillType}，{@code class} → {@code Class_}。 */
    static String capitalizedFieldName(String fieldName) {
        String camel = underscoresToCamelCase(fieldName, true);
        if (FORBIDDEN.contains(camel)) {
            return camel + "_";
        }
        return camel;
    }

    /** 表名（sheet，PascalCase）→ {@code ConfigTables} 上的访问器名：{@code TestMultiKey} → {@code testMultiKey}。 */
    static String accessorName(String sheet) {
        if (sheet.isEmpty()) {
            throw new IllegalArgumentException("表名为空");
        }
        String name = Character.toLowerCase(sheet.charAt(0)) + sheet.substring(1);
        return SourceVersionKeywords.isKeyword(name) ? name + "Table" : name;
    }

    /** Java 关键字（访问器名不能撞上）。 */
    static final class SourceVersionKeywords {
        private static final Set<String> KEYWORDS = Set.of(
                "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
                "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
                "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
                "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
                "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
                "volatile", "while", "true", "false", "null", "var", "record", "yield");

        private SourceVersionKeywords() {
        }

        static boolean isKeyword(String name) {
            return KEYWORDS.contains(name);
        }
    }
}
