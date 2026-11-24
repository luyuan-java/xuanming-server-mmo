import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 跨平台本地竖切启停与巡检工具（零依赖 JDK 21 单文件工具，与 {@code tools/local/{start,stop}-slice.sh} 保持同一拓扑与次序）。
 *
 * <p>用法（在仓库根目录执行）：
 * <pre>
 *   java tools/LocalSlice.java status   # 查看 run/pids 下各实例存活与命名空间
 *   java tools/LocalSlice.java order    # 打印启动与停止次序及默认端口
 *   java tools/LocalSlice.java stop     # 按逆序优雅停机（每进程最多等 50s 后兜底强停）
 *   java tools/LocalSlice.java start    # 按依赖次序拉起本地 dev 切片（需已设置密钥环境变量与 mvn install）
 * </pre>
 */
public final class LocalSlice {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z][a-z0-9]{0,27}");
    private static final Set<String> RESERVED_NS = Set.of("mysql", "sys", "public");
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(50);
    private static final Duration START_PORT_TIMEOUT = Duration.ofSeconds(90);

    private record ServiceSpec(String name, String module, List<Integer> ports, List<String> extraArgs) {
    }

    /** 与 tools/local/start-slice.sh SERVICES 及 stop-slice.sh 严格同序。 */
    private static final List<ServiceSpec> START_ORDER = List.of(
            new ServiceSpec("game-scene-manager", "game-scene-manager", List.of(20882), List.of()),
            new ServiceSpec("game-content-guard", "game-content-guard", List.of(20919), List.of()),
            new ServiceSpec("game-login", "game-login", List.of(20881), List.of()),
            new ServiceSpec("game-friend", "game-friend", List.of(20883), List.of()),
            new ServiceSpec("game-chat", "game-chat", List.of(20884), List.of()),
            new ServiceSpec("game-mail", "game-mail", List.of(20916), List.of()),
            new ServiceSpec("game-rank", "game-rank", List.of(20917), List.of()),
            new ServiceSpec("game-match", "game-match", List.of(20888), List.of()),
            new ServiceSpec("game-team", "game-team", List.of(20885), List.of()),
            new ServiceSpec("game-guild", "game-guild", List.of(20886), List.of()),
            new ServiceSpec("game-mall", "game-mall", List.of(18120), List.of()),
            new ServiceSpec("game-payment", "game-payment", List.of(20920), List.of()),
            new ServiceSpec("game-trade", "game-trade", List.of(20887), List.of()),
            new ServiceSpec("game-data", "game-data", List.of(18106), List.of()),
            new ServiceSpec("game-gm-admin", "game-gm-admin", List.of(18128), List.of()),
            new ServiceSpec("game-inbox", "game-inbox", List.of(20918), List.of()),
            new ServiceSpec("game-scene", "game-scene", List.of(21000, 21100, 18104), List.of()),
            new ServiceSpec("game-gate", "game-gate", List.of(11000), List.of()),
            new ServiceSpec("game-gateway", "game-gateway", List.of(18081), List.of()),
            new ServiceSpec("game-battle", "game-battle", List.of(21200, 12000), List.of())
    );

    private static final List<String> STOP_ORDER = List.of(
            "game-battle", "game-match", "game-gateway", "game-gate-z2", "game-gate",
            "game-scene-z2", "game-scene-2", "game-scene", "game-inbox", "game-gm-admin", "game-data",
            "game-trade", "game-payment", "game-mall", "game-guild", "game-team",
            "game-rank", "game-mail", "game-chat", "game-friend", "game-login",
            "game-content-guard", "game-scene-manager"
    );

    public static void main(String[] args) {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            System.out.println("用法: java tools/LocalSlice.java <status|order|stop|start> [--root <dir>]");
            return;
        }
        String cmd = args[0];
        Path root = Path.of(".").toAbsolutePath().normalize();
        for (int i = 1; i < args.length; i++) {
            if ("--root".equals(args[i]) && i + 1 < args.length) {
                root = Path.of(args[++i]).toAbsolutePath().normalize();
            } else {
                System.err.println("未知参数: " + args[i]);
                System.exit(2);
            }
        }
        try {
            switch (cmd) {
                case "order" -> printOrder();
                case "status" -> printStatus(root);
                case "stop" -> stopSlice(root);
                case "start" -> startSlice(root);
                default -> {
                    System.err.println("不支持的子命令: " + cmd);
                    System.exit(2);
                }
            }
        } catch (Exception e) {
            System.err.println("LocalSlice 执行失败: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printOrder() {
        System.out.println("启动次序（共 " + START_ORDER.size() + " 个核心模块）：");
        for (int i = 0; i < START_ORDER.size(); i++) {
            ServiceSpec s = START_ORDER.get(i);
            System.out.printf("  %2d. %-20s ports=%s%n", i + 1, s.name(), s.ports());
        }
        System.out.println("停止次序（共 " + STOP_ORDER.size() + " 个实例位）：" + String.join(" -> ", STOP_ORDER));
    }

    private static void printStatus(Path root) throws IOException {
        Path pidDir = root.resolve("run/pids");
        Path nsFile = root.resolve("run/game-namespace");
        String ns = Files.isRegularFile(nsFile) ? Files.readString(nsFile, StandardCharsets.UTF_8).strip() : "(未记录)";
        System.out.println("部署命名空间: " + ns);
        if (!Files.isDirectory(pidDir)) {
            System.out.println("当前没有运行中的本地切片（run/pids 不存在）。");
            return;
        }
        int alive = 0;
        int stale = 0;
        for (String name : STOP_ORDER) {
            Path pidFile = pidDir.resolve(name + ".pid");
            if (!Files.isRegularFile(pidFile)) {
                continue;
            }
            long pid = parsePid(pidFile);
            boolean running = pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            if (running) alive++; else stale++;
            System.out.printf("  %-20s pid=%-8d %s%n", name, pid, running ? "RUNNING" : "STALE");
        }
        System.out.println("汇总: running=" + alive + " stale=" + stale);
    }

    private static void stopSlice(Path root) throws IOException, InterruptedException {
        Path pidDir = root.resolve("run/pids");
        for (String name : STOP_ORDER) {
            Path pidFile = pidDir.resolve(name + ".pid");
            if (!Files.isRegularFile(pidFile)) {
                continue;
            }
            long pid = parsePid(pidFile);
            Optional<ProcessHandle> handleOpt = pid > 0 ? ProcessHandle.of(pid) : Optional.empty();
            if (handleOpt.isPresent() && handleOpt.get().isAlive()) {
                ProcessHandle handle = handleOpt.get();
                handle.destroy();
                long deadline = System.nanoTime() + STOP_TIMEOUT.toNanos();
                while (handle.isAlive() && System.nanoTime() < deadline) {
                    Thread.sleep(250);
                }
                if (handle.isAlive()) {
                    System.out.println(name + " 未在 50s 内退出，强制结束");
                    handle.destroyForcibly();
                }
            }
            Files.deleteIfExists(pidFile);
            System.out.println("已停止 " + name);
        }
        boolean anyPidLeft = false;
        if (Files.isDirectory(pidDir)) {
            try (DirectoryStream<Path> left = Files.newDirectoryStream(pidDir, "*.pid")) {
                anyPidLeft = left.iterator().hasNext();
            }
        }
        if (!anyPidLeft) {
            Files.deleteIfExists(root.resolve("run/game-namespace"));
        }
    }

    private static void startSlice(Path root) throws IOException, InterruptedException {
        Path fenceDir = Path.of(System.getenv().getOrDefault("MMORPG_PUBLICATION_FENCE_DIR",
                root.resolve("run/maintenance/publication-fences").toString()));
        if (Files.isSymbolicLink(fenceDir)) {
            throw new IllegalStateException("发布围栏目录不可为符号链接");
        }
        if (Files.isDirectory(fenceDir)) {
            try (DirectoryStream<Path> fences = Files.newDirectoryStream(fenceDir, "*.closed.json")) {
                if (fences.iterator().hasNext()) {
                    throw new IllegalStateException("发布已永久关闭，普通 start 无权解除");
                }
            }
        }
        for (String required : List.of("GAME_GATE_TOKEN_SECRET", "GAME_LOGIN_DEV_PASSWORD",
                "GAME_NODE_LINK_SECRET", "GAME_DUBBO_SECRET")) {
            String v = System.getenv(required);
            if (v == null || v.isEmpty()) {
                throw new IllegalStateException("缺少必要环境变量: " + required);
            }
        }
        String ns = System.getenv().containsKey("GAME_NAMESPACE") ? System.getenv("GAME_NAMESPACE") : "game";
        if (ns == null || !NAMESPACE.matcher(ns).matches() || RESERVED_NS.contains(ns)) {
            throw new IllegalStateException("部署命名空间 GAME_NAMESPACE 非法: " + ns);
        }
        Path pidDir = root.resolve("run/pids");
        Path logDir = root.resolve("run/logs");
        if (Files.isDirectory(pidDir)) {
            try (DirectoryStream<Path> pids = Files.newDirectoryStream(pidDir, "*.pid")) {
                for (Path p : pids) {
                    long pid = parsePid(p);
                    if (pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                        throw new IllegalStateException("已有切片进程在运行（" + p.getFileName() + "），请先执行 stop");
                    }
                }
            }
        }
        Files.createDirectories(pidDir);
        Files.createDirectories(logDir);
        Files.writeString(root.resolve("run/game-namespace"), ns + "\n", StandardCharsets.UTF_8);

        Map<String, String> baseEnv = new LinkedHashMap<>(System.getenv());
        baseEnv.putIfAbsent("GAME_RUN_MODE", "dev");
        baseEnv.put("GAME_NAMESPACE", ns);
        baseEnv.putIfAbsent("SPRING_DATASOURCE_URL",
                "jdbc:mysql://127.0.0.1:4000/" + ns
                        + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8"
                        + "&connectTimeout=3000&socketTimeout=10000&useAffectedRows=true");
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();

        for (ServiceSpec spec : START_ORDER) {
            Path jar = findRunnableJar(root.resolve(spec.module()).resolve("target"), spec.module());
            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);
            cmd.add("-jar");
            cmd.add(jar.toString());
            cmd.add("--spring.profiles.active=local");
            cmd.addAll(spec.extraArgs());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(root.toFile());
            pb.environment().putAll(baseEnv);
            Path logFile = logDir.resolve(spec.name() + ".log");
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
            System.out.println("启动 " + spec.name() + " (" + jar.getFileName() + ")");
            Process proc = pb.start();
            Files.writeString(pidDir.resolve(spec.name() + ".pid"), proc.pid() + "\n", StandardCharsets.UTF_8);
            for (int port : spec.ports()) {
                waitPort(spec.name(), port, proc);
            }
            System.out.println("  " + spec.name() + " 就绪 ports=" + spec.ports());
        }
        System.out.println("全部核心服务就绪。停止：java tools/LocalSlice.java stop");
    }

    private static Path findRunnableJar(Path targetDir, String module) throws IOException {
        if (Files.isDirectory(targetDir)) {
            try (DirectoryStream<Path> jars = Files.newDirectoryStream(targetDir, module + "-*.jar")) {
                for (Path p : jars) {
                    if (!p.getFileName().toString().contains("-plain")) {
                        return p;
                    }
                }
            }
        }
        throw new IllegalStateException("找不到 " + module + " 可执行 jar，请先执行 mvn -DskipTests install");
    }

    private static void waitPort(String name, int port, Process proc) throws InterruptedException {
        long deadline = System.nanoTime() + START_PORT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!proc.isAlive()) {
                throw new IllegalStateException(name + " 进程提前退出 exit=" + proc.exitValue() + "，查看 run/logs/" + name + ".log");
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException ignored) {
                Thread.sleep(300);
            }
        }
        throw new IllegalStateException(name + " 端口 " + port + " 在 " + START_PORT_TIMEOUT.toSeconds() + "s 内未就绪");
    }

    private static long parsePid(Path pidFile) {
        try {
            return Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).strip());
        } catch (Exception e) {
            return -1L;
        }
    }
}
