package com.game.robot;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * travel / travel-abandon / travel-long 子命令（批次 5.4，zone-travel-spec §11.11）的附加选项。取值优先级与 {@link RobotOptions} 相同：
 * 命令行 &gt; 环境变量 &gt; 缺省值；选项名登记在 {@link RobotOptions.Opt}（帮助里列出、{@link RobotOptions#parse} 认得它们），
 * 这里只取这两项并校验（做法同 {@link InstanceOptions}）。出发区与访客区是 {@link RobotOptions#zoneId()} / {@link RobotOptions#visitZoneId()}。
 *
 * @param dwell         到访客区之后的停留（{@code --dwell-ms}，缺省 35 s，<b>可以是 0</b>）：停留期间不该被踢、不该再收到 124。
 *                      缺省取 35 s 是为了越过 30 s 的重连租约
 * @param sceneConfigId 去程 226 指定的地图（{@code --travel-scene-config}，World 表的 {@code scene_config_id}）；0 = 由目标区挑默认主世界。
 *                      非 0 时才分得清「落在请求的地图」与「回落默认主世界」
 */
public record TravelOptions(Duration dwell, int sceneConfigId) {

    static final long MAX_DWELL_MS = 10 * 60 * 1000L;
    private static final List<RobotOptions.Opt> OWN = List.of(RobotOptions.Opt.DWELL, RobotOptions.Opt.TRAVEL_SCENE_CONFIG);

    /**
     * @param args 与交给 {@link RobotOptions#parse} 的同一份命令行（扫描规则相同：{@code --名字 值} 或 {@code --名字=值}，开关可只写名字）
     * @param env  环境变量
     */
    public static TravelOptions parse(List<String> args, Map<String, String> env) throws UsageException {
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

        String dwellText = value(RobotOptions.Opt.DWELL, given, env);
        long dwellMs;
        try {
            dwellMs = Long.parseLong(dwellText);
        } catch (NumberFormatException e) {
            throw new UsageException("--dwell-ms 不是整数毫秒：" + dwellText);
        }
        // 与别的时长选项不同：0 是合法值（不停留）
        if (dwellMs < 0 || dwellMs > MAX_DWELL_MS) {
            throw new UsageException("--dwell-ms 应在 0–" + MAX_DWELL_MS + " 毫秒之间：" + dwellText);
        }
        String sceneText = value(RobotOptions.Opt.TRAVEL_SCENE_CONFIG, given, env);
        int sceneConfigId;
        try {
            sceneConfigId = Integer.parseInt(sceneText);
        } catch (NumberFormatException e) {
            throw new UsageException("--travel-scene-config 不是整数：" + sceneText);
        }
        if (sceneConfigId < 0) {
            throw new UsageException("--travel-scene-config 应 ≥ 0（0 = 由目标区挑默认主世界）：" + sceneText);
        }
        return new TravelOptions(Duration.ofMillis(dwellMs), sceneConfigId);
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
