package com.game.data.tools.release;

import static com.game.data.tools.release.ReleaseFailure.require;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** 包内路径不解释转义，不跟随符号链接，不覆盖已有文件；所有内容摘要按原字节计算。 */
final class ReleaseFiles {
    static final long MAX_JAR_BYTES = 512L * 1024 * 1024;
    static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4",
            "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private ReleaseFiles() { }

    static String relative(String value) {
        require(value != null && value.length() <= 240 && !value.isEmpty(), "PATH_INVALID");
        for (String part : value.split("/", -1)) {
            require(part.matches("[A-Za-z0-9_][A-Za-z0-9._-]*") && !part.endsWith(".")
                    && !RESERVED.contains(part.split("\\.", 2)[0].toUpperCase(Locale.ROOT)), "PATH_INVALID");
        }
        return value;
    }

    static Path safe(Path root, String name) throws IOException {
        Path result = root.toAbsolutePath().normalize().resolve(relative(name));
        noLinks(result);
        return result;
    }

    static void noLinks(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path segment : absolute) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                rejectLink(attributes);
                require(current.equals(absolute) || attributes.isDirectory(), "PATH_PARENT_INVALID");
            }
        }
    }

    private static void rejectLink(BasicFileAttributes attributes) {
        // Windows junction 属于 isOther 而不是 isSymbolicLink；同样不能穿越。
        require(!attributes.isSymbolicLink() && !attributes.isOther(), "SYMLINK_OR_SPECIAL_FILE_REJECTED");
    }

    static void regular(Path path) throws IOException {
        noLinks(path);
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "FILE_REQUIRED");
    }

    static byte[] read(Path path, int limit) throws IOException {
        regular(path);
        require(Files.size(path) <= limit, "FILE_TOO_LARGE");
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            require(bytes.length <= limit, "FILE_TOO_LARGE");
            return bytes;
        }
    }

    static String hash(Path path, long limit) throws IOException {
        regular(path);
        require(Files.size(path) <= limit, "FILE_TOO_LARGE");
        MessageDigest digest = digest("SHA-256");
        long total = 0;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) >= 0;) {
                total += count;
                require(total <= limit, "FILE_TOO_LARGE");
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String hash(byte[] bytes) { return HexFormat.of().formatHex(digest("SHA-256").digest(bytes)); }
    static String gitBlob(byte[] bytes) {
        MessageDigest digest = digest("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(digest.digest(bytes));
    }
    private static MessageDigest digest(String name) {
        try { return MessageDigest.getInstance(name); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("JDK_DIGEST_UNAVAILABLE"); }
    }

    static void writeNew(Path root, String name, byte[] bytes) throws IOException {
        Path destination = safe(root, name);
        Files.createDirectories(destination.getParent());
        noLinks(destination);
        try (FileChannel channel = FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer data = ByteBuffer.wrap(bytes);
            while (data.hasRemaining()) channel.write(data);
            channel.force(true);
        }
    }

    static void copyNew(Path root, String name, Path input) throws IOException {
        regular(input);
        require(Files.size(input) > 0 && Files.size(input) <= MAX_JAR_BYTES, "JAR_SIZE");
        Path destination = safe(root, name);
        Files.createDirectories(destination.getParent());
        noLinks(destination);
        try (InputStream source = Files.newInputStream(input, LinkOption.NOFOLLOW_LINKS);
             FileChannel channel = FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536];
            long total = 0;
            for (int count; (count = source.read(buffer)) >= 0;) {
                total += count;
                require(total <= MAX_JAR_BYTES, "JAR_SIZE");
                ByteBuffer data = ByteBuffer.wrap(buffer, 0, count);
                while (data.hasRemaining()) channel.write(data);
            }
            channel.force(true);
        }
    }

    static Set<String> inventory(Path root) throws IOException {
        noLinks(root);
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "BUNDLE_DIRECTORY");
        Set<String> files = new TreeSet<>();
        Set<String> folded = new TreeSet<>();
        Files.walkFileTree(root, Set.of(), 33, new SimpleFileVisitor<>() {
            private int count;
            private void check(Path path, BasicFileAttributes attributes) throws IOException {
                rejectLink(attributes);
                require(++count <= 20_000, "FILE_COUNT_LIMIT");
                require(root.relativize(path).getNameCount() <= 32, "PATH_DEPTH_LIMIT");
                if (path.equals(root)) return;
                String name = relative(root.relativize(path).toString().replace('\\', '/'));
                require(folded.add(name.toLowerCase(Locale.ROOT)), "PATH_CASE_COLLISION");
            }
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                check(path, attributes);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                check(path, attributes);
                require(attributes.isRegularFile(), "FILE_REQUIRED");
                files.add(relative(root.relativize(path).toString().replace('\\', '/')));
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }
}
