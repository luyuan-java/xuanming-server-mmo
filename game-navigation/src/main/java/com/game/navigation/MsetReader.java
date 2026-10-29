package com.game.navigation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.game.navigation.NavMesh.*;
import static com.game.navigation.NavMeshFormatException.Code.*;

/**
 * MSET v1 / UE tile v7、little-endian、double / uint64、无 segment/cluster 的固定格式读取器。
 * 固定来源与 ABI 证据见 docs/porting/navigation-reader-candidate-20261027.md。
 * 所有解析状态属于单次调用；全量校验完成才返回，不提供逐 tile 注册回调。
 */
public final class MsetReader {
    public static final int MSET_MAGIC = 0x4d534554;
    private final Limits limits;

    /** 字节数、实际 tile 数与实际几何数量独立限额；声明容量不用于预分配数组。 */
    public record Limits(int maxFileBytes, int maxTileBytes, int maxTiles,
                         int maxTotalPolygons, int maxTotalVertices, int maxTotalTriangles,
                         double maxAbsoluteCoordinate) {
        public Limits {
            if (maxFileBytes < 96 || maxFileBytes > Integer.MAX_VALUE - 8
                    || maxTileBytes < 80 || maxTileBytes > maxFileBytes || maxTiles < 1
                    || maxTotalPolygons < 1 || maxTotalVertices < 1 || maxTotalTriangles < 1
                    || !Double.isFinite(maxAbsoluteCoordinate) || maxAbsoluteCoordinate <= 0
                    || maxAbsoluteCoordinate > 1e100) {
                throw new IllegalArgumentException("非法导航读取限额");
            }
        }
        public static Limits defaults() {
            return new Limits(32 * 1024 * 1024, 8 * 1024 * 1024, 4096,
                    262_144, 524_288, 1_048_576, 1e9);
        }
    }

    public MsetReader() { this(Limits.defaults()); }
    public MsetReader(Limits limits) { this.limits = Objects.requireNonNull(limits); }

    public NavMesh read(Path path) throws IOException {
        // 不依赖可能过期的 Files.size；实际流仍受同一硬限额约束。
        try (InputStream input = Files.newInputStream(path)) { return read(input); }
    }

    /** 不关闭调用方的流；至多读取 maxFileBytes + 1 字节以判定超限。 */
    public NavMesh read(InputStream input) throws IOException {
        Objects.requireNonNull(input);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(limits.maxFileBytes(), 8192));
        byte[] chunk = new byte[8192];
        while (true) {
            int wanted = (int) Math.min(chunk.length, (long) limits.maxFileBytes() - bytes.size() + 1);
            int count = input.read(chunk, 0, wanted);
            if (count < 0) break;
            if (count == 0) {
                int value = input.read();
                if (value < 0) break;
                chunk[0] = (byte) value;
                count = 1;
            }
            require((long) bytes.size() + count <= limits.maxFileBytes(), LIMIT, bytes.size(), "文件超过字节限额");
            bytes.write(chunk, 0, count);
        }
        return parse(bytes.toByteArray());
    }

    public NavMesh read(byte[] input) throws NavMeshFormatException {
        Objects.requireNonNull(input);
        require(input.length <= limits.maxFileBytes(), LIMIT, 0, "文件超过字节限额");
        return parse(input.clone());
    }

    private NavMesh parse(byte[] input) throws NavMeshFormatException {
        Cursor c = new Cursor(ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN), 0);
        c.need(96);
        require(c.i32() == MSET_MAGIC, FORMAT, 0, "不是固定 little-endian MSET");
        require(c.i32() == 1, VERSION, 4, "不支持的 MSET 版本");
        int count = c.i32();
        require(count > 0 && count <= limits.maxTiles(), LIMIT, 8, "tile 数必须在限额内且非空");
        c.zero(4);
        double height = c.real(), radius = c.real(), climb = c.real(), quant = c.real();
        require(height > 0 && radius >= 0 && climb >= 0 && quant > 0,
                NUMBER, 16, "非法 agent 参数或 BV 量化因子");
        Vec3 origin = c.vector();
        double width = c.real(), depth = c.real();
        require(width > 0 && depth > 0, NUMBER, 72, "tile 尺寸必须为正");
        int capacity = c.i32(), polyCapacity = c.i32();
        require(capacity > 0 && capacity <= (1 << 30) && polyCapacity > 0 && polyCapacity <= (1 << 30),
                LIMIT, 88, "声明容量不支持 nextPow2 溢出或非正值");
        require(count <= capacity, LIMIT, 8, "tile 数超过声明容量");
        int tileBits = bits(capacity), polyBits = bits(polyCapacity);
        int saltBits = Math.min(31, 64 - tileBits - polyBits);
        require(saltBits >= 5, REFERENCE, 88, "引用位宽不足 5 个 salt 位");
        ReferenceLayout layout = new ReferenceLayout(tileBits, polyBits, saltBits);
        Parameters params = new Parameters(height, radius, climb, quant, origin, width, depth,
                capacity, polyCapacity, layout);
        List<Tile> tiles = new ArrayList<>();
        Set<TileKey> keys = new HashSet<>();
        Set<Integer> slots = new HashSet<>();
        Budget budget = new Budget();
        for (int index = 0; index < count; index++) {
            long headerOffset = c.offset();
            c.need(16);
            long reference = c.i64();
            int size = c.i32();
            c.zero(4);
            require(layout.canonical(reference) && layout.polygon(reference) == 0
                    && layout.slot(reference) < capacity, REFERENCE, headerOffset, "无效 tile 引用");
            require(slots.add(layout.slot(reference)), DUPLICATE, headerOffset, "重复 tile 槽位（含不同 salt）");
            require(size >= 80, LENGTH, headerOffset + 8, "tile 长度小于头部或为负");
            require(size <= limits.maxTileBytes(), LIMIT, headerOffset + 8, "tile 超过字节限额");
            Cursor tileBytes = c.slice(size);
            Tile tile = readTile(tileBytes, reference, params, budget);
            require(keys.add(tile.key()), DUPLICATE, tileBytes.base, "重复 (x,z,layer)");
            tiles.add(tile);
        }
        require(c.remaining() == 0, LENGTH, c.offset(), "文件存在额外尾字节");
        return new NavMesh(params, tiles);
    }

    private Tile readTile(Cursor c, long reference, Parameters params, Budget budget)
            throws NavMeshFormatException {
        c.need(80);
        require(c.u16() == 7, VERSION, c.base, "不支持的 UE tile 版本");
        int layer = c.u16(), polygonCount = c.u16(), vertexCount = c.u16();
        int x = c.i32(), z = c.i32();
        int linkCount = c.u16(), detailCount = c.u16(), detailVertexCount = c.u16();
        int triangleCount = c.u16(), bvCount = c.u16(), offMeshCount = c.u16(), groundCount = c.u16();
        c.zero(2);
        Bounds bounds = new Bounds(c.vector(), c.vector());
        for (int axis = 0; axis < 3; axis++) {
            require(bounds.minimum().axis(axis) <= bounds.maximum().axis(axis), NUMBER, c.base + 32, "tile AABB 倒置");
        }
        require(groundCount > 0 && polygonCount == groundCount + offMeshCount
                && detailCount == groundCount && vertexCount >= 3 + offMeshCount * 2
                && polygonCount <= params.maxPolygonsPerTile(), FORMAT, c.base + 4, "不一致的 polygon/detail/off-mesh 数量");
        require(bvCount == 0 || bvCount == groundCount * 2, FORMAT, c.base + 24, "BV 数量不符合固定 builder 容量");
        long expected = 80L + aligned(vertexCount * 24L) + aligned(polygonCount * 32L)
                + aligned(linkCount * 16L) + aligned(detailCount * 6L) + aligned(detailVertexCount * 24L)
                + aligned(triangleCount * 4L) + aligned(bvCount * 16L) + aligned(offMeshCount * 72L);
        require(expected == c.length(), LENGTH, c.base, "tile 长度与各段计数不符，期望 " + expected);
        budget.add(polygonCount, vertexCount + detailVertexCount, triangleCount, c.base);
        checkGridBounds(params, x, z, bounds, c.base);
        List<Vec3> vertices = readVectors(c, vertexCount);
        int groundVertexCount = vertexCount - offMeshCount * 2;
        List<Polygon> polygons = new ArrayList<>(polygonCount);
        int edgeCount = 0, portals = 0;
        for (int i = 0; i < polygonCount; i++) {
            long offset = c.offset();
            c.skip(4); // firstLink 和后续 links 是 C++ 运行时重建的缓存，不导入 Java。
            int[] indices = new int[6], neighbors = new int[6];
            for (int j = 0; j < 6; j++) indices[j] = c.u16();
            for (int j = 0; j < 6; j++) neighbors[j] = c.u16();
            int flags = c.u16(), n = c.u8(), areaType = c.u8();
            boolean ground = i < groundCount;
            require(ground ? n >= 3 && n <= 6 && (areaType >>> 6) == 0
                    : n == 2 && (areaType >>> 6) == 1, FORMAT, offset + 30, "多边形类型或顶点数非法");
            List<Integer> ids = new ArrayList<>(n);
            List<Edge> edges = new ArrayList<>(n);
            for (int j = 0; j < 6; j++) {
                if (j >= n) {
                    require(indices[j] == 0 && neighbors[j] == 0, FORMAT, offset, "未使用的 polygon 槽位应为零");
                    continue;
                }
                int v = indices[j], nei = neighbors[j];
                require(v < (ground ? groundVertexCount : vertexCount) && !ids.contains(v),
                        REFERENCE, offset + 4 + j * 2, "polygon 顶点引用越界或重复");
                ids.add(v);
                if (!ground) {
                    require(nei == 0 && v == groundVertexCount + (i - groundCount) * 2 + j,
                            REFERENCE, offset, "off-mesh 顶点/邻接不符合固定布局");
                }
                if (nei == 0) edges.add(new Edge(EdgeKind.WALL, -1, -1));
                else if ((nei & 0x8000) != 0) {
                    require(ground && (nei == 0x8000 || nei == 0x8002 || nei == 0x8004 || nei == 0x8006),
                            ADJACENCY, offset + 16 + j * 2, "非法 portal 方向或保留位");
                    edges.add(new Edge(EdgeKind.PORTAL, -1, nei & 7));
                    portals++;
                } else {
                    require(ground && nei <= groundCount && nei != i + 1,
                            REFERENCE, offset + 16 + j * 2, "内部邻接越界或指向自身");
                    edges.add(new Edge(EdgeKind.INTERNAL, nei - 1, -1));
                }
            }
            if (ground) edgeCount += n;
            polygons.add(new Polygon(ids, edges, flags, areaType & 63,
                    ground ? PolygonType.GROUND : PolygonType.OFF_MESH_POINT));
        }
        require(linkCount == edgeCount + portals * 2, FORMAT, c.base + 16, "静态 link 容量与固定 builder 不符");
        c.skip(linkCount * 16);
        List<DetailMesh> details = new ArrayList<>(detailCount);
        int nextVertex = 0, nextTriangle = 0;
        for (int i = 0; i < detailCount; i++) {
            long offset = c.offset();
            DetailMesh detail = new DetailMesh(c.u16(), c.u16(), c.u8(), c.u8());
            require(detail.vertexBase() == nextVertex && detail.triangleBase() == nextTriangle
                    && detail.triangleCount() > 0, REFERENCE, offset, "detail 分段重叠、有洞或为空");
            nextVertex += detail.vertexCount();
            nextTriangle += detail.triangleCount();
            require(nextVertex <= detailVertexCount && nextTriangle <= triangleCount,
                    REFERENCE, offset, "detail 范围越界");
            details.add(detail);
        }
        require(nextVertex == detailVertexCount && nextTriangle == triangleCount,
                REFERENCE, c.offset(), "detail 段存在未引用数据");
        c.padding(detailCount * 6);
        List<Vec3> detailVertices = readVectors(c, detailVertexCount);
        List<DetailTriangle> triangles = new ArrayList<>(triangleCount);
        for (int i = 0; i < triangleCount; i++) {
            DetailTriangle triangle = new DetailTriangle(c.u8(), c.u8(), c.u8(), c.u8());
            require((triangle.edgeFlags() & ~0x15) == 0 && triangle.a() != triangle.b()
                    && triangle.a() != triangle.c() && triangle.b() != triangle.c(), FORMAT, c.offset() - 4,
                    "detail triangle 标志位或重复索引非法");
            triangles.add(triangle);
        }
        c.padding(triangleCount * 4);
        List<BvNode> bv = new ArrayList<>(Math.max(0, bvCount - 1));
        for (int i = 0; i < bvCount; i++) {
            if (i == bvCount - 1) { c.zero(16); break; } // 固定 builder 预留的一个全零尾节点。
            QuantizedBounds q = new QuantizedBounds(c.u16(), c.u16(), c.u16(), c.u16(), c.u16(), c.u16());
            for (int axis = 0; axis < 3; axis++) {
                require(q.minimum(axis) <= q.maximum(axis), NUMBER, c.offset() - 12, "BV AABB 倒置");
            }
            bv.add(new BvNode(q, c.i32()));
        }
        List<OffMeshConnection> offMesh = new ArrayList<>(offMeshCount);
        for (int i = 0; i < offMeshCount; i++) {
            long offset = c.offset();
            Vec3 start = c.vector(), end = c.vector();
            double radius = c.real(), snapHeight = c.real(); // 负 snapHeight 明确表示使用 step height。
            long userId = Integer.toUnsignedLong(c.i32());
            int polygon = c.u16(), side = c.u8(), flags = c.u8();
            require(radius >= 0 && polygon == groundCount + i && (side == 0x80 || side <= 7)
                    && (flags & ~0xc0) == 0, REFERENCE, offset, "off-mesh 参数/引用/方向非法");
            // addTile 可投影 navVerts 端点，不能要求它们等于未投影的 con.pos。
            offMesh.add(new OffMeshConnection(start, end, radius, snapHeight, userId, polygon, side,
                    (flags & 0x80) != 0, (flags & 0x40) != 0));
        }
        require(c.remaining() == 0, LENGTH, c.offset(), "tile 存在额外尾字节");
        Tile tile = new Tile(reference, params.references().slot(reference), new TileKey(x, z, layer),
                bounds, linkCount, vertices, polygons, details, detailVertices, triangles, bv, offMesh);
        validateGeometry(tile, params, groundCount, c.base);
        validateBv(tile, params, groundCount, c.base);
        return tile;
    }

    private void checkGridBounds(Parameters p, int x, int z, Bounds b, long offset)
            throws NavMeshFormatException {
        double minX = p.origin().x() + (double) x * p.tileWidth();
        double minZ = p.origin().z() + (double) z * p.tileHeight();
        double maxX = minX + p.tileWidth(), maxZ = minZ + p.tileHeight();
        require(Double.isFinite(minX) && Double.isFinite(minZ) && Double.isFinite(maxX) && Double.isFinite(maxZ)
                && near(b.minimum().x(), minX) && near(b.maximum().x(), maxX)
                && near(b.minimum().z(), minZ) && near(b.maximum().z(), maxZ),
                NUMBER, offset + 32, "tile AABB 与网格坐标/尺寸不一致");
    }

    private static void validateGeometry(Tile tile, Parameters params, int groundCount, long offset)
            throws NavMeshFormatException {
        for (int i = 0; i < groundCount; i++) {
            Polygon polygon = tile.polygons().get(i);
            int n = polygon.vertexIndices().size();
            double direction = 0;
            for (int e = 0; e < n; e++) {
                Vec3 a = tile.vertices().get(polygon.vertexIndices().get(e));
                Vec3 b = tile.vertices().get(polygon.vertexIndices().get((e + 1) % n));
                Vec3 d = tile.vertices().get(polygon.vertexIndices().get((e + 2) % n));
                for (int axis = 0; axis < 3; axis++) {
                    require(inside(a.axis(axis), tile.bounds().minimum().axis(axis), tile.bounds().maximum().axis(axis)),
                            NUMBER, offset, "ground 顶点在 tile AABB 外");
                }
                require(a.x() != b.x() || a.z() != b.z(), FORMAT, offset, "ground 存在零长度水平边");
                double cross = (b.x() - a.x()) * (d.z() - b.z()) - (b.z() - a.z()) * (d.x() - b.x());
                require(Double.isFinite(cross), NUMBER, offset, "多边形计算溢出");
                if (cross != 0) {
                    require(direction == 0 || Math.signum(cross) == direction, FORMAT, offset, "ground 多边形非凸");
                    direction = Math.signum(cross);
                }
                Edge edge = polygon.edges().get(e);
                if (edge.kind() == EdgeKind.INTERNAL) {
                    Polygon neighbor = tile.polygons().get(edge.polygonIndex());
                    int matches = 0;
                    for (int k = 0; k < neighbor.vertexIndices().size(); k++) {
                        int after = (k + 1) % neighbor.vertexIndices().size();
                        Edge back = neighbor.edges().get(k);
                        if (neighbor.vertexIndices().get(k).equals(polygon.vertexIndices().get((e + 1) % n))
                                && neighbor.vertexIndices().get(after).equals(polygon.vertexIndices().get(e))
                                && back.kind() == EdgeKind.INTERNAL && back.polygonIndex() == i) matches++;
                    }
                    require(matches == 1, ADJACENCY, offset, "内部邻接缺少反向共享边");
                } else if (edge.kind() == EdgeKind.PORTAL) {
                    int axis = edge.portalSide() == 0 || edge.portalSide() == 4 ? 0 : 2;
                    double plane = edge.portalSide() == 0 || edge.portalSide() == 2
                            ? tile.bounds().maximum().axis(axis) : tile.bounds().minimum().axis(axis);
                    require(near(a.axis(axis), plane) && near(b.axis(axis), plane), ADJACENCY, offset,
                            "portal 边未处在声明方向的 tile 边界");
                }
            }
            require(direction != 0, FORMAT, offset, "ground 多边形水平面积为零");
            // 仅比较连续转角无法识别五角星；每条边必须支撑整个凸多边形。
            for (int e = 0; e < n; e++) {
                Vec3 a = tile.vertices().get(polygon.vertexIndices().get(e));
                Vec3 b = tile.vertices().get(polygon.vertexIndices().get((e + 1) % n));
                for (int v : polygon.vertexIndices()) {
                    require(direction * cross(a, b, tile.vertices().get(v)) >= 0,
                            FORMAT, offset, "ground 多边形自交或非凸");
                }
            }
            DetailMesh detail = tile.detailMeshes().get(i);
            int localCount = n + detail.vertexCount();
            require(localCount <= 256, REFERENCE, offset, "detail 局部顶点超出 uint8 索引域");
            for (int j = 0; j < detail.triangleCount(); j++) {
                DetailTriangle triangle = tile.detailTriangles().get(detail.triangleBase() + j);
                require(triangle.a() < localCount && triangle.b() < localCount && triangle.c() < localCount,
                        REFERENCE, offset, "detail triangle 顶点越界");
            }
            validateDetailSurface(tile, i, direction, offset);
        }
    }

    /**
     * 检查三角分段构成 polygon 的完整曲面：内部边反向配对，单边在 polygon 边界上，
     * 三角同向且非退化，面积一致。只在本次校验中分配局部拓扑表。
     */
    private static void validateDetailSurface(Tile tile, int polygonIndex, double direction, long offset)
            throws NavMeshFormatException {
        Polygon polygon = tile.polygons().get(polygonIndex);
        DetailMesh detail = tile.detailMeshes().get(polygonIndex);
        int n = polygon.vertexIndices().size();
        Map<Integer, int[]> edges = new HashMap<>();
        Set<Integer> triangleKeys = new HashSet<>();
        Vec3 origin = tile.detailVertex(polygonIndex, 0);
        double polygonArea = 0, triangleArea = 0, perimeter = 0;
        for (int e = 0; e < n; e++) {
            Vec3 a = tile.detailVertex(polygonIndex, e), b = tile.detailVertex(polygonIndex, (e + 1) % n);
            polygonArea += direction * cross(origin, a, b);
            perimeter += Math.hypot(b.x() - a.x(), b.z() - a.z());
        }
        for (int j = 0; j < detail.triangleCount(); j++) {
            DetailTriangle triangle = tile.detailTriangles().get(detail.triangleBase() + j);
            int[] ids = {triangle.a(), triangle.b(), triangle.c()};
            int min = Math.min(ids[0], Math.min(ids[1], ids[2]));
            int max = Math.max(ids[0], Math.max(ids[1], ids[2]));
            int middle = ids[0] + ids[1] + ids[2] - min - max;
            require(triangleKeys.add((min << 16) | (middle << 8) | max), FORMAT, offset, "重复 detail triangle");
            Vec3 a = tile.detailVertex(polygonIndex, ids[0]);
            Vec3 b = tile.detailVertex(polygonIndex, ids[1]);
            Vec3 c = tile.detailVertex(polygonIndex, ids[2]);
            double area = direction * cross(a, b, c);
            require(Double.isFinite(area) && area > 0, FORMAT, offset, "detail triangle 退化或方向不一致");
            triangleArea += area;
            for (int id : ids) {
                Vec3 point = tile.detailVertex(polygonIndex, id);
                for (int e = 0; e < n; e++) {
                    Vec3 pa = tile.detailVertex(polygonIndex, e), pb = tile.detailVertex(polygonIndex, (e + 1) % n);
                    double signed = direction * cross(pa, pb, point);
                    double tolerance = 1e-8 * Math.hypot(pb.x() - pa.x(), pb.z() - pa.z());
                    require(signed >= -tolerance, FORMAT, offset, "detail triangle 越出 polygon 水平边界");
                }
            }
            for (int e = 0; e < 3; e++) {
                int from = ids[e], to = ids[(e + 1) % 3];
                Vec3 fromPoint = tile.detailVertex(polygonIndex, from), toPoint = tile.detailVertex(polygonIndex, to);
                boolean boundaryFlag = false;
                for (int k = 0; k < n; k++) {
                    Vec3 pa = tile.detailVertex(polygonIndex, k), pb = tile.detailVertex(polygonIndex, (k + 1) % n);
                    if (sourceBoundaryPoint(fromPoint, pa, pb) && sourceBoundaryPoint(toPoint, pa, pb)) {
                        boundaryFlag = true;
                        break;
                    }
                }
                require(((triangle.edgeFlags() >>> (e * 2)) & 1) == (boundaryFlag ? 1 : 0),
                        ADJACENCY, offset, "detail triangle 边界标志与固定 Recast 几何规则不符");
                int key = (Math.min(from, to) << 8) | Math.max(from, to);
                int[] edge = edges.get(key);
                if (edge == null) edges.put(key, new int[]{from, to, 1});
                else {
                    require(edge[2] == 1 && edge[0] == to && edge[1] == from,
                            ADJACENCY, offset, "detail 内部边超过两面或方向不相反");
                    edge[2]++;
                }
            }
        }
        for (int[] edge : edges.values()) {
            if (edge[2] == 2) continue;
            Vec3 a = tile.detailVertex(polygonIndex, edge[0]), b = tile.detailVertex(polygonIndex, edge[1]);
            boolean boundary = false;
            for (int e = 0; e < n; e++) {
                Vec3 pa = tile.detailVertex(polygonIndex, e), pb = tile.detailVertex(polygonIndex, (e + 1) % n);
                if (onSegment(a, pa, pb) && onSegment(b, pa, pb)) { boundary = true; break; }
            }
            require(boundary, ADJACENCY, offset, "detail 存在未配对内部边/曲面缺口");
        }
        double tolerance = Math.max(perimeter * 1e-8, Math.ulp(polygonArea) * 64);
        require(Double.isFinite(triangleArea) && Math.abs(triangleArea - polygonArea) <= tolerance,
                FORMAT, offset, "detail 曲面面积与 polygon 不一致");
    }

    private static double cross(Vec3 a, Vec3 b, Vec3 c) {
        return (b.x() - a.x()) * (c.z() - a.z()) - (b.z() - a.z()) * (c.x() - a.x());
    }
    private static boolean onSegment(Vec3 p, Vec3 a, Vec3 b) {
        double length = Math.hypot(b.x() - a.x(), b.z() - a.z());
        return Math.abs(cross(a, b, p)) <= length * 1e-8
                && inside(p.x(), Math.min(a.x(), b.x()), Math.max(a.x(), b.x()))
                && inside(p.z(), Math.min(a.z(), b.z()), Math.max(a.z(), b.z()));
    }

    /** 源 rcSqr(0.001f) 先按 float 平方再转 double，不能换成 double 平方或开方比较。 */
    private static boolean sourceBoundaryPoint(Vec3 p, Vec3 a, Vec3 b) {
        double dx = b.x() - a.x(), dz = b.z() - a.z();
        double t = ((p.x() - a.x()) * dx + (p.z() - a.z()) * dz) / (dx * dx + dz * dz);
        t = Math.max(0, Math.min(1, t));
        double px = a.x() + t * dx - p.x(), pz = a.z() + t * dz - p.z();
        double squaredThreshold = 0.001f * 0.001f;
        return px * px + pz * pz < squaredThreshold;
    }

    /** 显式栈校验二叉 BV 树；不信任 escape，也不递归解析不可信输入。 */
    private static void validateBv(Tile tile, Parameters params, int groundCount, long offset)
            throws NavMeshFormatException {
        List<BvNode> nodes = tile.bvTree();
        if (nodes.isEmpty()) return;
        boolean[] leaves = new boolean[groundCount];
        record Branch(int index, int end, int parent) { }
        ArrayDeque<Branch> pending = new ArrayDeque<>();
        pending.push(new Branch(0, nodes.size(), -1));
        int visited = 0;
        while (!pending.isEmpty()) {
            Branch branch = pending.pop();
            require(branch.index() < branch.end(), REFERENCE, offset, "空 BV 子树");
            BvNode node = nodes.get(branch.index());
            visited++;
            if (branch.parent() >= 0) {
                QuantizedBounds parent = nodes.get(branch.parent()).bounds();
                for (int axis = 0; axis < 3; axis++) {
                    require(node.bounds().minimum(axis) >= parent.minimum(axis)
                            && node.bounds().maximum(axis) <= parent.maximum(axis), NUMBER, offset, "BV 子节点越出父 AABB");
                }
            }
            if (node.index() >= 0) {
                require(branch.end() == branch.index() + 1 && node.index() < groundCount && !leaves[node.index()],
                        REFERENCE, offset, "BV 叶引用越界、重复或 escape 不一致");
                leaves[node.index()] = true;
                Polygon polygon = tile.polygons().get(node.index());
                for (int v : polygon.vertexIndices()) checkBvPoint(tile, params, node, tile.vertices().get(v), false, offset);
                DetailMesh detail = tile.detailMeshes().get(node.index());
                for (int j = 0; j < detail.triangleCount(); j++) {
                    DetailTriangle t = tile.detailTriangles().get(detail.triangleBase() + j);
                    for (int local : new int[]{t.a(), t.b(), t.c()}) {
                        checkBvPoint(tile, params, node, tile.detailVertex(node.index(), local), true, offset);
                    }
                }
            } else {
                long end = (long) branch.index() - node.index();
                require(end == branch.end() && end - branch.index() >= 3, REFERENCE, offset, "BV escape 越界或子树太短");
                int left = branch.index() + 1;
                BvNode child = nodes.get(left);
                long split = child.index() < 0 ? (long) left - child.index() : left + 1L;
                require(split > left && split < branch.end(), REFERENCE, offset, "BV 缺少两个合法子树");
                pending.push(new Branch((int) split, branch.end(), branch.index()));
                pending.push(new Branch(left, (int) split, branch.index()));
            }
        }
        require(visited == nodes.size(), REFERENCE, offset, "BV 存在未访问节点");
        for (boolean leaf : leaves) require(leaf, REFERENCE, offset, "BV 未覆盖全部 ground polygon");
    }

    private static void checkBvPoint(Tile tile, Parameters params, BvNode node, Vec3 point, boolean heightOnly,
                                     long offset) throws NavMeshFormatException {
        for (int axis = heightOnly ? 1 : 0; axis < (heightOnly ? 2 : 3); axis++) {
            double quantized = (point.axis(axis) - tile.bounds().minimum().axis(axis)) * params.bvQuantization();
            require(Double.isFinite(quantized) && quantized >= node.bounds().minimum(axis) - 1e-6
                    && quantized <= node.bounds().maximum(axis) + 1e-6, NUMBER, offset, "BV 叶 AABB 不覆盖多边形/细节高度");
        }
    }

    private List<Vec3> readVectors(Cursor c, int count) throws NavMeshFormatException {
        List<Vec3> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(c.vector());
        return result;
    }

    private final class Budget {
        private long polygons, vertices, triangles;
        void add(int p, int v, int t, long offset) throws NavMeshFormatException {
            polygons += p; vertices += v; triangles += t;
            require(polygons <= limits.maxTotalPolygons() && vertices <= limits.maxTotalVertices()
                    && triangles <= limits.maxTotalTriangles(), LIMIT, offset, "实际几何总量超过读取限额");
        }
    }

    private final class Cursor {
        private final ByteBuffer bytes;
        private final long base;
        Cursor(ByteBuffer bytes, long base) { this.bytes = bytes; this.base = base; }
        long offset() { return base + bytes.position(); }
        int length() { return bytes.limit(); }
        int remaining() { return bytes.remaining(); }
        void need(int count) throws NavMeshFormatException {
            require(count >= 0 && count <= remaining(), LENGTH, offset(), "截断的输入");
        }
        int u8() throws NavMeshFormatException { need(1); return Byte.toUnsignedInt(bytes.get()); }
        int u16() throws NavMeshFormatException { need(2); return Short.toUnsignedInt(bytes.getShort()); }
        int i32() throws NavMeshFormatException { need(4); return bytes.getInt(); }
        long i64() throws NavMeshFormatException { need(8); return bytes.getLong(); }
        double real() throws NavMeshFormatException {
            need(8); double value = bytes.getDouble();
            require(Double.isFinite(value) && Math.abs(value) <= limits.maxAbsoluteCoordinate(),
                    NUMBER, offset() - 8, "非有限数或超过数值限额");
            return value;
        }
        Vec3 vector() throws NavMeshFormatException { return new Vec3(real(), real(), real()); }
        void skip(int count) throws NavMeshFormatException { need(count); bytes.position(bytes.position() + count); }
        void zero(int count) throws NavMeshFormatException {
            need(count);
            for (int i = 0; i < count; i++) require(bytes.get() == 0, FORMAT, offset() - 1, "固定格式填充/保留字节非零");
        }
        void padding(int size) throws NavMeshFormatException { zero((int) aligned(size) - size); }
        Cursor slice(int count) throws NavMeshFormatException {
            need(count);
            Cursor result = new Cursor(bytes.slice(bytes.position(), count).order(ByteOrder.LITTLE_ENDIAN), offset());
            skip(count);
            return result;
        }
    }

    private static int bits(int capacity) { return capacity == 1 ? 0 : 32 - Integer.numberOfLeadingZeros(capacity - 1); }
    private static long aligned(long size) { return (size + 7) & ~7L; }
    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= Math.max(1e-8, Math.max(Math.ulp(a), Math.ulp(b)) * 8);
    }
    private static boolean inside(double value, double min, double max) {
        return (value >= min || near(value, min)) && (value <= max || near(value, max));
    }
    private static void require(boolean condition, NavMeshFormatException.Code code, long offset, String message)
            throws NavMeshFormatException {
        if (!condition) throw new NavMeshFormatException(code, offset, message);
    }
}
