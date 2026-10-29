package com.game.data.tools.release;

import static com.game.data.tools.release.ReleaseFailure.require;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 只检查 Boot jar 自带的构建戳；它们不是编译证明、签名、镜像层内容或 registry digest。 */
final class ReleaseJar {
    private ReleaseJar() { }
    static Map<String, String> inspect(Path path, String module, ReleaseSpec spec, String mavenVersion, String bootVersion) throws IOException {
        ReleaseFiles.regular(path);
        require(java.nio.file.Files.size(path) <= ReleaseFiles.MAX_JAR_BYTES, "JAR_SIZE");
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var names = new HashSet<String>();
            var entries = zip.entries();
            long expanded = 0;
            while (entries.hasMoreElements()) {
                ZipEntry current = entries.nextElement();
                require(names.add(current.getName()) && names.size() <= 100_000, "JAR_DUPLICATE_OR_TOO_MANY_ENTRIES");
                require(safeName(current.getName()), "JAR_ENTRY_PATH");
                require(current.getName().length() <= 2048 && current.getSize() >= 0
                        && current.getSize() <= ReleaseFiles.MAX_JAR_BYTES, "JAR_ENTRY_SIZE");
                expanded += current.getSize();
                require(expanded <= 2L * 1024 * 1024 * 1024, "JAR_EXPANDED_SIZE");
            }
            // 两项固定元数据各最多 64 KiB；另读取两个启动 class 的 8 字节头，不按条目路径写盘。
            Manifest manifest = new Manifest(new ByteArrayInputStream(entry(zip, "META-INF/MANIFEST.MF")));
            var attributes = manifest.getMainAttributes();
            String startClass = attributes.getValue("Start-Class");
            require("org.springframework.boot.loader.launch.JarLauncher".equals(attributes.getValue("Main-Class"))
                    && startClass != null && startClass.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"), "JAR_NOT_BOOT");
            require(bootVersion.equals(attributes.getValue("Spring-Boot-Version")), "JAR_BOOT_VERSION");
            classHeader(zip, "org/springframework/boot/loader/launch/JarLauncher.class");
            classHeader(zip, "BOOT-INF/classes/" + startClass.replace('.', '/') + ".class");
            Properties info = properties(entry(zip, "BOOT-INF/classes/META-INF/build-info.properties"));
            require(module.equals(info.getProperty("build.artifact")) && "com.game".equals(info.getProperty("build.group")), "JAR_MODULE");
            require(spec.javaCommit().equals(info.getProperty("build.commit")), "JAR_COMMIT");
            require(spec.contractCommit().equals(info.getProperty("build.contract")), "JAR_CONTRACT");
            require(mavenVersion.equals(info.getProperty("build.version")), "JAR_MAVEN_VERSION");
            if (info.containsKey("build.release")) require(spec.version().equals(info.getProperty("build.release")), "JAR_RELEASE");
            try { Instant.parse(info.getProperty("build.time")); }
            catch (RuntimeException invalid) { throw new ReleaseFailure("JAR_BUILD_TIME"); }
            Map<String, String> selected = new TreeMap<>();
            for (String key : java.util.List.of("build.artifact", "build.group", "build.commit", "build.contract", "build.version", "build.time")) {
                selected.put(key, info.getProperty(key));
            }
            selected.put("build.release", info.getProperty("build.release", "unavailable"));
            return Map.copyOf(selected);
        } catch (java.util.zip.ZipException invalid) { throw new ReleaseFailure("JAR_INVALID"); }
    }

    private static void classHeader(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        require(entry != null && !entry.isDirectory() && entry.getSize() >= 8, "JAR_CLASS_MISSING");
        try (var input = zip.getInputStream(entry)) {
            byte[] header = input.readNBytes(8);
            require(header.length == 8, "JAR_CLASS_HEADER");
            var data = java.nio.ByteBuffer.wrap(header);
            int magic = data.getInt();
            int minor = Short.toUnsignedInt(data.getShort());
            int major = Short.toUnsignedInt(data.getShort());
            require(magic == 0xCAFEBABE && minor == 0 && major >= 45 && major <= 65, "JAR_CLASS_HEADER");
        }
    }

    private static boolean safeName(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":")) return false;
        for (char ch : name.toCharArray()) if (ch < 32 || ch == 127) return false;
        String path = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String segment : path.split("/", -1)) if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        return true;
    }

    static Properties properties(byte[] bytes) {
        Properties values = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                require(!containsKey(key), "PROPERTIES_DUPLICATE");
                return super.put(key, value);
            }
        };
        try { values.load(new ByteArrayInputStream(bytes)); }
        catch (IOException | IllegalArgumentException invalid) { throw new ReleaseFailure("PROPERTIES_INVALID"); }
        return values;
    }
    private static byte[] entry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        require(entry != null && !entry.isDirectory() && entry.getSize() >= 0 && entry.getSize() <= 65536, "JAR_METADATA_MISSING_OR_LARGE");
        try (var input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(65537);
            require(bytes.length <= 65536, "JAR_METADATA_TOO_LARGE");
            return bytes;
        }
    }
}
