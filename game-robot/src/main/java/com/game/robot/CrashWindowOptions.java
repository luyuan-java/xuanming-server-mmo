package com.game.robot;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * battle-settle 故障变体（scene-battle-spec §13.8「故障变体」）的附加选项。取值优先级与 {@link RobotOptions} 相同：命令行 &gt; 环境变量 &gt; 缺省值；
 * 选项名登记在 {@link RobotOptions.Opt}（帮助里列出、{@link RobotOptions#parse} 认得它们），这里只取这三项并校验（做法同 {@link InstanceOptions}）。
 *
 * <p>robot 只是客户端：kill -9 与重启由 {@code tools/local/battle-crash-window.sh} 做。一次演练分两次调用 robot：
 * {@link Phase#ARM} 打到断点、在断点处写出状态文件（脚本等它出现就杀进程）；{@link Phase#VERIFY} 在进程重启之后读状态文件、重登核对。
 *
 * @param variant   哪个崩溃窗口；{@link Variant#NONE} = 不是故障变体，跑完整的 battle-settle
 * @param phase     阶段
 * @param stateFile 状态文件（相对路径按 robot 的工作目录，即仓库根目录）
 */
public record CrashWindowOptions(Variant variant, Phase phase, Path stateFile) {

    /** 崩溃窗口。命令行写法是小写加连字符（{@link #wire}）。 */
    public enum Variant {
        NONE("none"),
        /** 大厅收到 150 之后立即 kill scene、重启、重登 → 金币恰好增一次、150 至多一份。 */
        SCENE_AFTER_150("scene-after-150"),
        /** 结算落库（SET）之后、大厅 150 之前 kill battle → 期限 + 10 s 时 scene 的 rescue 到账（D21）。 */
        BATTLE_AFTER_STORE("battle-after-store");

        private final String wire;

        Variant(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        /** 按命令行写法找；不认识返回 null。 */
        public static Variant ofWire(String text) {
            for (Variant v : values()) {
                if (v.wire.equals(text)) {
                    return v;
                }
            }
            return null;
        }
    }

    public enum Phase {
        ARM, VERIFY;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** 按命令行写法找；不认识返回 null。 */
        public static Phase ofWire(String text) {
            for (Phase p : values()) {
                if (p.wire().equals(text)) {
                    return p;
                }
            }
            return null;
        }
    }

    private static final List<RobotOptions.Opt> OWN = List.of(RobotOptions.Opt.CRASH_WINDOW, RobotOptions.Opt.CRASH_PHASE,
            RobotOptions.Opt.CRASH_STATE);

    /** 是不是故障变体（不是就跑完整的 battle-settle）。 */
    public boolean enabled() {
        return variant != Variant.NONE;
    }

    /**
     * @param args 与交给 {@link RobotOptions#parse} 的同一份命令行（扫描规则相同：{@code --名字 值} 或 {@code --名字=值}，开关可只写名字）
     * @param env  环境变量
     */
    public static CrashWindowOptions parse(List<String> args, Map<String, String> env) throws UsageException {
        Map<RobotOptions.Opt, String> given = new EnumMap<>(RobotOptions.Opt.class);
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (!arg.startsWith("--")) {
                continue;
            }
            String name = arg.substring(2);
            String value = null;
            int eq = name.indexOf('=');
            if (eq >= 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            }
            RobotOptions.Opt opt = byArg(name);
            if (value == null && opt.isFlag()) {
                boolean explicit = i + 1 < args.size() && (args.get(i + 1).equals("true") || args.get(i + 1).equals("false"));
                value = explicit ? args.get(++i) : "true";
            }
            if (value == null) {
                if (i + 1 >= args.size()) {
                    throw new UsageException("选项 --" + name + " 缺少取值");
                }
                value = args.get(++i);
            }
            if (OWN.contains(opt)) {
                given.put(opt, value);
            }
        }

        String variantText = value(RobotOptions.Opt.CRASH_WINDOW, given, env);
        Variant variant = Variant.ofWire(variantText);
        if (variant == null) {
            throw new UsageException("--crash-window 只能是 none / scene-after-150 / battle-after-store：" + variantText);
        }
        String phaseText = value(RobotOptions.Opt.CRASH_PHASE, given, env);
        Phase phase = Phase.ofWire(phaseText);
        if (phase == null) {
            throw new UsageException("--crash-phase 只能是 arm / verify：" + phaseText);
        }
        String stateText = value(RobotOptions.Opt.CRASH_STATE, given, env);
        Path stateFile;
        try {
            stateFile = Path.of(stateText);
        } catch (InvalidPathException e) {
            throw new UsageException("--crash-state 不是合法路径：" + stateText);
        }
        if (stateFile.getFileName() == null) {
            throw new UsageException("--crash-state 要是一个文件路径：" + stateText);
        }
        return new CrashWindowOptions(variant, phase, stateFile);
    }

    private static RobotOptions.Opt byArg(String name) throws UsageException {
        for (RobotOptions.Opt opt : RobotOptions.Opt.values()) {
            if (opt.arg.equals(name)) {
                return opt;
            }
        }
        throw new UsageException("未知选项：--" + name);
    }

    private static String value(RobotOptions.Opt opt, Map<RobotOptions.Opt, String> given, Map<String, String> env) {
        String value = given.get(opt);
        if (value == null) {
            value = env.get(opt.env);
        }
        if (value == null || value.isBlank()) {
            value = opt.defaultValue;
        }
        return value.strip();
    }
}
