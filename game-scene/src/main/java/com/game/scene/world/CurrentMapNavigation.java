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

/** 与客户端共用的保守矩形栅格。服务器 (x,y,z) = Unity (z,x,y)。 */
public final class CurrentMapNavigation implements SceneNavigation {
    private static final String YUEXI_REVISION = "yuexi-bay-20261115";
    private static final String YUEXI_SOURCE_SHA = "99fec92614867c04f1c3cdd6bcf6e8bd0d3235d3b44409c3db6a3174951c8a86";
    static final String YUEXI_FOLDER = "village/" + YUEXI_REVISION;
    private static final int RESOLUTION = 150;
    private static final double CELL_SIZE = 2;
    private static final double BACKOFF = 0.05;
    private final byte[] mask;
    private final Vec3 spawn;
    private final int columns, rows;
    private final double originX, originZ, cellSize;
    private final double minX, minZ, maxX, maxZ;
    private final int[] connectedDomains;
    private final boolean requiresConnectedTeleport;

    CurrentMapNavigation(byte[] mask, Vec3 spawn) {
        this(mask, spawn, RESOLUTION, RESOLUTION, CELL_SIZE, 50, 0, 50, 0, 350, 300);
    }

    /** 场景 3 只有引用正式版本化资源路径时才切换到月汐湾矩形栅格。 */
    static CurrentMapNavigation loadYuexi(Path root) {
        Path folder = root.resolve(YUEXI_FOLDER);
        try {
            byte[] sourceBytes = Files.readAllBytes(folder.resolve("source.json"));
            if (!YUEXI_SOURCE_SHA.equals(sha256(sourceBytes))) {
                throw new IllegalArgumentException("月汐湾 source.json SHA256 不匹配正式交接: " + folder);
            }
            JsonNode source = new ObjectMapper().readTree(sourceBytes);
            if (!source.path("schemaVersion").isIntegralNumber() || source.path("schemaVersion").intValue() != 2
                    || !source.path("sceneId").isIntegralNumber() || source.path("sceneId").intValue() != 3
                    || !"village".equals(source.path("key").asText())
                    || !YUEXI_REVISION.equals(source.path("revision").asText())
                    || !"base64-msb-first-row-major-top-to-bottom".equals(source.path("maskEncoding").asText())
                    || !rectangleEquals(source.path("worldRect"), 0, 0, 86.4, 72)
                    || !rectangleEquals(source.path("effectiveWorldRect"), 0, 0, 72, 72)
                    || coordinate(source, "cellSize") != 0.5) {
                throw new IllegalArgumentException("月汐湾导航身份或世界矩形不一致: " + folder);
            }
            CurrentMapNavigation map = loadRectangular(source, Files.readAllBytes(folder.resolve("walkmask.txt")), folder);
            int count = 0;
            for (int row = 0; row < map.rows; row++) {
                for (int col = 0; col < map.columns; col++) {
                    if (!map.cell(col, row)) continue;
                    int label = map.connectedDomains[row * map.columns + col];
                    if (label != 1) {
                        throw new IllegalArgumentException("月汐湾导航含有效范围外或与出生点断开的可走格: " + folder);
                    }
                    count++;
                }
            }
            if (count != positiveInteger(source, "walkableCellCount")) {
                throw new IllegalArgumentException("月汐湾可走格计数不一致: " + folder);
            }
            return map;
        } catch (IOException e) {
            throw new UncheckedIOException("读取月汐湾导航失败: " + folder, e);
        }
    }

    private static boolean rectangleEquals(JsonNode rect, double x, double z, double width, double height) {
        return coordinate(rect, "x") == x && coordinate(rect, "y") == z
                && coordinate(rect, "width") == width && coordinate(rect, "height") == height;
    }

    /** 矩形栅格使用世界原点定位；有效区域可截断末行、末列，不能扩张到整格。 */
    CurrentMapNavigation(byte[] mask, Vec3 spawn, int columns, int rows, double cellSize,
                         double originX, double originZ, double minX, double minZ, double maxX, double maxZ) {
        this(mask, spawn, columns, rows, cellSize, originX, originZ, minX, minZ, maxX, maxZ, false);
    }

    private CurrentMapNavigation(byte[] mask, Vec3 spawn, int columns, int rows, double cellSize,
                                 double originX, double originZ, double minX, double minZ, double maxX, double maxZ,
                                 boolean requiresConnectedTeleport) {
        long cells = (long) columns * rows;
        if (columns <= 0 || rows <= 0 || cells > 1_000_000 || mask.length != (cells + 7) / 8
                || !Double.isFinite(cellSize) || cellSize <= 0
                || !Double.isFinite(originX) || !Double.isFinite(originZ)
                || !Double.isFinite(minX) || !Double.isFinite(minZ) || !Double.isFinite(maxX) || !Double.isFinite(maxZ)
                || minX < originX || minZ < originZ || maxX <= minX || maxZ <= minZ
                || maxX > originX + columns * cellSize || maxZ > originZ + rows * cellSize
                || (cells % 8 != 0 && (mask[mask.length - 1] & ((1 << (8 - cells % 8)) - 1)) != 0)) {
            throw new IllegalArgumentException("当前地图遮罩长度或尾部填充位错误");
        }
        this.columns = columns;
        this.rows = rows;
        this.cellSize = cellSize;
        this.originX = originX;
        this.originZ = originZ;
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
        this.requiresConnectedTeleport = requiresConnectedTeleport;
        this.mask = mask.clone();
        this.spawn = spawn;
        if (!isWalkable(spawn) || spawn.z() != 0) {
            throw new IllegalArgumentException("当前地图出生点不在可走地面上");
        }
        connectedDomains = labelConnectedDomains();
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

    /** schema 2 为新版主世界与隔离联调地图共用的矩形和位图格式。 */
    static CurrentMapNavigation loadRectangular(JsonNode source, byte[] text, Path folder) {
        byte[] mask = Base64.getDecoder().decode(text);
        if (!sha256(text).equals(source.path("walkmaskSha256").asText())
                || !sha256(mask).equals(source.path("walkmaskPackedSha256").asText())) {
            throw new IllegalArgumentException("导航位图 SHA256 不一致: " + folder);
        }

        JsonNode world = source.path("worldRect"), effective = source.path("effectiveWorldRect");
        double x = coordinate(world, "x"), z = coordinate(world, "y");
        double width = coordinate(world, "width"), height = coordinate(world, "height");
        double size = coordinate(source, "cellSize");
        int columns = positiveInteger(source, "walkmaskColumns"), rows = positiveInteger(source, "walkmaskRows");
        if (width <= 0 || height <= 0 || size <= 0
                || columns != Math.ceil(width / size) || rows != Math.ceil(height / size)) {
            throw new IllegalArgumentException("导航栅格与世界矩形宽高不匹配: " + folder);
        }
        double ex = coordinate(effective, "x"), ez = coordinate(effective, "y");
        double ew = coordinate(effective, "width"), eh = coordinate(effective, "height");
        JsonNode spawn = source.path("spawnWorld");
        return new CurrentMapNavigation(mask,
                new Vec3(coordinate(spawn, "z"), coordinate(spawn, "x"), coordinate(spawn, "y")),
                columns, rows, size, x, z, Math.max(x, ex), Math.max(z, ez),
                Math.min(x + width, ex + ew), Math.min(z + height, ez + eh), true);
    }

    private static double coordinate(JsonNode object, String key) {
        JsonNode node = object.path(key);
        if (!node.isNumber() || !Double.isFinite(node.doubleValue())) {
            throw new IllegalArgumentException("导航坐标必须为有限数值: " + key);
        }
        return node.doubleValue();
    }

    private static int positiveInteger(JsonNode object, String key) {
        JsonNode node = object.path(key);
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() <= 0) {
            throw new IllegalArgumentException("导航维度必须为正整数: " + key);
        }
        return node.intValue();
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Vec3 spawn() { return spawn; }

    boolean requiresConnectedTeleport() { return requiresConnectedTeleport; }

    /** 瞬移只比较可达域，不要求两点间直线可走；四连通不会把对角相触的平台合并。 */
    boolean sameConnectedDomain(Vec3 from, Vec3 to) {
        if (!isWalkable(from) || !isWalkable(to)) return false;
        int start = (int) Math.floor((from.x() - originZ) / cellSize) * columns
                + (int) Math.floor((from.y() - originX) / cellSize);
        int end = (int) Math.floor((to.x() - originZ) / cellSize) * columns
                + (int) Math.floor((to.y() - originX) / cellSize);
        return connectedDomains[start] != 0 && connectedDomains[start] == connectedDomains[end];
    }

    // 仅装配时标记一次，归队瞬移不在场景线程上重新遍历整个地图。
    private int[] labelConnectedDomains() {
        int[] domains = new int[columns * rows];
        int[] queue = new int[domains.length];
        int label = 0;
        for (int start = 0; start < domains.length; start++) {
            if (domains[start] != 0 || !domainCell(start % columns, start / columns)) continue;
            domains[start] = ++label;
            int head = 0, tail = 0;
            queue[tail++] = start;
            while (head < tail) {
                int at = queue[head++], col = at % columns, row = at / columns;
                for (int direction = 0; direction < 4; direction++) {
                    int x = col + (direction == 0 ? 1 : direction == 1 ? -1 : 0);
                    int y = row + (direction == 2 ? 1 : direction == 3 ? -1 : 0);
                    if (!domainCell(x, y)) continue;
                    int next = y * columns + x;
                    if (domains[next] == 0) {
                        domains[next] = label;
                        queue[tail++] = next;
                    }
                }
            }
        }
        return domains;
    }

    private boolean domainCell(int col, int row) {
        return cell(col, row) && originX + (col + 1) * cellSize > minX
                && originX + col * cellSize < maxX && originZ + (row + 1) * cellSize > minZ
                && originZ + row * cellSize < maxZ;
    }

    @Override
    public boolean isWalkable(Vec3 point) {
        return point.isFinite() && point.y() >= minX && point.y() < maxX && point.x() >= minZ && point.x() < maxZ
                && cell((int) Math.floor((point.y() - originX) / cellSize),
                        (int) Math.floor((point.x() - originZ) / cellSize));
    }

    /** col 向 Unity x 正向，row 向 Unity z 正向；存盘位序的行则从图片上方开始。 */
    private boolean cell(int col, int row) {
        if (col < 0 || row < 0 || col >= columns || row >= rows) return false;
        int bit = (rows - 1 - row) * columns + col;
        return (mask[bit / 8] & (1 << (7 - bit % 8))) != 0;
    }

    @Override
    public Vec3 velocity(Vec3 velocity) { return new Vec3(velocity.x(), velocity.y(), 0); }

    @Override
    public Move trace(Vec3 from, Vec3 to) {
        if (!isWalkable(from)) return new Move(spawn, true);
        if (!MovementRules.insideWorld(to)) return new Move(new Vec3(from.x(), from.y(), 0), true);
        double u = (from.y() - originX) / cellSize;
        double v = (from.x() - originZ) / cellSize;
        double du = (to.y() - from.y()) / cellSize;
        double dv = (to.x() - from.x()) / cellSize;
        // 与半开有效矩形求最早出界参数；末格可能只有 0.4 m，不能等到下一整格线。
        double boundary = Double.POSITIVE_INFINITY;
        if (to.y() < minX) boundary = Math.min(boundary, (minX - from.y()) / (to.y() - from.y()));
        if (to.y() >= maxX) boundary = Math.min(boundary, (maxX - from.y()) / (to.y() - from.y()));
        if (to.x() < minZ) boundary = Math.min(boundary, (minZ - from.x()) / (to.x() - from.x()));
        if (to.x() >= maxZ) boundary = Math.min(boundary, (maxZ - from.x()) / (to.x() - from.x()));
        int col = (int) Math.floor(u), row = (int) Math.floor(v);
        int sx = du > 0 ? 1 : du < 0 ? -1 : 0;
        int sy = dv > 0 ? 1 : dv < 0 ? -1 : 0;
        double deltaX = sx == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(du);
        double deltaY = sy == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dv);
        double nextX = sx > 0 ? (col + 1 - u) / du : sx < 0 ? (col - u) / du : Double.POSITIVE_INFINITY;
        double nextY = sy > 0 ? (row + 1 - v) / dv : sy < 0 ? (row - v) / dv : Double.POSITIVE_INFINITY;
        // 每次至少跨过一条格线；越出矩形边界立即阻挡，无需采样步长或无限循环。
        while (Math.min(nextX, nextY) <= Math.min(1, boundary)) {
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
        if (boundary <= 1) return blocked(from, to, boundary);
        return new Move(new Vec3(to.x(), to.y(), 0), false);
    }

    private Move blocked(Vec3 from, Vec3 to, double at) {
        double distance = from.horizontalDistance(to);
        double fraction = Math.max(0, at - (distance > 0 ? BACKOFF / distance : 0));
        return new Move(new Vec3(from.x() + (to.x() - from.x()) * fraction,
                from.y() + (to.y() - from.y()) * fraction, 0), true);
    }
}
