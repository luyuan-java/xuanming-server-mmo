package com.game.table.load;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * 配置表表达式列（{@code cfg_expr_type} / {@code cfg_expr_param}）的一条公式：加载时编译成不可变的语法树，求值无状态、线程安全。
 *
 * <p>基线是 C++ 的 exprtk（{@code cpp/libs/engine/config/table_expression.h}）；Go 服务不支持表达式列。这里手写一个小型递归下降编译器，
 * 只收现有数据与策划约定用到的子集：数字字面量（含小数与指数）、声明过的参数名、{@code + - * / %}（浮点取余，同 C fmod）、
 * {@code ^}（乘方，右结合，优先级高于一元负号：{@code -2^2 = -4}）、括号、一元正负号、白名单函数
 * {@code min / max}（≥ 1 个参数）、{@code abs / floor / ceil}（1 个参数）、{@code random()}（[0, 1)，由调用方注入随机源，不用全局随机）。
 * 与基线的差别（PARITY「配置表表达式列」行）：编译失败、引用未声明的名字都在加载期失败（基线不检查，返回值未定义）；
 * 每次求值不再重新编译、也没有「先设参数再取值」的共享可变状态。空串（trim 后）按常量 0。
 */
public final class TableExpression {

    private final String source;
    private final Node root;
    private final int paramCount;

    private TableExpression(String source, Node root, int paramCount) {
        this.source = source;
        this.root = root;
        this.paramCount = paramCount;
    }

    /**
     * 编译一行的公式。
     *
     * @param sheet  表名（只用于报错）
     * @param column 列名（只用于报错）
     * @param rowKey 该行主键（只用于报错）
     * @param params 声明的参数名（{@code cfg_expr_param}，按声明顺序）
     * @throws TableLoadException 语法错误、引用未声明的参数或未知函数、函数参数个数不对
     */
    public static TableExpression compile(String sheet, String column, Object rowKey, String source, List<String> params) {
        String text = source == null ? "" : source.strip();
        if (text.isEmpty()) {
            return new TableExpression(text, new Const(0), params.size());
        }
        try {
            Parser p = new Parser(text, params);
            Node root = p.parseExpression();
            p.skipSpaces();
            if (!p.atEnd()) {
                throw p.error("多余的内容");
            }
            return new TableExpression(text, root, params.size());
        } catch (ParseException e) {
            throw new TableLoadException("配置表 " + sheet + " 的表达式列 " + column + "（行 " + rowKey + "）编译失败：" + e.getMessage()
                    + "，公式 \"" + text + "\"");
        }
    }

    /** 按声明顺序传参求值；公式里用到 {@code random()} 时取 {@code random}（不得为 null）。 */
    public double evaluate(double[] args, RandomGenerator random) {
        if (args.length != paramCount) {
            throw new IllegalArgumentException("表达式 \"" + source + "\" 需要 " + paramCount + " 个参数，实际 " + args.length);
        }
        return root.eval(args, random);
    }

    public String source() {
        return source;
    }

    @Override
    public String toString() {
        return source;
    }

    // ---------------------------------------------------------------- 语法树

    private sealed interface Node permits Const, Var, Neg, Binary, Call {
        double eval(double[] args, RandomGenerator random);
    }

    private record Const(double value) implements Node {
        @Override
        public double eval(double[] args, RandomGenerator random) {
            return value;
        }
    }

    private record Var(int index) implements Node {
        @Override
        public double eval(double[] args, RandomGenerator random) {
            return args[index];
        }
    }

    private record Neg(Node operand) implements Node {
        @Override
        public double eval(double[] args, RandomGenerator random) {
            return -operand.eval(args, random);
        }
    }

    private record Binary(char op, Node left, Node right) implements Node {
        @Override
        public double eval(double[] args, RandomGenerator random) {
            double a = left.eval(args, random);
            double b = right.eval(args, random);
            return switch (op) {
                case '+' -> a + b;
                case '-' -> a - b;
                case '*' -> a * b;
                case '/' -> a / b;
                case '%' -> a % b;
                case '^' -> Math.pow(a, b);
                default -> throw new IllegalStateException("未知运算符 " + op);
            };
        }
    }

    private record Call(String name, List<Node> args) implements Node {
        @Override
        public double eval(double[] values, RandomGenerator random) {
            return switch (name) {
                case "random" -> random.nextDouble();
                case "abs" -> Math.abs(args.get(0).eval(values, random));
                case "floor" -> Math.floor(args.get(0).eval(values, random));
                case "ceil" -> Math.ceil(args.get(0).eval(values, random));
                case "min", "max" -> {
                    double acc = args.get(0).eval(values, random);
                    for (int i = 1; i < args.size(); i++) {
                        double v = args.get(i).eval(values, random);
                        acc = name.equals("min") ? Math.min(acc, v) : Math.max(acc, v);
                    }
                    yield acc;
                }
                default -> throw new IllegalStateException("未知函数 " + name);
            };
        }
    }

    // ---------------------------------------------------------------- 递归下降
    //   expression := term (('+' | '-') term)*
    //   term       := unary (('*' | '/' | '%') unary)*
    //   unary      := ('+' | '-') unary | power
    //   power      := primary ('^' unary)?          （右结合）
    //   primary    := number | name | name '(' [expression (',' expression)*] ')' | '(' expression ')'

    private static final class ParseException extends Exception {
        ParseException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private final String text;
        private final List<String> params;
        private int pos;

        Parser(String text, List<String> params) {
            this.text = text;
            this.params = params;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipSpaces() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        boolean accept(char c) {
            skipSpaces();
            if (pos < text.length() && text.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        ParseException error(String what) {
            return new ParseException(what + "（第 " + (pos + 1) + " 个字符）");
        }

        Node parseExpression() throws ParseException {
            Node left = parseTerm();
            while (true) {
                if (accept('+')) {
                    left = new Binary('+', left, parseTerm());
                } else if (accept('-')) {
                    left = new Binary('-', left, parseTerm());
                } else {
                    return left;
                }
            }
        }

        Node parseTerm() throws ParseException {
            Node left = parseUnary();
            while (true) {
                if (accept('*')) {
                    left = new Binary('*', left, parseUnary());
                } else if (accept('/')) {
                    left = new Binary('/', left, parseUnary());
                } else if (accept('%')) {
                    left = new Binary('%', left, parseUnary());
                } else {
                    return left;
                }
            }
        }

        Node parseUnary() throws ParseException {
            if (accept('-')) {
                return new Neg(parseUnary());
            }
            if (accept('+')) {
                return parseUnary();
            }
            return parsePower();
        }

        Node parsePower() throws ParseException {
            Node base = parsePrimary();
            if (accept('^')) {
                return new Binary('^', base, parseUnary());
            }
            return base;
        }

        Node parsePrimary() throws ParseException {
            skipSpaces();
            if (atEnd()) {
                throw error("公式不完整");
            }
            char c = text.charAt(pos);
            if (accept('(')) {
                Node inner = parseExpression();
                if (!accept(')')) {
                    throw error("缺右括号");
                }
                return inner;
            }
            if (Character.isDigit(c) || c == '.') {
                return parseNumber();
            }
            if (Character.isLetter(c) || c == '_') {
                String name = parseName();
                if (accept('(')) {
                    return parseCall(name);
                }
                int index = params.indexOf(name);
                if (index < 0) {
                    throw error("引用了未声明的参数 " + name + "（声明的参数 " + params + "）");
                }
                return new Var(index);
            }
            throw error("意外的字符 '" + c + "'");
        }

        Node parseNumber() throws ParseException {
            int start = pos;
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
                pos++;
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                int save = pos;
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                if (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                        pos++;
                    }
                } else {
                    pos = save;
                }
            }
            String literal = text.substring(start, pos);
            try {
                double value = Double.parseDouble(literal);
                if (!Double.isFinite(value)) {
                    throw error("数字越界 " + literal);
                }
                return new Const(value);
            } catch (NumberFormatException e) {
                throw error("不是合法的数字 " + literal);
            }
        }

        String parseName() {
            int start = pos;
            while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
                pos++;
            }
            return text.substring(start, pos);
        }

        Node parseCall(String rawName) throws ParseException {
            String name = rawName.toLowerCase(Locale.ROOT);
            List<Node> args = new ArrayList<>();
            if (!accept(')')) {
                do {
                    args.add(parseExpression());
                } while (accept(','));
                if (!accept(')')) {
                    throw error("函数 " + rawName + " 缺右括号");
                }
            }
            int n = args.size();
            boolean ok = switch (name) {
                case "random" -> n == 0;
                case "abs", "floor", "ceil" -> n == 1;
                case "min", "max" -> n >= 1;
                default -> throw error("未知函数 " + rawName);
            };
            if (!ok) {
                throw error("函数 " + rawName + " 的参数个数不对（" + n + " 个）");
            }
            return new Call(name, List.copyOf(args));
        }
    }
}
