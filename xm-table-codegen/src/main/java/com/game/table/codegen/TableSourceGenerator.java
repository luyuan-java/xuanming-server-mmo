package com.game.table.codegen;

import com.game.table.codegen.TableSchema.Expression;
import com.game.table.codegen.TableSchema.Field;
import com.game.table.codegen.TableSchema.ForeignKey;
import com.game.table.codegen.TableSchema.Key;
import com.game.table.codegen.TableSchema.Kind;
import com.game.table.codegen.TableSchema.Table;
import com.game.table.codegen.TableSchema.TipRef;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按 {@link TableSchema} 生成 Java 源码：每张表一个 {@code <Sheet>Rows}（不可变的行列表 + 键 / 索引），
 * 外加聚合全部表的 {@code ConfigTables}（加载、完整性校验、外键校验）。
 *
 * <p>生成代码依赖 xm-table 手写的运行时 {@code com.game.table.load}（读 manifest 与数据文件、异常、索引工具），
 * 自身只有直白的字段与循环，便于在 IDE 里读。
 */
final class TableSourceGenerator {

    static final String PROCESSOR = "com.game.table.codegen.ConfigTableProcessor";
    private static final String LOAD_PKG = "com.game.table.load";

    private TableSourceGenerator() {
    }

    /** 全部生成文件：简单类名 → 源码。 */
    static Map<String, String> generate(TableSchema schema) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Table table : schema.tables()) {
            out.put(table.rowsClass(), rowsClass(schema.javaPackage(), table));
        }
        out.put("ConfigTables", configTables(schema));
        return out;
    }

    // ---------------------------------------------------------------- <Sheet>Rows

    static String rowsClass(String pkg, Table t) {
        Src s = new Src();
        String row = t.rowClass();
        Field pk = t.primaryKey();
        s.line("package " + pkg + ";");
        s.line();
        boolean hasExpressions = !t.expressions().isEmpty();
        if (hasExpressions) {
            s.line("import " + LOAD_PKG + ".TableExpression;");
        }
        s.line("import " + LOAD_PKG + ".TableIndexes;");
        s.line("import " + LOAD_PKG + ".TableLoadException;");
        if (hasExpressions) {
            s.line("import java.util.Collections;");
        }
        s.line("import java.util.HashMap;");
        if (hasExpressions) {
            s.line("import java.util.IdentityHashMap;");
        }
        s.line("import java.util.List;");
        s.line("import java.util.Map;");
        s.line("import java.util.NoSuchElementException;");
        s.line("import java.util.Optional;");
        s.line("import java.util.Set;");
        s.line("import java.util.TreeSet;");
        if (hasExpressions) {
            s.line("import java.util.concurrent.ThreadLocalRandom;");
            s.line("import java.util.random.RandomGenerator;");
        }
        s.line("import javax.annotation.processing.Generated;");
        s.line();
        s.line("/**");
        s.line(" * 配置表 {@code " + t.sheet() + "}（源 {@code " + t.sourceFile() + "}，数据文件 {@code " + t.dataFile() + "}）的只读视图。");
        s.line(" *");
        s.line(" * <p>由 xm-table-codegen 按权威 schema 生成，不要手改。实例不可变，随 {@link ConfigTables} 整体加载、整体替换：");
        s.line(" * 调用方长期只持有 id，不长期持有行对象。");
        s.line(" */");
        s.line("@Generated(\"" + PROCESSOR + "\")");
        s.open("public final class " + t.rowsClass());
        s.line("/** 表名（sheet 名）。 */");
        s.line("public static final String SHEET = \"" + t.sheet() + "\";");
        s.line("/** 导表器产物文件名。 */");
        s.line("public static final String DATA_FILE = \"" + t.dataFile() + "\";");
        s.line();
        s.line("private final List<" + row + "> rows;");
        s.line("private final Map<" + pk.kind().boxed + ", " + (t.multiPrimaryKey() ? "List<" + row + ">" : row) + "> byId;");
        for (Key k : t.keys()) {
            s.line("private final Map<" + k.field().kind().boxed + ", " + (k.multi() ? "List<" + row + ">" : row) + "> by"
                    + k.field().pascal() + ";");
        }
        for (Field f : t.indexes()) {
            s.line("private final Map<" + f.kind().boxed + ", List<" + row + ">> by" + f.pascal() + ";");
        }
        for (Expression e : t.expressions()) {
            s.line("/** 表达式列 {@code " + e.field().name() + "} 的参数（cfg_expr_param，按声明顺序）。 */");
            s.line("private static final List<String> " + paramsConstant(e) + " = List.of(" + quotedList(e.params()) + ");");
            s.line("/** 每行预编译好的 {@code " + e.field().name() + "} 公式（按行对象身份查）。 */");
            s.line("private final Map<" + row + ", TableExpression> " + expressionsField(e) + ";");
        }
        s.line();

        // 构造：建键与索引，唯一键重复直接拒绝；表达式列逐行预编译，编译失败直接拒绝。
        s.line("/** @throws TableLoadException 主键重复（主键没有声明 cfg_multi 时）、表达式列编译失败 */");
        s.open(t.rowsClass() + "(List<" + row + "> rows)");
        s.line("this.rows = List.copyOf(rows);");
        s.line("Map<" + pk.kind().boxed + ", " + (t.multiPrimaryKey() ? "List<" + row + ">" : row) + "> byId = new HashMap<>();");
        for (Key k : t.keys()) {
            s.line("Map<" + k.field().kind().boxed + ", " + (k.multi() ? "List<" + row + ">" : row) + "> by" + k.field().pascal()
                    + " = new HashMap<>();");
            if (!k.multi()) {
                s.line("Set<" + k.field().kind().boxed + "> duplicates" + k.field().pascal() + " = new TreeSet<>();");
            }
        }
        for (Field f : t.indexes()) {
            s.line("Map<" + f.kind().boxed + ", List<" + row + ">> by" + f.pascal() + " = new HashMap<>();");
        }
        for (Expression e : t.expressions()) {
            s.line("Map<" + row + ", TableExpression> " + expressionsField(e) + " = new IdentityHashMap<>();");
        }
        s.open("for (" + row + " row : this.rows)");
        for (Expression e : t.expressions()) {
            s.line(expressionsField(e) + ".put(row, TableExpression.compile(SHEET, \"" + e.field().name() + "\", row." + pk.getter()
                    + ", row." + e.field().getter() + ", " + paramsConstant(e) + "));");
        }
        if (t.multiPrimaryKey()) {
            s.line("TableIndexes.add(byId, row." + pk.getter() + ", row);");
        } else {
            s.open("if (byId.putIfAbsent(row." + pk.getter() + ", row) != null)");
            s.line("throw TableLoadException.duplicateKey(SHEET, \"" + pk.name() + "\", row." + pk.getter() + ");");
            s.close();
        }
        for (Key k : t.keys()) {
            String map = "by" + k.field().pascal();
            if (k.multi()) {
                s.line("TableIndexes.add(" + map + ", row." + k.field().getter() + ", row);");
            } else {
                // 导表器只校验主键唯一，不校验二级唯一键：重复时按表序取第一行（与 C++ emplace 一致），并告警。
                s.open("if (" + map + ".putIfAbsent(row." + k.field().getter() + ", row) != null)");
                s.line("duplicates" + k.field().pascal() + ".add(row." + k.field().getter() + ");");
                s.close();
            }
        }
        for (Field f : t.indexes()) {
            String map = "by" + f.pascal();
            if (f.repeated()) {
                s.open("for (" + f.kind().boxed + " key : row." + f.getter() + ")");
                s.line("TableIndexes.add(" + map + ", key, row);");
                s.close();
            } else {
                s.line("TableIndexes.add(" + map + ", row." + f.getter() + ", row);");
            }
        }
        s.close();
        s.line("this.byId = " + (t.multiPrimaryKey() ? "TableIndexes.freeze(byId)" : "Map.copyOf(byId)") + ";");
        for (Key k : t.keys()) {
            String map = "by" + k.field().pascal();
            s.line("this." + map + " = " + (k.multi() ? "TableIndexes.freeze(" + map + ")" : "Map.copyOf(" + map + ")") + ";");
            if (!k.multi()) {
                s.line("TableIndexes.warnDuplicateKeys(SHEET, \"" + k.field().name() + "\", duplicates" + k.field().pascal() + ");");
            }
        }
        for (Field f : t.indexes()) {
            String map = "by" + f.pascal();
            s.line("this." + map + " = TableIndexes.freeze(" + map + ");");
        }
        for (Expression e : t.expressions()) {
            s.line("this." + expressionsField(e) + " = Collections.unmodifiableMap(" + expressionsField(e) + ");");
        }
        s.close();
        s.line();

        s.line("/** 全部行，按数据文件中的顺序（即 Excel 表序）。 */");
        s.open("public List<" + row + "> all()");
        s.line("return rows;");
        s.close();
        s.line();
        s.open("public int size()");
        s.line("return rows.size();");
        s.close();
        s.line();
        String pkType = pk.kind().primitive;
        s.open("public boolean contains(" + pkType + " " + javaParam(pk) + ")");
        s.line("return byId.containsKey(" + javaParam(pk) + ");");
        s.close();
        s.line();
        if (t.multiPrimaryKey()) {
            s.line("/** 该主键命中的全部行（主键声明了 cfg_multi，可重复，所以不提供取单行的方法）；没有时为空列表。 */");
            s.open("public List<" + row + "> findAll(" + pkType + " " + javaParam(pk) + ")");
            s.line("return byId.getOrDefault(" + javaParam(pk) + ", List.of());");
            s.close();
        } else {
            s.open("public Optional<" + row + "> find(" + pkType + " " + javaParam(pk) + ")");
            s.line("return Optional.ofNullable(byId.get(" + javaParam(pk) + "));");
            s.close();
            s.line();
            s.line("/** @throws NoSuchElementException 表里没有这一行 */");
            s.open("public " + row + " get(" + pkType + " " + javaParam(pk) + ")");
            s.line(row + " row = byId.get(" + javaParam(pk) + ");");
            s.open("if (row == null)");
            s.line("throw new NoSuchElementException(\"配置表 " + t.sheet() + " 没有 " + pk.name() + "=\" + " + javaParam(pk) + ");");
            s.close();
            s.line("return row;");
            s.close();
        }
        for (Key k : t.keys()) {
            Field f = k.field();
            s.line();
            if (k.multi()) {
                s.line("/** 多值键 {@code " + f.name() + "} 命中的全部行；没有时为空列表。 */");
                s.open("public List<" + row + "> findAllBy" + f.pascal() + "(" + f.kind().primitive + " key)");
                s.line("return by" + f.pascal() + ".getOrDefault(key, List.of());");
            } else {
                s.line("/** 唯一键 {@code " + f.name() + "}（取值重复时为表序第一行）。 */");
                s.open("public Optional<" + row + "> findBy" + f.pascal() + "(" + f.kind().primitive + " key)");
                s.line("return Optional.ofNullable(by" + f.pascal() + ".get(key));");
            }
            s.close();
        }
        for (Field f : t.indexes()) {
            s.line();
            s.line("/** 索引 {@code " + f.name() + "}" + (f.repeated() ? "（列表里含该值的行）" : "") + "；没有时为空列表。 */");
            s.open("public List<" + row + "> findAllBy" + f.pascal() + "(" + f.kind().primitive + " key)");
            s.line("return by" + f.pascal() + ".getOrDefault(key, List.of());");
            s.close();
        }
        for (Expression e : t.expressions()) {
            expressionAccessors(s, row, e);
        }
        if (!t.expressions().isEmpty()) {
            s.line();
            s.open("private static TableExpression expressionOf(Map<" + row + ", TableExpression> expressions, " + row
                    + " row, String column)");
            s.line("TableExpression expression = expressions.get(row);");
            s.open("if (expression == null)");
            s.line("throw new IllegalArgumentException(\"配置表 \" + SHEET + \" 的行不属于这份快照（表达式列 \" + column + \"）\");");
            s.close();
            s.line("return expression;");
            s.close();
        }
        s.close();
        return s.toString();
    }

    /** 表达式列的两个求值方法：缺省随机源（{@code random()} 用 ThreadLocalRandom）与注入随机源。 */
    private static void expressionAccessors(Src s, String row, Expression e) {
        String method = "eval" + e.field().pascal();
        StringBuilder params = new StringBuilder();
        StringBuilder args = new StringBuilder();
        for (String p : e.params()) {
            String name = javaName(p);
            params.append(", double ").append(name);
            args.append(args.isEmpty() ? "" : ", ").append(name);
        }
        String desc = "表达式列 {@code " + e.field().name() + "}（" + e.resultType()
                + (e.params().isEmpty() ? "，无参数" : "；参数 " + String.join("、", e.params())) + "）";
        s.line();
        s.line("/** " + desc + "：按该行的公式求值（{@code random()} 用 ThreadLocalRandom）。 */");
        s.open("public double " + method + "(" + row + " row" + params + ")");
        s.line("return " + method + "(row" + (args.isEmpty() ? "" : ", " + args) + ", ThreadLocalRandom.current());");
        s.close();
        s.line();
        s.line("/** " + desc + "：按该行的公式求值，{@code random()} 取 {@code random}。 */");
        s.open("public double " + method + "(" + row + " row" + params + ", RandomGenerator random)");
        s.line("return expressionOf(" + expressionsField(e) + ", row, \"" + e.field().name() + "\").evaluate(new double[] {" + args
                + "}, random);");
        s.close();
    }

    private static String paramsConstant(Expression e) {
        return e.field().name().toUpperCase(java.util.Locale.ROOT) + "_PARAMS";
    }

    private static String expressionsField(Expression e) {
        return JavaNames.underscoresToCamelCase(e.field().name(), false) + "Expressions";
    }

    private static String quotedList(java.util.List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            sb.append(sb.isEmpty() ? "" : ", ").append('"').append(v).append('"');
        }
        return sb.toString();
    }

    private static String javaName(String param) {
        String camel = JavaNames.underscoresToCamelCase(param, false);
        return JavaNames.SourceVersionKeywords.isKeyword(camel) || camel.equals("row") || camel.equals("random")
                ? camel + "Value" : camel;
    }

    // ---------------------------------------------------------------- ConfigTables

    static String configTables(TableSchema schema) {
        Src s = new Src();
        s.line("package " + schema.javaPackage() + ";");
        s.line();
        s.line("import " + LOAD_PKG + ".ForeignKeyProblems;");
        s.line("import " + LOAD_PKG + ".TableSource;");
        s.line("import " + LOAD_PKG + ".TipReferenceProblems;");
        s.line("import java.nio.file.Path;");
        s.line("import java.util.HashSet;");
        s.line("import java.util.LinkedHashMap;");
        s.line("import java.util.Map;");
        s.line("import java.util.Set;");
        s.line("import javax.annotation.processing.Generated;");
        s.line();
        s.line("/**");
        s.line(" * 全部配置表的一份不可变快照。由 xm-table-codegen 按权威 schema 生成，不要手改。");
        s.line(" *");
        s.line(" * <p>只能经 {@link #load(Path)} 得到：一次读完全部表并整体校验（数据文件 sha256 与行数对得上 manifest、");
        s.line(" * 主键 / 唯一键不重复、外键都能解析），任何一项不过即抛异常，不返回半成品。");
        s.line(" * 热更 = 加载一份新快照再整体替换引用；同一份快照里的表彼此一致。");
        s.line(" */");
        s.line("@Generated(\"" + TableSourceGenerator.PROCESSOR + "\")");
        s.open("public final class ConfigTables");
        s.line("/** 全部表名（sheet 名），按字母序。 */");
        StringBuilder names = new StringBuilder();
        for (Table t : schema.tables()) {
            names.append(names.isEmpty() ? "" : ", ").append('"').append(t.sheet()).append('"');
        }
        s.line("public static final Set<String> SHEETS = Set.of(" + names + ");");
        s.line("/** 合法 tip 码（同步来的 tip/*.proto 全部枚举值，含 0），升序；表内 tip 引用列按它校验。 */");
        StringBuilder codes = new StringBuilder();
        for (long code : schema.tipCodes()) {
            codes.append(codes.isEmpty() ? "" : ", ").append(code).append('L');
        }
        s.line("private static final long[] TIP_CODES = {" + codes + "};");
        s.line();
        for (Table t : schema.tables()) {
            s.line("private final " + t.rowsClass() + " " + t.accessor() + ";");
        }
        s.line();
        s.open("private ConfigTables(TableSource source)");
        for (Table t : schema.tables()) {
            s.line("this." + t.accessor() + " = new " + t.rowsClass() + "(source.rows(" + t.rowsClass() + ".SHEET, "
                    + t.rowsClass() + ".DATA_FILE, " + t.rowClass() + ".parser()));");
        }
        s.close();
        s.line();
        s.line("/**");
        s.line(" * 从导表器产物目录（含 {@code manifest.json} 与各表 {@code .pb}）加载全部配置表。");
        s.line(" *");
        s.line(" * @throws com.game.table.load.TableLoadException 目录 / manifest / 数据文件不完整、被改动或与 schema 不一致，");
        s.line(" *                                               键重复，外键解析不到");
        s.line(" */");
        s.open("public static ConfigTables load(Path dir)");
        s.line("TableSource source = TableSource.open(dir);");
        s.line("ConfigTables tables = new ConfigTables(source);");
        s.line("source.requireNoUnknownSheets(SHEETS);");
        s.line("ForeignKeyProblems problems = new ForeignKeyProblems();");
        s.line("tables.checkForeignKeys(problems);");
        s.line("problems.throwIfAny();");
        s.line("TipReferenceProblems tipProblems = new TipReferenceProblems(TIP_CODES);");
        s.line("tables.checkTipReferences(tipProblems);");
        s.line("tipProblems.throwIfAny();");
        s.line("return tables;");
        s.close();
        s.line();
        for (Table t : schema.tables()) {
            s.line("/** 配置表 {@code " + t.sheet() + "}。 */");
            s.open("public " + t.rowsClass() + " " + t.accessor() + "()");
            s.line("return " + t.accessor() + ";");
            s.close();
            s.line();
        }
        s.line("/** 表名 → 行数，按表名排序（启动日志用）。 */");
        s.open("public Map<String, Integer> rowCounts()");
        s.line("Map<String, Integer> counts = new LinkedHashMap<>();");
        for (Table t : schema.tables()) {
            s.line("counts.put(" + t.rowsClass() + ".SHEET, " + t.accessor() + ".size());");
        }
        s.line("return counts;");
        s.close();
        s.line();
        foreignKeyChecks(s, schema);
        s.line();
        tipReferenceChecks(s, schema);
        s.close();
        return s.toString();
    }

    private static void tipReferenceChecks(Src s, TableSchema schema) {
        s.line("/**");
        s.line(" * 表内 tip 引用（整型列标了 cfg_tip_ref，或列名含 tip）：每个取值必须是 0 或一个当前活动的 tip 码");
        s.line(" * （同导表器 enum_gen.py validate_tip_references；tip 码轴重排后表里的旧数字不能静默变成未知码）。");
        s.line(" */");
        s.open("private void checkTipReferences(TipReferenceProblems problems)");
        for (Table t : schema.tables()) {
            for (TipRef ref : t.tipRefs()) {
                String label = ref.label(t.sheet());
                String rowKey = "row." + t.primaryKey().getter();
                Field f = ref.field();
                s.open("for (" + t.rowClass() + " row : " + t.accessor() + ".all())");
                if (ref.subField() == null) {
                    emitTipCheck(s, label, rowKey, "row", f);
                } else if (f.repeated()) {
                    s.open("for (" + ref.structType() + " struct : row." + f.getter() + ")");
                    emitTipCheck(s, label, rowKey, "struct", ref.subField());
                    s.close();
                } else {
                    s.open("");
                    s.line(ref.structType() + " struct = row." + f.getter() + ";");
                    emitTipCheck(s, label, rowKey, "struct", ref.subField());
                    s.close();
                }
                s.close();
            }
        }
        s.close();
    }

    private static void emitTipCheck(Src s, String label, String rowKey, String receiver, Field f) {
        if (f.repeated()) {
            s.open("for (" + f.kind().primitive + " value : " + receiver + "." + f.getter() + ")");
            s.line("problems.check(\"" + label + "\", " + rowKey + ", value);");
            s.close();
        } else {
            s.line("problems.check(\"" + label + "\", " + rowKey + ", " + receiver + "." + f.getter() + ");");
        }
    }

    private static void foreignKeyChecks(Src s, TableSchema schema) {
        s.line("/**");
        s.line(" * 外键：每个非空取值（不是 0 / -1 / 空串，与导表器口径一致）都必须能在目标列里找到。");
        s.line(" * 目标列没有任何非空取值（空表）时跳过并告警，同导表器 foreign_key.py（「无法校验」不是「校验失败」）。");
        s.line(" */");
        s.open("private void checkForeignKeys(ForeignKeyProblems problems)");
        for (Table t : schema.tables()) {
            for (ForeignKey fk : t.foreignKeys()) {
                Table target = schema.table(fk.targetSheet()).orElseThrow();
                Field targetField = target.field(fk.targetColumn()).orElseThrow();
                Field f = fk.field();
                Kind kind = f.kind();
                String label = t.sheet() + "." + f.name() + " -> " + target.sheet() + "." + targetField.name();
                s.line("// " + fk.option() + ": " + label);
                s.open("");
                String set = "targetValues";
                s.line("Set<" + kind.boxed + "> " + set + " = new HashSet<>();");
                s.open("for (" + target.rowClass() + " row : " + target.accessor() + ".all())");
                s.line(kind.primitive + " value = row." + targetField.getter() + ";");
                s.open("if (!(" + emptyCheck(kind) + "))");
                s.line(set + ".add(value);");
                s.close();
                s.close();
                s.open("if (" + set + ".isEmpty())");
                s.line("problems.skipEmptyTarget(\"" + label + "\");");
                s.close();
                s.open("else");
                emitFkLoop(s, t, f, set + ".contains(value)", label);
                s.close();
                s.close();
            }
        }
        s.close();
    }

    private static String emptyCheck(Kind kind) {
        return switch (kind) {
            case STRING -> "value.isEmpty()";
            case INT, LONG -> "value == 0 || value == -1";
            default -> throw new IllegalStateException("外键类型不支持: " + kind);
        };
    }

    private static void emitFkLoop(Src s, Table t, Field f, String lookup, String label) {
        Kind kind = f.kind();
        Field pk = t.primaryKey();
        s.open("for (" + t.rowClass() + " row : " + t.accessor() + ".all())");
        if (f.repeated()) {
            s.open("for (" + kind.primitive + " value : row." + f.getter() + ")");
        } else {
            s.open("");
            s.line(kind.primitive + " value = row." + f.getter() + ";");
        }
        s.open("if (!(" + emptyCheck(kind) + ") && !" + lookup + ")");
        s.line("problems.add(\"" + label + "\", row." + pk.getter() + ", value);");
        s.close();
        s.close();
        s.close();
    }

    /** 参数名：主键列名转驼峰（{@code id} → {@code id}）。 */
    private static String javaParam(Field f) {
        String camel = JavaNames.underscoresToCamelCase(f.name(), false);
        return JavaNames.SourceVersionKeywords.isKeyword(camel) ? camel + "Value" : camel;
    }

    /** 极简源码拼接器：四空格缩进，{@code open} / {@code close} 成对出现。 */
    private static final class Src {
        private final StringBuilder sb = new StringBuilder();
        private int depth;

        void line() {
            sb.append('\n');
        }

        void line(String text) {
            sb.append("    ".repeat(depth)).append(text).append('\n');
        }

        /** 开一个块；{@code head} 为空串时开一个裸块 {@code {}}（用来限定局部变量作用域）。 */
        void open(String head) {
            line(head.isEmpty() ? "{" : head + " {");
            depth++;
        }

        void close() {
            depth--;
            line("}");
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
