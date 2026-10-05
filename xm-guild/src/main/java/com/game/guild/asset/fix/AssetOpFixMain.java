package com.game.guild.asset.fix;

import com.game.api.proto.AssetOutcome;
import com.game.common.deadline.Deadline;
import com.game.guild.asset.AssetOpStatus;
import com.game.guild.asset.GuildAssetStore;
import com.game.guild.asset.GuildAssetStore.FinalizeResult;
import com.game.guild.asset.ManualResolution;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 帮会资产指令（{@code xm_java.guild_asset_op}）卡死行的人工终结工具 assetopfix（基线 {@code go/guild/cmd/assetopfix/main.go}；
 * guild-economy-spec §2.12、§7.10；裁决 D4：v1 只做 CLI、进程里不挂人工终结入口；Q11：xm-guild 内的第二个主类）。
 *
 * <p>为什么需要它：scene 对某个 seq 回 UNKNOWN（窗口已过 / 信封非法）时，重投循环走 ALERT、按 max-backoff 反复重排、永不终结；同一玩家同一条流累计
 * 16 行未决后，新的捐献 / 兑换全被 14026 挡住。UNKNOWN 的 seq，scene 以后也不会再应用，唯一的疑问是「以前有没有应用过」——只能由人查流水回答；
 * 查清之后人工终结是安全的。
 *
 * <p>边界：只连 MySQL 与缓存 Redis（终结会改帮会资金 / 成员帮贡，必须失效缓存；Redis 连不上拒绝执行）；不起 Spring / Dubbo、不连 scene / Kafka，
 * <b>不推送</b>。人工终结<b>不置 durable</b>，只开放 applied / aborted 两种结局（裁决 E：REJECTED 是 scene 的判定、PARTIAL 需逐件核对）；
 * 对侧账与自动终结共用 {@link GuildAssetStore#resolveManually} 里的同一个 terminate（同一把 {@code status = PENDING} 的 CAS，只有一个赢家）。
 *
 * <p>用法（同一个 jar，换主类）：
 * <pre>
 * java -cp xm-guild.jar -Dloader.main=com.game.guild.asset.fix.AssetOpFixMain org.springframework.boot.loader.launch.PropertiesLauncher \
 *     [-f application.yaml] list [-min-age-min 60] [-limit 50]
 * ... resolve -op &lt;op_id&gt; -as applied|aborted -operator &lt;名字&gt; -reason &lt;依据&gt; -confirm &lt;op_id&gt; -txlog-checked [-force]
 * </pre>
 * 配置与服务进程同一份（缺省读 jar 里的 {@code application.yaml}，环境变量 / {@code -D} 覆盖规则同 Spring Boot）。
 *
 * <p>退出码：0 成功；1 执行出错；2 参数不合法或前置条件不满足（含缺 {@code -txlog-checked}）；3 行已不在 PENDING（被自动终结或别人抢先），本次未做任何改动。
 */
public final class AssetOpFixMain {

    private static final Logger log = LoggerFactory.getLogger(AssetOpFixMain.class);

    static final int EXIT_OK = 0;
    static final int EXIT_ERROR = 1;
    static final int EXIT_REJECTED = 2;
    static final int EXIT_NOT_PENDING = 3;

    /** 只终结创建早于 30 分钟前的行：更新的行循环还在正常重试，人工插手只会和它抢同一行。 */
    static final long RESOLVE_MIN_AGE_MS = 30L * 60_000L;
    /**
     * 只处理「反复 UNKNOWN」的行（{@code -force} 只跳过这一条）。E12 之后传输失败不再把 last_outcome 写成 0，但新插的行 last_outcome 初值就是 0：
     * 从创建起只遇到过传输失败的行列上仍是 0，所以核对清单第 0 步照旧要求先确认 scene 可达、循环仍在报 UNKNOWN。
     */
    static final int RESOLVE_MIN_ATTEMPTS = 10;
    static final int LIST_DEFAULT_MIN_AGE_MIN = 60;
    static final int LIST_DEFAULT_LIMIT = 50;
    static final int LIST_MAX_LIMIT = 500;
    /** 一次 list / resolve 的总预算（Store 内部每条 SQL 另有子预算：读 1000 ms、人工终结事务 2000 ms）。 */
    static final long OP_TIMEOUT_MS = 15_000L;

    static final String USAGE = """
            assetopfix：帮会资产指令（guild_asset_op）卡死行的人工终结工具（guild-economy-spec §2.12）

            用法：
              assetopfix [-f <配置 yaml>] list [-min-age-min 60] [-limit 50]
              assetopfix [-f <配置 yaml>] resolve -op <op_id> -as applied|aborted -operator <名字> -reason <依据> \\
                  -confirm <op_id> -txlog-checked [-force]

            resolve 的前置条件（任一不满足即拒绝，不改任何数据）：
              行存在且为 PENDING；创建早于 30 分钟前；
              last_outcome = UNKNOWN(0) 且 attempts >= 10（-force 只跳过这一条）；
              -confirm 与 -op 相同（二次确认）；
              带 -txlog-checked（不带则打印核对清单后退出）。

            注意：last_outcome = 0 也包括「从创建起只遇到过传输失败、scene 从没答复过」的行（scene 可能已应用、只是回包丢了）。
            list 里的 yes 只说明列上的前置满足，动手前必须按核对清单第 0 步确认 scene 可达、循环仍在报 UNKNOWN 告警。

            退出码：0 成功；1 执行出错；2 参数 / 前置条件不满足；3 行已不在 PENDING，未做改动。
            """;

    /** 按配置连上 MySQL 与缓存 Redis，给出 Store；失败抛异常（消息不含口令与连接串原文）。 */
    @FunctionalInterface
    interface StoreOpener {
        OpenedStore open(String configPath) throws Exception;
    }

    /** 打开的 Store 与要在退出前关掉的资源。 */
    record OpenedStore(GuildAssetStore store, AutoCloseable resources) {
    }

    private AssetOpFixMain() {
    }

    public static void main(String[] args) {
        logToStderr();
        System.exit(run(args, System.out, System.err, AssetOpFixEnvironment::open, System::currentTimeMillis));
    }

    /**
     * 没有 Spring 上下文：日志改走 stderr（stdout 只留表格、审计行与结论，便于直接贴进工单），连接池 / Redis 客户端的启动噪音压到 WARN。
     * 审计行另以 ERROR 级进日志，部署侧采集 stderr 即可检索。
     */
    private static void logToStderr() {
        if (!(LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext context)) {
            return;
        }
        context.reset();
        ch.qos.logback.classic.encoder.PatternLayoutEncoder encoder = new ch.qos.logback.classic.encoder.PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [guild-assetopfix] %logger{36} - %msg%n");
        encoder.start();
        ch.qos.logback.core.ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.ConsoleAppender<>();
        appender.setContext(context);
        appender.setTarget("System.err");
        appender.setEncoder(encoder);
        appender.start();
        ch.qos.logback.classic.Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(ch.qos.logback.classic.Level.INFO);
        root.addAppender(appender);
        context.getLogger("org.redisson").setLevel(ch.qos.logback.classic.Level.WARN);
        context.getLogger("com.alibaba.druid").setLevel(ch.qos.logback.classic.Level.WARN);
        context.getLogger("io.netty").setLevel(ch.qos.logback.classic.Level.WARN);
    }

    /** 可测的入口：不调 {@code System.exit}，返回退出码。 */
    static int run(String[] args, PrintStream out, PrintStream err, StoreOpener opener, LongSupplier clockMs) {
        Flags global = new Flags(Map.of("f", false, "h", true, "help", true));
        String error = global.parse(args, 0);
        if (error != null) {
            err.println(error);
            err.print(USAGE);
            return EXIT_REJECTED;
        }
        if (global.bool("h") || global.bool("help")) {
            out.print(USAGE);
            return EXIT_OK;
        }
        List<String> rest = global.positional();
        if (rest.isEmpty()) {
            err.print(USAGE);
            return EXIT_REJECTED;
        }
        String configPath = global.string("f");
        String[] sub = rest.subList(1, rest.size()).toArray(String[]::new);
        // 子命令参数先于连库解析：参数写错不该先去碰数据库
        return switch (rest.getFirst()) {
            case "list" -> {
                ListOptions o = parseList(sub, err);
                yield o == null ? EXIT_REJECTED : withStore(opener, configPath, err, store -> runList(store, o, out, err, clockMs));
            }
            case "resolve" -> {
                ResolveOptions o = parseResolve(sub, err);
                yield o == null ? EXIT_REJECTED
                        : withStore(opener, configPath, err, store -> runResolve(store, o, out, err, clockMs));
            }
            default -> {
                err.println("未知子命令 \"" + rest.getFirst() + "\"");
                err.println();
                err.print(USAGE);
                yield EXIT_REJECTED;
            }
        };
    }

    private interface StoreCommand {
        int run(GuildAssetStore store);
    }

    private static int withStore(StoreOpener opener, String configPath, PrintStream err, StoreCommand command) {
        OpenedStore opened;
        try {
            opened = opener.open(configPath);
        } catch (Exception e) {
            err.println(e.getMessage() == null ? e.toString() : e.getMessage());
            return EXIT_ERROR;
        }
        try {
            return command.run(opened.store());
        } finally {
            try {
                opened.resources().close();
            } catch (Exception e) {
                err.println("关闭连接时出错（忽略）: " + e);
            }
        }
    }

    // ================================================================ list

    record ListOptions(int minAgeMin, int limit) {
    }

    static ListOptions parseList(String[] args, PrintStream err) {
        Flags flags = new Flags(Map.of("min-age-min", false, "limit", false));
        String error = flags.parse(args, 0);
        if (error != null) {
            err.println(error);
            return null;
        }
        if (!flags.positional().isEmpty()) {
            err.println("list 不接受位置参数: " + flags.positional());
            return null;
        }
        Integer minAge = flags.integer("min-age-min", LIST_DEFAULT_MIN_AGE_MIN, err);
        Integer limit = flags.integer("limit", LIST_DEFAULT_LIMIT, err);
        if (minAge == null || limit == null) {
            return null;
        }
        if (minAge < 0) {
            err.println("-min-age-min 不能为负（得到 " + minAge + "）");
            return null;
        }
        if (limit < 1 || limit > LIST_MAX_LIMIT) {
            err.println("-limit 必须在 [1, " + LIST_MAX_LIMIT + "] 内（得到 " + limit + "）");
            return null;
        }
        return new ListOptions(minAge, limit);
    }

    static int runList(GuildAssetStore store, ListOptions o, PrintStream out, PrintStream err, LongSupplier clockMs) {
        long nowMs = clockMs.getAsLong();
        long createdBeforeMs = nowMs - o.minAgeMin() * 60_000L;
        List<GuildAssetOpRow> rows;
        try {
            rows = store.listStuck(createdBeforeMs, o.limit(), Deadline.after(OP_TIMEOUT_MS));
        } catch (RuntimeException e) {
            err.println("列出未决行失败: " + e);
            return EXIT_ERROR;
        }
        if (rows.isEmpty()) {
            out.println("没有创建早于 " + o.minAgeMin() + " 分钟前的 PENDING 行");
            return EXIT_OK;
        }
        // 表头与取值只用 ASCII：按字符数对齐，中文双宽字符会把列挤歪
        List<String[]> table = new ArrayList<>();
        table.add(new String[] {"op_id", "player_id", "guild_id", "stream", "epoch", "seq", "kind", "attempts", "last_outcome",
                "last_reason", "age_min", "resolvable"});
        for (GuildAssetOpRow r : rows) {
            table.add(new String[] {u(r.getOpId()), u(r.getPlayerId()), u(r.getGuildId()), Integer.toUnsignedString(r.getStream()),
                    u(r.getStreamEpoch()), u(r.getSeq()), r.getKind().name(), Integer.toUnsignedString(r.getAttempts()),
                    Integer.toUnsignedString(r.getLastOutcome()), Integer.toUnsignedString(r.getLastReason()),
                    Long.toString(ageMinutes(r.getCreatedMs(), nowMs)), resolvableLabel(r, nowMs)});
        }
        printTable(out, table);
        out.println();
        out.println("共 " + rows.size() + " 行。resolvable: yes = 满足 resolve 在列上的全部前置；force = 只差\"反复 UNKNOWN\"一条，"
                + "确需人工介入时加 -force；no = 不能人工终结。");
        out.println("注意：last_outcome=0 也包括从创建起只遇到过传输失败的行（scene 可能已应用、只是回包丢了，scene 恢复后循环会自己终结）。"
                + "yes 不等于该人工终结：先确认对应 scene 节点可达、日志里循环仍在报 \"scene 回 UNKNOWN，不终结 op_id=<id>\"。");
        return EXIT_OK;
    }

    static String resolvableLabel(GuildAssetOpRow r, long nowMs) {
        if (checkResolvable(r, nowMs, false) == null) {
            return "yes";
        }
        return checkResolvable(r, nowMs, true) == null ? "force" : "no";
    }

    // ================================================================ resolve

    record ResolveOptions(long opId, String as, AssetOpStatus status, String operator, String reason, boolean txlogChecked,
                          boolean force) {
    }

    static ResolveOptions parseResolve(String[] args, PrintStream err) {
        Map<String, Boolean> spec = new LinkedHashMap<>();
        spec.put("op", false);
        spec.put("as", false);
        spec.put("operator", false);
        spec.put("reason", false);
        spec.put("confirm", false);
        spec.put("txlog-checked", true);
        spec.put("force", true);
        Flags flags = new Flags(spec);
        String error = flags.parse(args, 0);
        if (error != null) {
            err.println(error);
            return null;
        }
        if (!flags.positional().isEmpty()) {
            err.println("resolve 不接受位置参数: " + flags.positional());
            return null;
        }
        Long opId = flags.unsignedLong("op", 0, err);
        Long confirm = flags.unsignedLong("confirm", 0, err);
        if (opId == null || confirm == null) {
            return null;
        }
        if (opId == 0) {
            err.println("缺 -op");
            return null;
        }
        String as = flags.string("as");
        AssetOpStatus status = finalStatusOf(as);
        if (status == null) {
            err.println("-as 只能是 applied 或 aborted（得到 \"" + (as == null ? "" : as) + "\"）");
            return null;
        }
        String operator = flags.string("operator");
        if (operator == null || operator.isBlank()) {
            err.println("缺 -operator：人工终结必须留下操作人");
            return null;
        }
        // 操作人按原样进审计行：带换行 / 控制字符就能伪造出第二条 [AssetOpManual]，所以直接拒绝（长度上限由 ManualResolution 统一校验）
        if (hasControl(operator)) {
            err.println("-operator 不能含换行或控制字符");
            return null;
        }
        String reason = flags.string("reason");
        if (reason == null || reason.isBlank()) {
            err.println("缺 -reason：人工终结必须写明判定依据");
            return null;
        }
        if (!confirm.equals(opId)) {
            err.println("二次确认失败：-confirm（" + u(confirm) + "）必须与 -op（" + u(opId) + "）相同");
            return null;
        }
        return new ResolveOptions(opId, as, status, operator, reason, flags.bool("txlog-checked"), flags.bool("force"));
    }

    /** 只开放两种人工结局（裁决 E）。null = 非法。 */
    static AssetOpStatus finalStatusOf(String as) {
        if ("applied".equals(as)) {
            return AssetOpStatus.APPLIED;
        }
        if ("aborted".equals(as)) {
            return AssetOpStatus.ABORTED;
        }
        return null;
    }

    static int runResolve(GuildAssetStore store, ResolveOptions o, PrintStream out, PrintStream err, LongSupplier clockMs) {
        Optional<GuildAssetOpRow> found;
        try {
            found = store.getOp(o.opId(), Deadline.after(OP_TIMEOUT_MS));
        } catch (RuntimeException e) {
            err.println("读取 op_id=" + u(o.opId()) + " 失败: " + e);
            return EXIT_ERROR;
        }
        if (found.isEmpty()) {
            err.println("op_id=" + u(o.opId()) + " 不存在");
            return EXIT_REJECTED;
        }
        GuildAssetOpRow row = found.get();
        String refused = checkResolvable(row, clockMs.getAsLong(), o.force());
        if (refused != null) {
            err.println("拒绝终结 op_id=" + u(o.opId()) + ": " + refused);
            return EXIT_REJECTED;
        }
        if (!o.txlogChecked()) {
            printChecklist(err, row);
            return EXIT_REJECTED;
        }

        // 前置检查与下面的 CAS 之间，循环可能已经把这一行终结了：CAS（WHERE status = PENDING）会落空、不会重复入账。前置检查只为挡住误操作
        ManualResolution resolution = new ManualResolution(o.opId(), o.status(), o.operator(), o.reason());
        boolean finalized = false;
        String failure = resolution.validate();
        if (failure == null) {
            try {
                FinalizeResult result = store.resolveManually(resolution, clockMs.getAsLong(), Deadline.after(OP_TIMEOUT_MS));
                finalized = result.finalized();
            } catch (RuntimeException e) {
                failure = e.toString();
            }
        }

        // 审计行无论成败都打（ERROR 级确保进日志检索），并原样打到 stdout 供贴进运维工单；末尾 force：跳过了哪条前置，事后追责必须看得到
        String audit = "[AssetOpManual] operator=" + o.operator() + " op_id=" + u(row.getOpId()) + " player_id=" + u(row.getPlayerId())
                + " stream=" + Integer.toUnsignedString(row.getStream()) + " seq=" + u(row.getSeq()) + " kind=" + row.getKindValue()
                + " as=" + o.as() + " reason=" + quote(o.reason()) + " finalized=" + finalized + " force=" + o.force();
        log.error(audit);
        out.println(audit);

        if (failure != null) {
            log.error("[AssetOpManual] op_id={} 人工终结失败: {}", u(o.opId()), failure);
            err.println("人工终结失败 op_id=" + u(o.opId()) + ": " + failure);
            return EXIT_ERROR;
        }
        if (!finalized) {
            out.println("op_id=" + u(o.opId()) + " 已被自动终结 / 已非 PENDING，本次未做任何改动（不要再手工补账）");
            return EXIT_NOT_PENDING;
        }
        out.println("op_id=" + u(o.opId()) + " 已人工终结为 " + o.as() + "。请把上面的 [AssetOpManual] 审计行贴到运维工单。");
        return EXIT_OK;
    }

    /** resolve 的前置条件（checkResolvable，main.go:428-447）；{@code force} 只跳过「反复 UNKNOWN」这一条。null = 满足。 */
    static String checkResolvable(GuildAssetOpRow r, long nowMs, boolean force) {
        if (r.getStatus() != GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING) {
            return "status=" + r.getStatus() + "，不是 PENDING：已终结的行不能再人工改";
        }
        if (Long.compareUnsigned(r.getCreatedMs() + RESOLVE_MIN_AGE_MS, nowMs) > 0) {
            return "创建于 " + ageMinutes(r.getCreatedMs(), nowMs) + " 分钟前，不足 30 分钟：新行循环还在正常重试，人工插手只会和它抢";
        }
        if (force) {
            return null;
        }
        int unknown = AssetOutcome.ASSET_OUTCOME_UNKNOWN_VALUE;
        if (r.getLastOutcome() != unknown || Integer.compareUnsigned(r.getAttempts(), RESOLVE_MIN_ATTEMPTS) < 0) {
            return "last_outcome=" + Integer.toUnsignedString(r.getLastOutcome()) + " attempts=" + Integer.toUnsignedString(r.getAttempts())
                    + "：只处理反复 UNKNOWN（last_outcome=" + unknown + " 且 attempts >= " + RESOLVE_MIN_ATTEMPTS + "）的行；"
                    + "其它原因（玩家离线、背包满、战斗中）循环会自己收口，确需人工介入时加 -force";
        }
        return null;
    }

    /** 核对清单（printChecklist，main.go:449-472）：只读这一行已有的列，不查任何别的系统，结论必须由人给出。 */
    static void printChecklist(PrintStream w, GuildAssetOpRow r) {
        w.println("未带 -txlog-checked，本次不做任何改动。请先完成核对：");
        w.println("  行：op_id=" + u(r.getOpId()) + " player_id=" + u(r.getPlayerId()) + " guild_id=" + u(r.getGuildId()) + " stream="
                + Integer.toUnsignedString(r.getStream()) + " epoch=" + u(r.getStreamEpoch()) + " seq=" + u(r.getSeq()) + " kind="
                + r.getKind().name() + " attempts=" + Integer.toUnsignedString(r.getAttempts()) + " last_outcome="
                + Integer.toUnsignedString(r.getLastOutcome()) + " last_reason=" + Integer.toUnsignedString(r.getLastReason()));
        w.println("  0. 先确认这是 scene 真回了 UNKNOWN：该玩家所在 scene 节点可达，且日志里循环最近仍在报 \"[AssetOp] scene 回 UNKNOWN，"
                + "不终结 op_id=" + u(r.getOpId()) + "\"。last_outcome=0 也包括从创建起只遇到过传输失败的行——那种行 scene 可能已应用、"
                + "只是回包丢了，恢复 scene 后循环会自己终结，不要人工终结");
        w.println("  1. 查 guild 的 [AssetOp] 日志：op_id=" + u(r.getOpId()) + " / corr=" + u(r.getOpId()) + "（每次投递、重排、告警都带它）");
        w.println("  2. 资产流水（xm-data 审计表）查 correlation_id=" + u(r.getOpId()) + "：有记录 = scene 已应用过这条指令");
        w.println("  3. 结论：查到已应用 → -as applied；确认从未应用 → -as aborted");
        if (r.getKind() == GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE) {
            w.println("  本行是捐献：applied = 帮会资金 +" + u(r.getFundsDelta()) + "、捐献者帮贡 +" + u(r.getContributionDelta())
                    + "（帮会已解散则只记孤儿计数）；aborted = 退回今日捐献次数");
        } else if (r.getKind() == GuildAssetOpKind.GUILD_ASSET_OP_KIND_SHOP) {
            w.println("  本行是商店兑换：applied = 不动帮贡（下单时已扣）；aborted = 退回帮贡 " + u(r.getContributionDelta()) + " 与限购次数");
        } else {
            w.println("  本行 kind=" + r.getKind().name() + "：只改状态，不做对侧账");
        }
        w.println("核对完毕后带上 -txlog-checked 重跑同一条命令。");
    }

    // ================================================================ 工具

    static long ageMinutes(long createdMs, long nowMs) {
        if (Long.compareUnsigned(nowMs, createdMs) <= 0) {
            return 0;
        }
        return (nowMs - createdMs) / 60_000L;
    }

    static boolean hasControl(String s) {
        return s.codePoints().anyMatch(Character::isISOControl);
    }

    /** 审计行里的字符串按 Go {@code %q} 的样子加引号转义（换行、引号、反斜杠与控制字符都转义，伪造不出第二行）。 */
    static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        s.codePoints().forEach(cp -> {
            switch (cp) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (Character.isISOControl(cp)) {
                        b.append(String.format("\\x%02x", cp));
                    } else {
                        b.appendCodePoint(cp);
                    }
                }
            }
        });
        return b.append('"').toString();
    }

    private static void printTable(PrintStream out, List<String[]> table) {
        int[] widths = new int[table.getFirst().length];
        for (String[] row : table) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }
        for (String[] row : table) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.length; i++) {
                line.append(row[i]);
                if (i < row.length - 1) {
                    line.append(" ".repeat(widths[i] - row[i].length() + 2));
                }
            }
            out.println(line);
        }
    }

    private static String u(long v) {
        return Long.toUnsignedString(v);
    }

    /** Go flag 包风格的最小解析器：{@code -name value}、{@code --name value}、{@code -name=value}；布尔旗标不带值；遇到第一个非旗标即停。 */
    static final class Flags {
        private final Map<String, Boolean> spec;
        private final Map<String, String> values = new LinkedHashMap<>();
        private final List<String> positional = new ArrayList<>();

        /** @param spec 旗标名 → 是否布尔 */
        Flags(Map<String, Boolean> spec) {
            this.spec = spec;
        }

        /** @return null = 成功；否则是错误文案 */
        String parse(String[] args, int from) {
            int i = from;
            while (i < args.length) {
                String a = args[i];
                if (a.equals("--")) {
                    i++;
                    break;
                }
                if (!a.startsWith("-") || a.equals("-")) {
                    break;
                }
                String body = a.startsWith("--") ? a.substring(2) : a.substring(1);
                String name = body;
                String value = null;
                int eq = body.indexOf('=');
                if (eq >= 0) {
                    name = body.substring(0, eq);
                    value = body.substring(eq + 1);
                }
                Boolean isBool = spec.get(name);
                if (isBool == null) {
                    return "未知参数 -" + name;
                }
                if (isBool) {
                    if (value != null && !value.equals("true") && !value.equals("false")) {
                        return "-" + name + " 只接受 true / false（得到 \"" + value + "\"）";
                    }
                    values.put(name, value == null ? "true" : value);
                    i++;
                    continue;
                }
                if (value == null) {
                    if (i + 1 >= args.length) {
                        return "-" + name + " 缺值";
                    }
                    value = args[++i];
                }
                values.put(name, value);
                i++;
            }
            for (; i < args.length; i++) {
                positional.add(args[i]);
            }
            return null;
        }

        List<String> positional() {
            return positional;
        }

        boolean bool(String name) {
            return "true".equals(values.get(name));
        }

        String string(String name) {
            return values.get(name);
        }

        Integer integer(String name, int fallback, PrintStream err) {
            String v = values.get(name);
            if (v == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                err.println("-" + name + " 不是整数: \"" + v + "\"");
                return null;
            }
        }

        Long unsignedLong(String name, long fallback, PrintStream err) {
            String v = values.get(name);
            if (v == null) {
                return fallback;
            }
            try {
                return Long.parseUnsignedLong(v.trim());
            } catch (NumberFormatException e) {
                err.println("-" + name + " 不是无符号整数: \"" + v + "\"");
                return null;
            }
        }
    }
}
