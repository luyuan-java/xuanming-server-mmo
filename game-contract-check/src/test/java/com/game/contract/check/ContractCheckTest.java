package com.game.contract.check;

import com.google.protobuf.DescriptorProtos.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static com.game.contract.check.ContractCompatibilityTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** 从磁盘输入跑完整 CLI，验证退出码、机器报告、来源和精确放行绑定。 */
class ContractCheckTest {
    @TempDir Path dir;

    List<String> fixture(FileDescriptorProto.Builder old, FileDescriptorProto.Builder next) throws Exception {
        Files.write(dir.resolve("old.desc"), set(old).toByteArray());
        Files.write(dir.resolve("new.desc"), set(next).toByteArray());
        Files.writeString(dir.resolve("ids.txt"), IDS);
        Files.writeString(dir.resolve("SOURCE.properties"), "mmorpg.commit=" + SHA + "\nmaps.source.commit=" + SHA
                + "\nmessage_id.sha256=" + ContractCompatibility.sha256(IDS) + "\n");
        List<String> args = new ArrayList<>();
        for (String side : List.of("old", "new")) {
            args.addAll(List.of("--" + side + "-desc", dir.resolve(side + ".desc").toString(),
                    "--" + side + "-ids", dir.resolve("ids.txt").toString(),
                    "--" + side + "-source", dir.resolve("SOURCE.properties").toString()));
        }
        args.addAll(List.of("--json", dir.resolve("report.json").toString(), "--text", dir.resolve("report.txt").toString()));
        return args;
    }
    int run(List<String> args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        return ContractCheck.run(args.toArray(String[]::new), stream, stream);
    }

    @Test void safeRunProducesDeterministicLfJsonAndText() throws Exception {
        List<String> args = fixture(app(), app());
        assertEquals(0, run(args));
        String json = Files.readString(dir.resolve("report.json"));
        assertTrue(json.contains("\"exitCode\":0"));
        assertTrue(json.contains("\"scope\":\"client-and-player-service-protobuf-subset\""));
        assertTrue(json.contains("\"notCovered\":[\"transport-frame-and-handshake\""));
        assertTrue(json.contains("\"uncheckedMethods\":[]"));
        assertFalse(json.contains("\r"));
        assertEquals(0, run(args));
        assertEquals(json, Files.readString(dir.resolve("report.json")));
        assertTrue(Files.readString(dir.resolve("report.txt")).startsWith("contract-check-v1 exit=0\n"));
    }

    @Test void breakNeedsExactAcceptedListAndStaleListFails() throws Exception {
        FileDescriptorProto.Builder next = app();
        request(next).getFieldBuilder(0).setType(FieldDescriptorProto.Type.TYPE_BOOL);
        List<String> args = fixture(app(), next);
        assertEquals(1, run(args));
        var oldSurface = surface(app());
        var nextSurface = surface(next);
        var findings = ContractCompatibility.compare(oldSurface, nextSurface);
        StringBuilder accepted = new StringBuilder();
        findings.stream().filter(f -> f.severity() == ContractCompatibility.Severity.BREAK).forEach(f -> accepted
                .append(oldSurface.fingerprint()).append('\t').append(nextSurface.fingerprint()).append('\t')
                .append(f.id()).append("\t评审 #123：强制旧客户端下线\n"));
        Path approval = dir.resolve("accepted.tsv");
        Files.writeString(approval, accepted.toString());
        args.addAll(List.of("--accepted", approval.toString()));
        assertEquals(0, run(args));
        assertTrue(Files.readString(dir.resolve("report.json")).contains("\"accepted\":true"));
        request(next).getFieldBuilder(0).setType(FieldDescriptorProto.Type.TYPE_UINT64);
        Files.write(dir.resolve("new.desc"), set(next).toByteArray());
        assertEquals(2, run(args));
        assertTrue(Files.readString(dir.resolve("report.json")).contains("INVALID_INPUT"));
    }

    @Test void invalidBinaryAndMissingInputFailClosedWithJson() throws Exception {
        List<String> args = fixture(app(), app());
        Files.write(dir.resolve("new.desc"), new byte[] {10, (byte) 255, (byte) 255});
        assertEquals(2, run(args));
        assertTrue(Files.readString(dir.resolve("report.json")).contains("\"exitCode\":2"));
        Files.delete(dir.resolve("new.desc"));
        assertEquals(2, run(args));
    }

    @Test void sourceHashMismatchAndDuplicateSourceKeysFail() throws Exception {
        List<String> args = fixture(app(), app());
        Files.writeString(dir.resolve("ids.txt"), IDS + "2=reserved:Other\n");
        assertEquals(2, run(args));
        Files.writeString(dir.resolve("ids.txt"), IDS);
        Files.writeString(dir.resolve("SOURCE.properties"), Files.readString(dir.resolve("SOURCE.properties")) + "maps.source.commit=" + SHA + "\n");
        assertEquals(2, run(args));
    }

    @Test void outputCannotOverwriteAnyInput() throws Exception {
        List<String> args = fixture(app(), app());
        byte[] descriptor = Files.readAllBytes(dir.resolve("old.desc"));
        args.set(args.indexOf("--json") + 1, dir.resolve("old.desc").toString());
        assertEquals(2, run(args));
        assertArrayEquals(descriptor, Files.readAllBytes(dir.resolve("old.desc")));
    }

    @Test void outputHardLinksCannotOverwriteEachOther() throws Exception {
        List<String> args = fixture(app(), app());
        Path json = dir.resolve("report.json"), text = dir.resolve("report.txt");
        Files.writeString(json, "preserve-original");
        Files.createLink(text, json);
        assertEquals(2, run(args));
        assertEquals("preserve-original", Files.readString(json));
        assertEquals("preserve-original", Files.readString(text));
    }

    @Test void outputHardLinkCannotOverwriteInput() throws Exception {
        List<String> args = fixture(app(), app());
        Path original = dir.resolve("old.desc");
        byte[] bytes = Files.readAllBytes(original);
        Files.createLink(dir.resolve("report.json"), original);
        assertEquals(2, run(args));
        assertArrayEquals(bytes, Files.readAllBytes(original));
    }

    @Test void explicitBreakAndDuplicateArgumentsAreNotPermission() throws Exception {
        List<String> args = fixture(app(), app());
        args.addAll(List.of("--explicit-break", "true"));
        assertEquals(2, run(args));
        args.removeLast(); args.removeLast();
        args.addAll(List.of("--json", dir.resolve("other.json").toString()));
        assertEquals(2, run(args));
    }

    @Test void jsonEscapesEvidenceControlCharacters() {
        var finding = new ContractCompatibility.Finding(ContractCompatibility.Severity.BREAK, "TEST", "p",
                "quote\"slash\\tab\t", "line\nreturn\r\u0001");
        String json = ContractCheck.json("old", "new", List.of(finding), java.util.Map.of(finding.id(), "理由\n两行"), 0);
        assertTrue(json.contains("quote\\\"slash\\\\tab\\t"));
        assertTrue(json.contains("line\\nreturn\\r\\u0001"));
        assertTrue(json.contains("理由\\n两行"));
    }
}
