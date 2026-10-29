package com.game.navigation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 已通过 {@link MsetReader} 结构校验的不可变导航几何。坐标为 Detour 的 Y-up；
 * 不包含场景注册、过滤器或最近点/raycast/path 的可变查询工作区。
 */
public final class NavMesh {
    private final Parameters parameters;
    private final List<Tile> tiles;
    private final Map<TileKey, Tile> byKey;
    private final Map<Integer, Tile> bySlot;

    NavMesh(Parameters parameters, List<Tile> tiles) {
        this.parameters = Objects.requireNonNull(parameters);
        this.tiles = List.copyOf(tiles);
        Map<TileKey, Tile> keys = new LinkedHashMap<>();
        Map<Integer, Tile> slots = new LinkedHashMap<>();
        for (Tile tile : tiles) {
            if (keys.put(tile.key(), tile) != null || slots.put(tile.slot(), tile) != null) {
                throw new IllegalArgumentException("重复 tile");
            }
        }
        this.byKey = Map.copyOf(keys);
        this.bySlot = Map.copyOf(slots);
    }

    public Parameters parameters() { return parameters; }
    public List<Tile> tiles() { return tiles; }
    public Optional<Tile> tile(TileKey key) { return Optional.ofNullable(byKey.get(key)); }

    /** 拒绝非规范位、旧 salt、空槽和越界多边形；long 保留 uint64 的全部位。 */
    public Optional<PolygonLocation> resolve(long polygonReference) {
        ReferenceLayout layout = parameters.references();
        if (!layout.canonical(polygonReference)) return Optional.empty();
        Tile tile = bySlot.get(layout.slot(polygonReference));
        int index = layout.polygon(polygonReference);
        if (tile == null || layout.salt(tile.reference()) != layout.salt(polygonReference)
                || index >= tile.polygons().size()) return Optional.empty();
        return Optional.of(new PolygonLocation(tile, index));
    }

    public record Vec3(double x, double y, double z) {
        public double axis(int index) {
            return switch (index) { case 0 -> x; case 1 -> y; case 2 -> z;
                default -> throw new IndexOutOfBoundsException(index); };
        }
    }
    public record Bounds(Vec3 minimum, Vec3 maximum) {
        public Bounds { Objects.requireNonNull(minimum); Objects.requireNonNull(maximum); }
    }
    public record Parameters(double walkableHeight, double walkableRadius, double walkableClimb,
                             double bvQuantization, Vec3 origin, double tileWidth, double tileHeight,
                             int maxTiles, int maxPolygonsPerTile, ReferenceLayout references) {
        public Parameters { Objects.requireNonNull(origin); Objects.requireNonNull(references); }
    }

    /** 固定 UE 源按容量 nextPow2 计算位宽，salt 至多 31 位且至少 5 位。 */
    public record ReferenceLayout(int tileBits, int polygonBits, int saltBits) {
        public ReferenceLayout {
            if (tileBits < 0 || tileBits > 30 || polygonBits < 0 || polygonBits > 30
                    || saltBits < 5 || saltBits > 31 || tileBits + polygonBits + saltBits > 64) {
                throw new IllegalArgumentException("非法引用位宽");
            }
        }
        private static long mask(int bits) { return (1L << bits) - 1; }
        public int slot(long reference) { return (int) ((reference >>> polygonBits) & mask(tileBits)); }
        public int polygon(long reference) { return (int) (reference & mask(polygonBits)); }
        public long salt(long reference) {
            return (reference >>> (tileBits + polygonBits)) & mask(saltBits);
        }
        public boolean canonical(long reference) {
            int used = tileBits + polygonBits + saltBits;
            return salt(reference) != 0 && (used == 64 || (reference >>> used) == 0);
        }
    }

    public record TileKey(int x, int z, int layer) { }
    public enum PolygonType { GROUND, OFF_MESH_POINT }
    public enum EdgeKind { WALL, INTERNAL, PORTAL }

    /** portalSide 只是方向标记，缺邻 tile 是合法边界；本库不伪造跨 tile 连接。 */
    public record Edge(EdgeKind kind, int polygonIndex, int portalSide) {
        public Edge { Objects.requireNonNull(kind); }
    }
    public record Polygon(List<Integer> vertexIndices, List<Edge> edges, int flags, int area,
                          PolygonType type) {
        public Polygon {
            vertexIndices = List.copyOf(vertexIndices);
            edges = List.copyOf(edges);
            Objects.requireNonNull(type);
        }
    }
    public record DetailMesh(int vertexBase, int triangleBase, int vertexCount, int triangleCount) { }
    public record DetailTriangle(int a, int b, int c, int edgeFlags) { }
    public record QuantizedBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public int minimum(int axis) {
            return switch (axis) { case 0 -> minX; case 1 -> minY; case 2 -> minZ;
                default -> throw new IndexOutOfBoundsException(axis); };
        }
        public int maximum(int axis) {
            return switch (axis) { case 0 -> maxX; case 1 -> maxY; case 2 -> maxZ;
                default -> throw new IndexOutOfBoundsException(axis); };
        }
    }
    /** index >= 0 为 ground polygon；index < 0 为整棵子树的负节点数。 */
    public record BvNode(QuantizedBounds bounds, int index) {
        public BvNode { Objects.requireNonNull(bounds); }
    }
    public record OffMeshConnection(Vec3 start, Vec3 end, double radius, double snapHeight,
                                    long userId, int polygonIndex, int side,
                                    boolean bidirectional, boolean cheapArea) {
        public OffMeshConnection { Objects.requireNonNull(start); Objects.requireNonNull(end); }
    }
    public record Tile(long reference, int slot, TileKey key, Bounds bounds,
                       int serializedLinkCapacity, List<Vec3> vertices, List<Polygon> polygons,
                       List<DetailMesh> detailMeshes, List<Vec3> detailVertices,
                       List<DetailTriangle> detailTriangles, List<BvNode> bvTree,
                       List<OffMeshConnection> offMeshConnections) {
        public Tile {
            Objects.requireNonNull(key); Objects.requireNonNull(bounds);
            vertices = List.copyOf(vertices); polygons = List.copyOf(polygons);
            detailMeshes = List.copyOf(detailMeshes); detailVertices = List.copyOf(detailVertices);
            detailTriangles = List.copyOf(detailTriangles); bvTree = List.copyOf(bvTree);
            offMeshConnections = List.copyOf(offMeshConnections);
        }
        public long polygonReference(int polygonIndex) {
            Objects.checkIndex(polygonIndex, polygons.size());
            return reference | polygonIndex;
        }
        /** 统一解析 detail triangle 的局部顶点编号，供后续高度/最近点算法使用。 */
        public Vec3 detailVertex(int polygonIndex, int localVertexIndex) {
            Polygon polygon = polygons.get(polygonIndex);
            DetailMesh detail = detailMeshes.get(polygonIndex);
            Objects.checkIndex(localVertexIndex, polygon.vertexIndices().size() + detail.vertexCount());
            return localVertexIndex < polygon.vertexIndices().size()
                    ? vertices.get(polygon.vertexIndices().get(localVertexIndex))
                    : detailVertices.get(detail.vertexBase() + localVertexIndex - polygon.vertexIndices().size());
        }
    }
    public record PolygonLocation(Tile tile, int polygonIndex) {
        public PolygonLocation {
            Objects.requireNonNull(tile); Objects.checkIndex(polygonIndex, tile.polygons().size());
        }
        public Polygon polygon() { return tile.polygons().get(polygonIndex); }
        public long reference() { return tile.polygonReference(polygonIndex); }
    }
}
