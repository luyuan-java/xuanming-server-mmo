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

/** 当前三张地图与客户端共用的保守栅格。服务器 (x,y,z) = Unity (z,x,y)。 */
public final class CurrentMapNavigation implements SceneNavigation {
    private static final int RESOLUTION = 150;
    private static final double CELL_SIZE = 2;
    private static final double BACKOFF = 0.05;
    private final byte[] mask;
    private final Vec3 spawn;

    CurrentMapNavigation(byte[] mask, Vec3 spawn) {
        if (mask.length != (RESOLUTION * RESOLUTION + 7) / 8 || (mask[mask.length - 1] & 15) != 0) {
            throw new IllegalArgumentException("当前地图遮罩长度或尾部填充位错误");
        }
        this.mask = mask.clone();
        this.spawn = spawn;
        if (!isWalkable(spawn) || spawn.z() != 0) {
            throw new IllegalArgumentException("当前地图出生点不在可走地面上");
        }
    }

    /** 仅启动或配置重载时读文件；运行中的场景逻辑线程不做 I/O。 */
    public static CurrentMapNavigation load(Path root, String key) {
        Path folder = root.resolve(key);
        try {
            JsonNode source = new ObjectMapper().readTree(Files.readAllBytes(folder.resolve("source.json")));
            byte[] text = Files.readAllBytes(folder.resolve("walkmask.txt"));
            byte[] packed = Base64.getDecoder().decode(text);
            JsonNode rect = source.path("worldRect");
            JsonNode point = source.path("spawnWorld");
            int sceneId = switch (key) {
                case "main" -> 1;
                case "island" -> 2;
                case "village" -> 3;
                default -> throw new IllegalArgumentException("未知当前地图: " + key);
            };
            if (source.path("schemaVersion").asInt() != 1 || !key.equals(source.path("key").asText())
                    || source.path("sceneId").asInt() != sceneId
                    || source.path("walkmaskResolution").asInt() != RESOLUTION
                    || source.path("cellSize").asDouble() != CELL_SIZE
                    || rect.path("x").asDouble() != 50 || rect.path("y").asDouble() != 0
                    || rect.path("width").asDouble() != 300 || rect.path("height").asDouble() != 300
                    || !rect.path("x").isNumber() || !rect.path("y").isNumber()
                    || !rect.path("width").isNumber() || !rect.path("height").isNumber()
                    || !source.path("artStatus").asText().equals("layout_preview")
                    || !source.path("navigationStatus").asText().equals("conservative_visible_ground")
                    || !source.path("maskEncoding").asText().equals("base64-msb-first-row-major-top-to-bottom")
                    || !sha256(text).equals(source.path("walkmaskSha256").asText())
                    || !sha256(packed).equals(source.path("walkmaskPackedSha256").asText())
                    || !point.path("x").isNumber() || !point.path("y").isNumber() || !point.path("z").isNumber()) {
                throw new IllegalArgumentException("当前地图合同或 SHA256 不一致: " + folder);
            }
            Vec3 spawn = new Vec3(point.get("z").asDouble(), point.get("x").asDouble(), point.get("y").asDouble());
            CurrentMapNavigation navigation = new CurrentMapNavigation(packed, spawn);
            int count = 0;
            for (int row = 0; row < RESOLUTION; row++) {
                for (int col = 0; col < RESOLUTION; col++) {
                    if (navigation.cell(col, row)) count++;
                }
            }
            if (count != source.path("walkableCellCount").asInt()) {
                throw new IllegalArgumentException("当前地图可走格计数不一致: " + folder);
            }
            return navigation;
        } catch (IOException e) {
            throw new UncheckedIOException("读取当前地图导航失败: " + folder, e);
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Vec3 spawn() { return spawn; }

    @Override
    public boolean isWalkable(Vec3 point) {
        return point.isFinite() && point.y() >= 50 && point.y() < 350 && point.x() >= 0 && point.x() < 300
                && cell((int) Math.floor((point.y() - 50) / CELL_SIZE), (int) Math.floor(point.x() / CELL_SIZE));
    }

    /** col 向 Unity x 正向，row 向 Unity z 正向；存盘位序的行则从图片上方开始。 */
    private boolean cell(int col, int row) {
        if (col < 0 || row < 0 || col >= RESOLUTION || row >= RESOLUTION) return false;
        int bit = (RESOLUTION - 1 - row) * RESOLUTION + col;
        return (mask[bit / 8] & (1 << (7 - bit % 8))) != 0;
    }

    @Override
    public Vec3 velocity(Vec3 velocity) { return new Vec3(velocity.x(), velocity.y(), 0); }

    @Override
    public Move trace(Vec3 from, Vec3 to) {
        if (!isWalkable(from)) return new Move(spawn, true);
        if (!MovementRules.insideWorld(to)) return new Move(new Vec3(from.x(), from.y(), 0), true);
        double u = (from.y() - 50) / CELL_SIZE;
        double v = from.x() / CELL_SIZE;
        double du = (to.y() - from.y()) / CELL_SIZE;
        double dv = (to.x() - from.x()) / CELL_SIZE;
        int col = (int) Math.floor(u), row = (int) Math.floor(v);
        int sx = du > 0 ? 1 : du < 0 ? -1 : 0;
        int sy = dv > 0 ? 1 : dv < 0 ? -1 : 0;
        double deltaX = sx == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(du);
        double deltaY = sy == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dv);
        double nextX = sx > 0 ? (col + 1 - u) / du : sx < 0 ? (col - u) / du : Double.POSITIVE_INFINITY;
        double nextY = sy > 0 ? (row + 1 - v) / dv : sy < 0 ? (row - v) / dv : Double.POSITIVE_INFINITY;
        // 每次至少跨过一条格线；越出 150² 世界边界立即阻挡，无需采样步长或无限循环。
        while (Math.min(nextX, nextY) <= 1) {
            double at = Math.min(nextX, nextY);
            boolean crossX = nextX <= nextY + 1e-12;
            boolean crossY = nextY <= nextX + 1e-12;
            if ((crossX && !cell(col + sx, row)) || (crossY && !cell(col, row + sy))
                    || (crossX && crossY && !cell(col + sx, row + sy))) {
                return blocked(from, to, at);
            }
            if (crossX) { col += sx; nextX += deltaX; }
            if (crossY) { row += sy; nextY += deltaY; }
        }
        return new Move(new Vec3(to.x(), to.y(), 0), false);
    }

    private Move blocked(Vec3 from, Vec3 to, double at) {
        double distance = from.horizontalDistance(to);
        double fraction = Math.max(0, at - (distance > 0 ? BACKOFF / distance : 0));
        return new Move(new Vec3(from.x() + (to.x() - from.x()) * fraction,
                from.y() + (to.y() - from.y()) * fraction, 0), true);
    }
}
