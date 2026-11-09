package com.game.contract.check.persist;

import static org.assertj.core.api.Assertions.*;
import static com.game.contract.check.persist.PersistentSchemaLedgerTest.*;
import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.*;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistentSchemaToolTest {
    @TempDir Path temporary;
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    @Test void explicitSnapshotUsesActualDescriptorAndNeverOverwrites() throws Exception {
        Path file = temporary.resolve("0001.schema");
        assertThat(run("snapshot", "--message-class", Original.class.getName(), "--output", file.toString())).isZero();
        byte[] before = Files.readAllBytes(file);
        assertThat(before).isEqualTo(PersistentSchemaLedger.genesisBytes(PersistentSchemaSnapshot.capture(Original.getDescriptor())));
        assertThat(stdout.toString(StandardCharsets.UTF_8)).contains("root=persist.Root", "genesis=");
        assertThat(run("snapshot", "--message-class", Original.class.getName(), "--output", file.toString())).isEqualTo(2);
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test void checkIsReadOnlyAndAppendReportsNewHeadForReview() throws Exception {
        Path file = temporary.resolve("0001.schema");
        assertThat(run("snapshot", "--message-class", Original.class.getName(), "--output", file.toString())).isZero();
        String genesis = PersistentSchemaSnapshot.sha256(Files.readAllBytes(file));
        assertThat(check("check", Original.class, genesis, genesis)).isZero();
        assertThat(check("check", Added.class, genesis, genesis)).isEqualTo(1);
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("SCHEMA_NOT_REGISTERED");
        assertThat(Files.exists(temporary.resolve("0002.schema"))).isFalse();
        assertThat(check("append", Added.class, genesis, genesis)).isZero();
        String head = PersistentSchemaSnapshot.sha256(Files.readAllBytes(temporary.resolve("0002.schema")));
        assertThat(check("check", Added.class, genesis, head)).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8)).contains("review-required=", "generation=2");
    }

    @Test void invalidOrDuplicateArgumentsFailBeforeWriting() {
        assertThat(run("snapshot", "--message-class", Original.class.getName())).isEqualTo(1);
        assertThat(run("snapshot", "--output", "x", "--output", "y")).isEqualTo(1);
        assertThat(run("erase", "--directory", temporary.toString())).isEqualTo(1);
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("ARGUMENTS");
    }

    @Test void wrongDescriptorClassNeverProducesSnapshot() {
        Path file = temporary.resolve("0001.schema");
        assertThat(run("snapshot", "--message-class", String.class.getName(), "--output", file.toString())).isEqualTo(2);
        assertThat(Files.exists(file)).isFalse();
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("SCHEMA_IO_OR_DESCRIPTOR_FAILED");
    }

    @Test void prefixCommandRequiresTrustedBaselineHead() throws Exception {
        Path baseline = Files.createDirectory(temporary.resolve("baseline"));
        Path candidate = Files.createDirectory(temporary.resolve("candidate"));
        byte[] first = PersistentSchemaLedger.genesisBytes(PersistentSchemaSnapshot.capture(Original.getDescriptor()));
        Files.write(baseline.resolve("0001.schema"), first);
        Files.write(candidate.resolve("0001.schema"), first);
        String hash = PersistentSchemaSnapshot.sha256(first);
        assertThat(run("prefix", "--baseline", baseline.toString(), "--candidate", candidate.toString(),
                "--genesis", hash, "--baseline-head", hash, "--candidate-head", hash)).isZero();
        assertThat(run("prefix", "--baseline", baseline.toString(), "--candidate", candidate.toString(),
                "--genesis", hash, "--baseline-head", "f".repeat(64), "--candidate-head", hash)).isEqualTo(1);
    }

    private int check(String action, Class<?> message, String genesis, String head) {
        return run(action, "--message-class", message.getName(), "--directory", temporary.toString(),
                "--genesis", genesis, "--head", head, "--allow-additions", "persist.Root");
    }
    private int run(String... args) {
        return PersistentSchemaTool.run(args, new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }
    public static final class Original {
        public static Descriptor getDescriptor() { return descriptor(false); }
    }
    public static final class Added {
        public static Descriptor getDescriptor() { return descriptor(true); }
    }
    private static Descriptor descriptor(boolean added) {
        try {
            var root = root().addField(field("a", 1, TYPE_UINT64));
            if (added) root.addField(field("b", 2, TYPE_STRING));
            var file = FileDescriptorProto.newBuilder().setName("state.proto").setPackage("persist").setSyntax("proto3").addMessageType(root);
            return FileDescriptor.buildFrom(file.build(), new FileDescriptor[0]).findMessageTypeByName("Root");
        } catch (Exception impossible) { throw new ExceptionInInitializerError(impossible); }
    }
}
