package com.game.data.tools;

import com.game.data.tools.release.ReleaseBundle;
import com.game.data.tools.release.ReleaseFailure;
import com.game.discovery.namespace.GameNamespaceBinder;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 离线 create/verify 与保守 preflight。无 Spring 启动、网络、Docker、发布、Git 写操作。 */
public final class ReleaseArtifacts {
    private ReleaseArtifacts() { }
    public static void main(String[] args) {
        System.exit(run(args, System.getProperties(), System.getenv(), System.out, System.err));
    }
    public static int run(String[] args, Map<?, ?> properties, Map<String, String> environment, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0 || args.length % 2 == 0) return usage(err);
            String command = args[0];
            Map<String, String> options = new TreeMap<>();
            for (int i = 1; i < args.length; i += 2) {
                if (!args[i].startsWith("--") || args[i + 1].isBlank() || options.putIfAbsent(args[i], args[i + 1]) != null) return usage(err);
            }
            Set<String> expected = command.equals("create") ? Set.of("--source", "--spec", "--output-root")
                    : Set.of("--bundle", "--manifest-sha256");
            if (!Set.of("create", "verify", "preflight").contains(command) || !options.keySet().equals(expected)) return usage(err);
            String namespace;
            try { namespace = GameNamespaceBinder.bind(properties, environment).namespace().value(); }
            catch (RuntimeException invalid) { err.println("NAMESPACE_INVALID"); return 1; }
            ReleaseBundle tool = new ReleaseBundle();
            ReleaseBundle.Result result = command.equals("create")
                    ? tool.create(Path.of(options.get("--source")), Path.of(options.get("--spec")), Path.of(options.get("--output-root")), namespace)
                    : tool.verify(Path.of(options.get("--bundle")), options.get("--manifest-sha256"), namespace);
            out.println(JsonMapper.builder().build().writeValueAsString(result));
            // UNKNOWN 不能成为可部署的成功码；verify 的 0 仅表示离线原件核验成功。
            return command.equals("preflight") ? 3 : 0;
        } catch (ReleaseFailure failure) {
            err.println(failure.code());
            return 1;
        } catch (Exception failure) {
            // 路径、DSN、Jackson 原文与进程输出都可能含敏感值，不输出异常 message/cause。
            err.println("RELEASE_IO_OR_INPUT_FAILED");
            return 1;
        }
    }
    private static int usage(PrintStream err) {
        err.println("USAGE: create --source DIR --spec FILE --output-root DIR | verify|preflight --bundle DIR --manifest-sha256 HASH");
        return 2;
    }
}
