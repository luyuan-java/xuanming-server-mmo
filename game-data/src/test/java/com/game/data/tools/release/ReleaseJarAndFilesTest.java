package com.game.data.tools.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseJarAndFilesTest {
    @TempDir Path root;
    private Path jar(String tail, String extraName) throws IOException {
        Path jar = root.resolve("app.jar");
        ReleaseFixture.jar(jar, "game-data", tail, extraName);
        return jar;
    }
    private void inspect(Path path) throws IOException {
        ReleaseJar.inspect(path, "game-data", ReleaseFixture.spec(), ReleaseFixture.MAVEN, ReleaseFixture.BOOT);
    }

    @Test void duplicatePropertiesAndMismatchedReleaseReject() throws Exception {
        assertThatThrownBy(() -> inspect(jar("build.commit=unknown\n", ""))).hasMessage("PROPERTIES_DUPLICATE");
        assertThatThrownBy(() -> inspect(jar("build.release=v0.0.1\n", ""))).hasMessage("JAR_RELEASE");
        inspect(jar("build.release=v2.10.0\n", ""));
    }

    @Test void metadataClaimsMustMatchSourceSelection() throws Exception {
        Path jar = jar("", "");
        assertThatThrownBy(() -> ReleaseJar.inspect(jar, "game-gate", ReleaseFixture.spec(), ReleaseFixture.MAVEN, ReleaseFixture.BOOT)).hasMessage("JAR_MODULE");
        assertThatThrownBy(() -> ReleaseJar.inspect(jar, "game-data", ReleaseFixture.spec(), "9.0", ReleaseFixture.BOOT)).hasMessage("JAR_MAVEN_VERSION");
        assertThatThrownBy(() -> ReleaseJar.inspect(jar, "game-data", ReleaseFixture.spec(), ReleaseFixture.MAVEN, "1.0")).hasMessage("JAR_BOOT_VERSION");
        ReleaseSpec spec = ReleaseFixture.spec();
        ReleaseSpec wrongCommit = new ReleaseSpec(spec.format(), spec.version(), "d".repeat(40), spec.contractCommit(), spec.mapsCommit(),
                spec.namespace(), spec.routeGeneration(), spec.directoryProtocol(), spec.rolloutStrategy(), spec.jars(), spec.settings(), spec.secretVersions(), spec.migrationPlans());
        assertThatThrownBy(() -> ReleaseJar.inspect(jar, "game-data", wrongCommit, ReleaseFixture.MAVEN, ReleaseFixture.BOOT)).hasMessage("JAR_COMMIT");
        ReleaseSpec wrongContract = new ReleaseSpec(spec.format(), spec.version(), spec.javaCommit(), "d".repeat(40), spec.mapsCommit(),
                spec.namespace(), spec.routeGeneration(), spec.directoryProtocol(), spec.rolloutStrategy(), spec.jars(), spec.settings(), spec.secretVersions(), spec.migrationPlans());
        assertThatThrownBy(() -> ReleaseJar.inspect(jar, "game-data", wrongContract, ReleaseFixture.MAVEN, ReleaseFixture.BOOT)).hasMessage("JAR_CONTRACT");
    }

    @Test void zipTraversalIsRejectedWithoutExtraction() throws Exception {
        assertThatThrownBy(() -> inspect(jar("", "../escaped.txt"))).hasMessage("JAR_ENTRY_PATH");
        assertThat(root.resolve("escaped.txt")).doesNotExist();
    }

    @Test void duplicateZipEntriesRejectEvenWhenMetadataIsUnique() throws Exception {
        Path path = jar("", "META-INF/MANIFEST.MX");
        byte[] bytes = Files.readAllBytes(path);
        byte[] before = "META-INF/MANIFEST.MX".getBytes(StandardCharsets.US_ASCII);
        byte[] after = "META-INF/MANIFEST.MF".getBytes(StandardCharsets.US_ASCII);
        int matches = 0;
        for (int i = 0; i <= bytes.length - before.length; i++) {
            if (Arrays.equals(bytes, i, i + before.length, before, 0, before.length)) {
                System.arraycopy(after, 0, bytes, i, after.length);
                matches++;
            }
        }
        assertThat(matches).isEqualTo(2); // 本地头 + 中央目录，保持其他 ZIP 原件不变。
        Files.write(path, bytes);
        assertThatThrownBy(() -> inspect(path)).hasMessage("JAR_DUPLICATE_OR_TOO_MANY_ENTRIES");
    }

    @Test void zipDeclaredExpansionBudgetRejectsBeforeDecompression() throws Exception {
        Path path = jar("", "unread.bin");
        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int changed = 0;
        for (int i = 0; i + 46 < bytes.length; i++) {
            if (buffer.getInt(i) == 0x02014b50) { buffer.putInt(i + 24, (int) ReleaseFiles.MAX_JAR_BYTES + 1); changed++; }
        }
        assertThat(changed).isEqualTo(5);
        Files.write(path, bytes);
        assertThatThrownBy(() -> inspect(path)).hasMessage("JAR_ENTRY_SIZE");
    }

    @Test void metadataDeclaredSizeIsBoundedBeforeDecompression() throws Exception {
        assertThatThrownBy(() -> inspect(jar("unused=" + "x".repeat(65536), ""))).hasMessage("JAR_METADATA_MISSING_OR_LARGE");
    }

    @Test void metadataActualDecompressionIsBoundedWhenDeclaredSizeLies() throws Exception {
        Path path = jar("unused=" + "x".repeat(65536), "");
        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int changed = 0;
        for (int i = 0; i + 46 <= bytes.length; i++) {
            if (buffer.getInt(i) != 0x02014b50) continue;
            int nameLength = Short.toUnsignedInt(buffer.getShort(i + 28));
            if (i + 46 + nameLength > bytes.length) continue;
            String name = new String(bytes, i + 46, nameLength, StandardCharsets.UTF_8);
            if (!name.equals("BOOT-INF/classes/META-INF/build-info.properties")) continue;
            assertThat(Short.toUnsignedInt(buffer.getShort(i + 10))).isEqualTo(8);
            assertThat(buffer.getInt(i + 24)).isGreaterThan(65536);
            // 只让中央目录谎报解压长度；保留压缩体、压缩长度和 CRC，迫使实际读取预算生效。
            buffer.putInt(i + 24, 1);
            changed++;
        }
        assertThat(changed).isEqualTo(1);
        Files.write(path, bytes);
        assertThatThrownBy(() -> inspect(path)).hasMessage("JAR_METADATA_TOO_LARGE");
    }

    @Test void missingStartupClassCannotPassAsBootJar() throws Exception {
        Path path = jar("", "");
        rewriteEntry(path, "BOOT-INF/classes/example/Main.class", null);
        assertThatThrownBy(() -> inspect(path)).hasMessage("JAR_CLASS_MISSING");
    }

    @Test void wrongLoaderClassHeaderAndFutureJavaVersionReject() throws Exception {
        Path path = jar("", "");
        rewriteEntry(path, "org/springframework/boot/loader/launch/JarLauncher.class", new byte[8]);
        assertThatThrownBy(() -> inspect(path)).hasMessage("JAR_CLASS_HEADER");
        Path future = jar("", "");
        byte[] header = ReleaseFixture.classHeader();
        header[7] = 66;
        rewriteEntry(future, "BOOT-INF/classes/example/Main.class", header);
        assertThatThrownBy(() -> inspect(future)).hasMessage("JAR_CLASS_HEADER");
    }

    private static void rewriteEntry(Path jar, String name, byte[] replacement) throws IOException {
        Path rewritten = jar.resolveSibling("rewritten.jar");
        try (var input = new java.util.zip.ZipFile(jar.toFile());
             var output = new java.util.zip.ZipOutputStream(Files.newOutputStream(rewritten))) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.getName().equals(name) && replacement == null) continue;
                output.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                if (entry.getName().equals(name)) output.write(replacement);
                else try (var bytes = input.getInputStream(entry)) { bytes.transferTo(output); }
                output.closeEntry();
            }
        }
        Files.move(rewritten, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Test void immutableWriteDoesNotTruncateExistingFile() throws Exception {
        ReleaseFiles.writeNew(root, "original.txt", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> ReleaseFiles.writeNew(root, "original.txt", new byte[]{4})).isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readAllBytes(root.resolve("original.txt"))).containsExactly(1, 2, 3);
    }

    @Test void inventoryBoundsDepthWhileStreamingDirectories() throws Exception {
        Path deep = root;
        for (int i = 0; i < 33; i++) deep = Files.createDirectory(deep.resolve("a"));
        assertThatThrownBy(() -> ReleaseFiles.inventory(root)).hasMessage("PATH_DEPTH_LIMIT");
    }

    @Test void symlinkCannotBeTraversedOrInventoried() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path bundle = Files.createDirectory(root.resolve("bundle"));
        Path link = bundle.resolve("linked");
        try { Files.createSymbolicLink(link, outside); }
        catch (UnsupportedOperationException | IOException unavailable) {
            Assumptions.assumeTrue(false, "当前文件系统不允许建立符号链接；此用例未验收");
        }
        assertThatThrownBy(() -> ReleaseFiles.safe(bundle, "linked/escaped.txt")).hasMessage("SYMLINK_OR_SPECIAL_FILE_REJECTED");
        assertThatThrownBy(() -> ReleaseFiles.inventory(bundle)).hasMessage("SYMLINK_OR_SPECIAL_FILE_REJECTED");
    }

    @Test void windowsJunctionCannotEscapeSourceOrOutputRoot() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path bundle = Files.createDirectory(root.resolve("bundle"));
        Path link = bundle.resolve("junction");
        // 仅 @TempDir 内创建 junction，不执行删除/移动命令，不依赖管理员符号链接权限。
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), outside.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            assertThat(Files.readAttributes(link, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS).isOther()).isTrue();
            assertThatThrownBy(() -> ReleaseFiles.safe(bundle, "junction/escaped.txt")).hasMessage("SYMLINK_OR_SPECIAL_FILE_REJECTED");
            assertThatThrownBy(() -> ReleaseFiles.inventory(bundle)).hasMessage("SYMLINK_OR_SPECIAL_FILE_REJECTED");
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
