package com.game.robot;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * mirror / dungeon 子命令（批次 5.3，dungeon-mirror-spec §12.6）的附加选项。取值优先级与 {@link RobotOptions} 相同：命令行 &gt; 环境变量 &gt; 缺省值；
 * 选项名登记在 {@link RobotOptions.Opt}（帮助里列出、{@link RobotOptions#parse} 认得它们），这里只取这几项并校验。
 *
 * @param mirrorConfigId         建镜像用的 Mirror 表 id（≥ 1）
 * @param instanceWait           等空置镜像被回收的上限
 * @param strictMirrorValidation 表外 mirror_config_id 期望同步 3005（true，Java D7 / Q1）还是 {@code {0}} + 79（false）
 * @param sceneAdminUrl          xm-scene 管理端口（dev 实例管理口与该节点的指标）
 * @param sceneManagerMetricsUrl xm-scene-manager 管理端口（抓指标）
 */
public record InstanceOptions(int mirrorConfigId, Duration instanceWait, boolean strictMirrorValidation, String sceneAdminUrl,
                              String sceneManagerMetricsUrl) {

    private static final long MAX_WAIT_MS = 10 * 60 * 1000L;
    private static final List<RobotOptions.Opt> OWN = List.of(RobotOptions.Opt.MIRROR_CONFIG_ID, RobotOptions.Opt.INSTANCE_WAIT,
            RobotOptions.Opt.EXPECT_MIRROR_VALIDATION, RobotOptions.Opt.SCENE_ADMIN_URL, RobotOptions.Opt.SCENE_MANAGER_METRICS_URL);

    /**
     * @param args 与交给 {@link RobotOptions#parse} 的同一份命令行（扫描规则相同：{@code --名字 值} 或 {@code --名字=值}，开关可只写名字）
     * @param env  环境变量
     */
    public static InstanceOptions parse(List<String> args, Map<String, String> env) throws UsageException {
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

        String mirrorText = value(RobotOptions.Opt.MIRROR_CONFIG_ID, given, env);
        int mirrorConfigId;
        try {
            mirrorConfigId = Integer.parseInt(mirrorText);
        } catch (NumberFormatException e) {
            throw new UsageException("--mirror-config-id 不是整数：" + mirrorText);
        }
        if (mirrorConfigId < 1) {
            throw new UsageException("--mirror-config-id 应 ≥ 1：" + mirrorText);
        }
        String waitText = value(RobotOptions.Opt.INSTANCE_WAIT, given, env);
        long waitMs;
        try {
            waitMs = Long.parseLong(waitText);
        } catch (NumberFormatException e) {
            throw new UsageException("--instance-wait-ms 不是整数毫秒：" + waitText);
        }
        if (waitMs <= 0 || waitMs > MAX_WAIT_MS) {
            throw new UsageException("--instance-wait-ms 应在 1–" + MAX_WAIT_MS + " 毫秒之间：" + waitText);
        }
        String validation = value(RobotOptions.Opt.EXPECT_MIRROR_VALIDATION, given, env);
        if (!validation.equals("strict") && !validation.equals("lenient")) {
            throw new UsageException("--expect-mirror-validation 只能是 strict / lenient：" + validation);
        }
        return new InstanceOptions(mirrorConfigId, Duration.ofMillis(waitMs), validation.equals("strict"),
                url(RobotOptions.Opt.SCENE_ADMIN_URL, given, env), url(RobotOptions.Opt.SCENE_MANAGER_METRICS_URL, given, env));
    }

    private static RobotOptions.Opt byArg(String name) throws UsageException {
        for (RobotOptions.Opt opt : RobotOptions.Opt.values()) {
            if (opt.arg.equals(name)) {
                return opt;
            }
        }
        throw new UsageException("未知选项：--" + name);
    }

    private static String url(RobotOptions.Opt opt, Map<RobotOptions.Opt, String> given, Map<String, String> env)
            throws UsageException {
        String url = value(opt, given, env);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new UsageException("--" + opt.arg + " 必须以 http:// 或 https:// 开头：" + url);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
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
