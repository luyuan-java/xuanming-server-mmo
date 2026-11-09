package com.game.contract.check.persist;

import static com.game.contract.check.persist.PersistentSchemaSnapshot.require;

import com.google.protobuf.Descriptors.Descriptor;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 使用本地已编译的消息 descriptor；无网络、无源码解析，不在检查时改写台账。 */
public final class PersistentSchemaTool {
    private PersistentSchemaTool() { }
    public static void main(String[] args) { System.exit(run(args, System.out, System.err)); }

    public static int run(String[] args, PrintStream out, PrintStream error) {
        try {
            require(args.length > 0 && args.length % 2 == 1, "ARGUMENTS");
            Map<String, String> values = new TreeMap<>();
            for (int i = 1; i < args.length; i += 2) {
                require(args[i].startsWith("--") && !args[i + 1].isBlank()
                        && values.putIfAbsent(args[i].substring(2), args[i + 1]) == null, "ARGUMENTS");
            }
            if (args[0].equals("snapshot")) {
                require(values.keySet().equals(Set.of("message-class", "output")), "ARGUMENTS");
                var snapshot = capture(values.get("message-class"));
                byte[] bytes = PersistentSchemaLedger.genesisBytes(snapshot);
                PersistentSchemaLedger.writeNew(Path.of(values.get("output")), bytes);
                out.println("genesis=" + PersistentSchemaSnapshot.sha256(bytes));
                out.println("root=" + snapshot.root());
                return 0;
            }
            if (args[0].equals("prefix")) {
                require(values.keySet().equals(Set.of("baseline", "candidate", "genesis", "baseline-head", "candidate-head")), "ARGUMENTS");
                var baseline = PersistentSchemaLedger.read(Path.of(values.get("baseline")), values.get("genesis"), values.get("baseline-head"));
                var candidate = PersistentSchemaLedger.read(Path.of(values.get("candidate")), values.get("genesis"), values.get("candidate-head"));
                PersistentSchemaLedger.requirePrefix(baseline, candidate);
                out.println("history-prefix=verified");
                return 0;
            }
            require(Set.of("check", "append").contains(args[0]) && values.keySet().equals(
                    Set.of("message-class", "directory", "genesis", "head", "allow-additions")), "ARGUMENTS");
            Set<String> allowed = values.get("allow-additions").equals("none") ? Set.of()
                    : Set.copyOf(Arrays.asList(values.get("allow-additions").split(",", -1)));
            require(allowed.stream().allMatch(s -> s.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")), "ADDITION_POLICY");
            var snapshot = capture(values.get("message-class"));
            Path directory = Path.of(values.get("directory"));
            if (args[0].equals("check")) {
                int generation = PersistentSchemaLedger.verify(directory, values.get("genesis"), values.get("head"), snapshot, allowed);
                out.println("generation=" + generation);
            } else {
                var entry = PersistentSchemaLedger.append(directory, values.get("genesis"), values.get("head"), snapshot, allowed);
                out.println("generation=" + entry.generation());
                out.println("head=" + entry.sha256());
                out.println("review-required=update-pinned-head-and-verify");
            }
            return 0;
        } catch (IllegalArgumentException invalid) {
            error.println(invalid.getMessage());
            return 1;
        } catch (Exception invalid) {
            // 不输出反射/路径异常细节；工具输入仅来自本地构建原件。
            error.println("SCHEMA_IO_OR_DESCRIPTOR_FAILED");
            return 2;
        }
    }

    private static PersistentSchemaSnapshot capture(String className) throws ReflectiveOperationException {
        require(className.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"), "MESSAGE_CLASS");
        var method = Class.forName(className).getMethod("getDescriptor");
        require(java.lang.reflect.Modifier.isStatic(method.getModifiers()) && method.getReturnType().equals(Descriptor.class), "MESSAGE_DESCRIPTOR_METHOD");
        return PersistentSchemaSnapshot.capture((Descriptor) method.invoke(null));
    }
}
