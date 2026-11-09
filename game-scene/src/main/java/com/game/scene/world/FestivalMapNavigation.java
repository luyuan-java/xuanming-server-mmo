package com.game.scene.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/** 固定九图的开发联调读取器；来源原字节仍保留 clientOnly，不代表正式激活。 */
public final class FestivalMapNavigation {
    // server-handoff-data.json 8c8678bf… 中 canonicalAppearance 的 source.json SHA。
    // source 又绑定编码/解码 mask SHA，因此无法用自洽的新 mask 擅自开放未验收道路。
    private static final Map<String, String> SOURCE_SHA = Map.ofEntries(
            Map.entry("01001", "e91225281ce556e67cd6e591a1699756769de316082d3f5d0df634f9ae4bee98"),
            Map.entry("01002", "d0ad450b7c1c814b20f08b9093c32c30ed0d3ae479ccee0f8dc70967ebe7e419"),
            Map.entry("05008", "7faa2069bfd41748307907fc370c8a18749e93b3854d65ffdb4530f7060bb46e"),
            Map.entry("10000", "cb34f10809a3bab290f9b2c3b8563f026d561aeed4d39b91c23ce01613783a74"),
            Map.entry("19004", "40f4df3d2ba34b7690ae14b8c2a2fdc6b05bb84a829a0c9f13bf3fd18c243302"),
            Map.entry("21003", "1ea4b27d26c3e743d8e94b4b7e9028e202ed06d37f4acad8a3d1825f38abdc36"),
            Map.entry("23006", "f0a9c4a85b90c8d9bc8d11bf1866f36f662b4d2830a3e64feff7dc95c54e7e8f"),
            Map.entry("28007", "932c3d2f0224181b5b0cf887893daed910d33b9a440740ab59ad382531b45b89"),
            Map.entry("35004", "fdd34f9117bd20df408ca70f97df69ce9ac12682a1ee426ef6446d69ae568107"));

    private FestivalMapNavigation() { }

    public static String paddedId(int sceneId) {
        String key = String.format(java.util.Locale.ROOT, "%05d", sceneId);
        return SOURCE_SHA.containsKey(key) ? key : null;
    }

    /** 只在装配时加载不可变快照；缺文件、错误版本、伪造导航均拒启。 */
    public static CurrentMapNavigation load(Path root, String paddedId) {
        String expected = SOURCE_SHA.get(paddedId);
        if (expected == null) throw new IllegalArgumentException("非本批节庆地图: " + paddedId);
        Path folder = root.resolve(paddedId);
        try {
            byte[] sourceBytes = Files.readAllBytes(folder.resolve("source.json"));
            if (!expected.equals(sha256(sourceBytes))) {
                throw new IllegalArgumentException("节庆 source.json SHA256 不匹配冻结交接: " + paddedId);
            }
            JsonNode source = new ObjectMapper().readTree(sourceBytes);
            byte[] text = Files.readAllBytes(folder.resolve("walkmask.txt"));
            byte[] mask = Base64.getDecoder().decode(text);
            if (source.path("schemaVersion").asInt() != 2
                    || !source.path("clientOnly").asBoolean()
                    || source.path("authoritativeServerRegistered").asBoolean()
                    || source.path("sceneId").asInt() != Integer.parseInt(paddedId)
                    || !("map" + paddedId).equals(source.path("key").asText())
                    || !sha256(text).equals(source.path("walkmaskSha256").asText())
                    || !sha256(mask).equals(source.path("walkmaskPackedSha256").asText())) {
                throw new IllegalArgumentException("节庆导航合同或 SHA256 不一致: " + paddedId);
            }
            JsonNode world = source.path("worldRect"), effective = source.path("effectiveWorldRect");
            double x = world.path("x").asDouble(), z = world.path("y").asDouble();
            double size = source.path("cellSize").asDouble();
            int columns = source.path("walkmaskColumns").asInt(), rows = source.path("walkmaskRows").asInt();
            if (columns != (int) Math.ceil(world.path("width").asDouble() / size)
                    || rows != (int) Math.ceil(world.path("height").asDouble() / size)) {
                throw new IllegalArgumentException("节庆导航宽高不匹配: " + paddedId);
            }
            JsonNode spawn = source.path("spawnWorld");
            return new CurrentMapNavigation(mask,
                    new Vec3(spawn.path("z").asDouble(), spawn.path("x").asDouble(), spawn.path("y").asDouble()),
                    columns, rows, size, x, z,
                    Math.max(x, effective.path("x").asDouble()),
                    Math.max(z, effective.path("y").asDouble()),
                    Math.min(x + world.path("width").asDouble(), effective.path("x").asDouble() + effective.path("width").asDouble()),
                    Math.min(z + world.path("height").asDouble(), effective.path("y").asDouble() + effective.path("height").asDouble()));
        } catch (IOException e) {
            throw new UncheckedIOException("读取节庆导航失败: " + folder, e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
