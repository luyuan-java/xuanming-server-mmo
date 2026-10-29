package com.game.navigation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static com.game.navigation.NavMesh.*;
import static com.game.navigation.NavMeshFormatException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class MsetReaderTest {
    private final MsetReader reader = new MsetReader();

    @Test
    void readsTwoTrianglesWithReciprocalEdgeAndUnresolvedBoundaryPortals() throws Exception {
        Fixture fixture = fixture(false, true);
        NavMesh mesh = reader.read(fixture.bytes());
        Tile tile = mesh.tiles().getFirst();
        assertEquals(new TileKey(0, 0, 0), tile.key());
        assertEquals(2, tile.polygons().size());
        assertEquals(3, tile.bvTree().size()); // 全零容量尾节点不进入查询视图。
        assertEquals(EdgeKind.INTERNAL, tile.polygons().get(0).edges().get(2).kind());
        assertEquals(1, tile.polygons().get(0).edges().get(2).polygonIndex());
        assertEquals(EdgeKind.PORTAL, tile.polygons().get(0).edges().get(1).kind());
        assertEquals(0, tile.polygons().get(0).edges().get(1).portalSide());
        assertEquals(new Vec3(1, 0, 1), tile.detailVertex(0, 2));
        assertEquals(1, mesh.resolve(tile.polygonReference(1)).orElseThrow().polygonIndex());
    }

    @Test
    void copiesInputAndExposesOnlyImmutableCollections() throws Exception {
        Fixture fixture = fixture(false, true);
        NavMesh mesh = reader.read(fixture.bytes());
        Arrays.fill(fixture.bytes(), (byte) 0);
        assertEquals(2, mesh.tiles().getFirst().polygons().size());
        assertThrows(UnsupportedOperationException.class, () -> mesh.tiles().clear());
        assertThrows(UnsupportedOperationException.class, () -> mesh.tiles().getFirst().vertices().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> mesh.tiles().getFirst().polygons().getFirst().vertexIndices().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> mesh.tiles().getFirst().polygons().getFirst().edges().clear());
    }

    @Test
    void allowsAbsentBvAndRuntimeCacheGarbageButNeverExportsCaches() throws Exception {
        Fixture fixture = fixture(false, false);
        ByteBuffer bytes = wrap(fixture.bytes());
        bytes.putInt(fixture.polygons(), 0xdeadbeef);
        for (int i = fixture.links(); i < fixture.details(); i++) fixture.bytes()[i] = (byte) 0xa5;
        Tile tile = reader.read(fixture.bytes()).tiles().getFirst();
        assertTrue(tile.bvTree().isEmpty());
        assertEquals(10, tile.serializedLinkCapacity());
    }

    @Test
    void supportsPointOffMeshWithoutEquatingProjectedVerticesAndOriginalEndpoints() throws Exception {
        Tile tile = reader.read(fixture(true, true).bytes()).tiles().getFirst();
        OffMeshConnection connection = tile.offMeshConnections().getFirst();
        assertEquals(2, connection.polygonIndex());
        assertEquals(0xffff_ffffL, connection.userId());
        assertEquals(-1, connection.snapHeight());
        assertTrue(connection.bidirectional());
        assertTrue(connection.cheapArea());
        assertNotEquals(connection.start(), tile.vertices().get(4));
        assertEquals(PolygonType.OFF_MESH_POINT, tile.polygons().get(2).type());
    }

    @Test
    void rejectsEveryTruncatedPrefixAndTrailingByte() {
        byte[] bytes = fixture(false, true).bytes();
        for (int size = 0; size < bytes.length; size++) {
            byte[] prefix = Arrays.copyOf(bytes, size);
            assertThrows(NavMeshFormatException.class, () -> reader.read(prefix), "prefix=" + size);
        }
        reject(Arrays.copyOf(bytes, bytes.length + 1), LENGTH);
    }

    @Test
    void rejectsOuterAndTileVersionsAndWrongEndianMagic() {
        Fixture f = fixture(false, true);
        reject(intAt(f.bytes(), 0, Integer.reverseBytes(MsetReader.MSET_MAGIC)), FORMAT);
        reject(intAt(f.bytes(), 4, 2), VERSION);
        reject(shortAt(f.bytes(), 112, 8), VERSION);
    }

    @Test
    void rejectsLengthsAndCountArithmeticBeforeAllocation() {
        Fixture f = fixture(false, true);
        reject(intAt(f.bytes(), 104, -1), LENGTH);
        reject(intAt(f.bytes(), 104, Integer.MAX_VALUE), LIMIT);
        reject(intAt(f.bytes(), 104, f.bytes().length - 112 - 8), LENGTH);
        reject(shortAt(f.bytes(), 112 + 20, 65535), LENGTH);
        reject(intAt(f.bytes(), 8, Integer.MAX_VALUE), LIMIT);
        reject(intAt(f.bytes(), 88, Integer.MAX_VALUE), LIMIT);
        reject(intAt(f.bytes(), 92, 0), LIMIT);
    }

    @Test
    void rejectsTooFewSaltBitsAndTileCountAboveDeclaredCapacity() {
        Fixture f = fixture(false, true);
        byte[] bits = intAt(intAt(f.bytes(), 88, 1 << 30), 92, 1 << 30);
        reject(bits, REFERENCE);
        reject(intAt(f.bytes(), 8, 5), LIMIT); // 声明最多 4 tile。
    }

    @Test
    void rejectsZeroSaltPolygonBitsUnusedHighBitsAndOutOfCapacityTileReferences() {
        Fixture f = fixture(false, true);
        reject(longAt(f.bytes(), 96, 0), REFERENCE);
        reject(longAt(f.bytes(), 96, 33), REFERENCE);
        reject(longAt(f.bytes(), 96, 32 | (1L << 63)), REFERENCE);
        // maxTiles=3 保持两位槽域，slot=3 虽可编码但不可使用。
        reject(longAt(intAt(f.bytes(), 88, 3), 96, 32 | 24), REFERENCE);
    }

    @Test
    void resolverRejectsStaleSaltMissingSlotAndOutOfRangePolygon() throws Exception {
        NavMesh mesh = reader.read(fixture(false, true).bytes());
        assertTrue(mesh.resolve(64).isEmpty());
        assertTrue(mesh.resolve(40).isEmpty());
        assertTrue(mesh.resolve(34).isEmpty());
        assertTrue(mesh.resolve(32 | (1L << 63)).isEmpty());
        assertThrows(IndexOutOfBoundsException.class, () -> mesh.tiles().getFirst().polygonReference(2));
    }

    @Test
    void rejectsDuplicateSlotEvenWithDifferentSaltAndDuplicateTileKey() {
        Fixture f = fixture(false, true);
        byte[] duplicate = duplicateTile(f.bytes());
        int second = f.bytes().length;
        reject(longAt(duplicate, second, 64), DUPLICATE);
        reject(longAt(duplicate, second, 40), DUPLICATE);
    }

    @Test
    void supportsMultipleLayersButOnlyWithDistinctSlots() throws Exception {
        Fixture f = fixture(false, true);
        byte[] duplicate = longAt(duplicateTile(f.bytes()), f.bytes().length, 40);
        wrap(duplicate).putShort(f.bytes().length + 16 + 2, (short) 1);
        NavMesh mesh = reader.read(duplicate);
        assertEquals(2, mesh.tiles().size());
        assertTrue(mesh.tile(new TileKey(0, 0, 1)).isPresent());
    }

    @Test
    void rejectsNaNInfinityAndCoordinateQuotaAtEveryDoubleDomain() {
        Fixture f = fixture(true, true);
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e10}) {
            for (int at : new int[]{16, 40, 48, 72, 112 + 32, f.vertices(), f.offMesh(), f.offMesh() + 56}) {
                reject(doubleAt(f.bytes(), at, bad), NUMBER);
            }
        }
        reject(doubleAt(f.bytes(), 40, 0), NUMBER);
        reject(doubleAt(f.bytes(), 72, 0), NUMBER);
        reject(doubleAt(f.bytes(), 24, -1), NUMBER);
    }

    @Test
    void rejectsAabbAndTileGridMismatch() {
        Fixture f = fixture(false, true);
        reject(doubleAt(f.bytes(), 112 + 32, 2), NUMBER);
        reject(intAt(f.bytes(), 112 + 8, 1), NUMBER);
        reject(doubleAt(f.bytes(), f.vertices(), -0.5), NUMBER);
    }

    @Test
    void rejectsBadPolygonVertexAndUnusedSlotData() {
        Fixture f = fixture(false, true);
        reject(shortAt(f.bytes(), f.polygons() + 4, 4), REFERENCE);
        reject(shortAt(f.bytes(), f.polygons() + 6, 0), REFERENCE);
        reject(shortAt(f.bytes(), f.polygons() + 10, 1), FORMAT);
        byte[] type = f.bytes().clone();
        type[f.polygons() + 31] = 64;
        reject(type, FORMAT);
    }

    @Test
    void rejectsInternalNeighborOutOfRangeSelfAndMissingReciprocity() {
        Fixture f = fixture(false, true);
        reject(shortAt(f.bytes(), f.polygons() + 20, 3), REFERENCE);
        reject(shortAt(f.bytes(), f.polygons() + 20, 1), REFERENCE);
        reject(shortAt(f.bytes(), f.polygons() + 32 + 16, 0), ADJACENCY);
    }

    @Test
    void rejectsReservedPortalBitsWrongPlaneAndIncorrectLinkCapacity() {
        Fixture f = fixture(false, true);
        reject(shortAt(f.bytes(), f.polygons() + 18, 0x8001), ADJACENCY);
        reject(shortAt(f.bytes(), f.polygons() + 18, 0x8010), ADJACENCY);
        reject(shortAt(f.bytes(), f.polygons() + 18, 0x8004), ADJACENCY);
        // 把 portal 改 wall 保持长度，但原始 link 容量不再符合静态边。
        reject(shortAt(f.bytes(), f.polygons() + 18, 0), FORMAT);
    }

    @Test
    void rejectsDetailRangeOverlapAndOutOfRangeTriangle() {
        Fixture f = fixture(false, true);
        reject(shortAt(f.bytes(), f.details() + 6 + 2, 0), REFERENCE);
        byte[] bad = f.bytes().clone(); bad[f.triangles()] = 3;
        reject(bad, REFERENCE);
        bad = f.bytes().clone(); bad[f.triangles() + 3] = 2;
        reject(bad, FORMAT);
        bad = f.bytes().clone(); bad[f.triangles() + 1] = 0;
        reject(bad, FORMAT);
        bad = f.bytes().clone(); bad[f.triangles() + 3] = 0;
        reject(bad, ADJACENCY);
    }

    @Test
    void rejectsSelfIntersectingStarEvenWhenEveryLocalTurnHasSameSign() {
        Fixture star = surfaceFixture(new double[][]{{3, 0, 6}, {5, 0, 0}, {0, 0, 4}, {6, 0, 4}, {1, 0, 0}},
                new double[0][], new int[][]{{0, 1, 2}, {0, 2, 3}, {0, 3, 4}});
        reject(star.bytes(), FORMAT);
    }

    @Test
    void acceptsDetailVertexFanAndResolvesLocalVertexDomain() throws Exception {
        Fixture square = surfaceFixture(square(), new double[][]{{1, .25, 1}},
                new int[][]{{0, 1, 4}, {1, 2, 4}, {2, 3, 4}, {3, 0, 4}});
        Tile tile = reader.read(square.bytes()).tiles().getFirst();
        assertEquals(1, tile.detailVertices().size());
        assertEquals(new Vec3(1, .25, 1), tile.detailVertex(0, 4));
        reject(doubleAt(square.bytes(), square.triangles() - 24, Double.NaN), NUMBER);
    }

    @Test
    void usesSourceFloatSquaredBoundaryThresholdRatherThanDoubleDistanceThreshold() throws Exception {
        double squaredThreshold = 0.001f * 0.001f;
        double distance = (0.001f + Math.sqrt(squaredThreshold)) / 2;
        assertTrue(distance > 0.001f);
        assertTrue(distance * distance < squaredThreshold);
        Fixture fan = surfaceFixture(square(), new double[][]{{1, .25, distance}},
                new int[][]{{0, 1, 4}, {1, 2, 4}, {2, 3, 4}, {3, 0, 4}});
        // 中间点仍在源阈值内，通向底边两个端点的内部边也有边界位。
        int[] expectedFlags = {0x15, 0x11, 0x01, 0x05};
        for (int i = 0; i < expectedFlags.length; i++) {
            fan.bytes()[fan.triangles() + i * 4 + 3] = (byte) expectedFlags[i];
        }
        assertEquals(4, reader.read(fan.bytes()).tiles().getFirst().detailTriangles().size());
        byte[] missingFlag = fan.bytes().clone();
        missingFlag[fan.triangles() + 3] = 1;
        reject(missingFlag, ADJACENCY);
    }

    @Test
    void rejectsBoundaryFlagsBeyondTheSourceSquaredThreshold() throws Exception {
        double squaredThreshold = 0.001f * 0.001f;
        double distance = Math.nextUp(Math.sqrt(squaredThreshold));
        assertTrue(distance * distance > squaredThreshold);
        Fixture fan = surfaceFixture(square(), new double[][]{{1, .25, distance}},
                new int[][]{{0, 1, 4}, {1, 2, 4}, {2, 3, 4}, {3, 0, 4}});
        assertEquals(4, reader.read(fan.bytes()).tiles().getFirst().detailTriangles().size());
        byte[] falseFlag = fan.bytes().clone();
        falseFlag[fan.triangles() + 3] = 0x15;
        reject(falseFlag, ADJACENCY);
    }

    @Test
    void rejectsDuplicateTrianglesAndHalfCoveredDetailSurface() {
        Fixture duplicate = surfaceFixture(square(), new double[0][], new int[][]{{0, 1, 2}, {0, 1, 2}});
        reject(duplicate.bytes(), FORMAT);
        Fixture missing = surfaceFixture(square(), new double[0][], new int[][]{{0, 1, 2}});
        reject(missing.bytes(), ADJACENCY);
    }

    @Test
    void rejectsFlippedDegenerateAndOutsideDetailGeometry() {
        reject(surfaceFixture(square(), new double[0][], new int[][]{{0, 2, 1}, {0, 2, 3}}).bytes(), FORMAT);
        reject(surfaceFixture(square(), new double[][]{{1, 0, 0}},
                new int[][]{{0, 1, 4}, {1, 2, 4}, {2, 3, 4}, {3, 0, 4}}).bytes(), FORMAT);
        reject(surfaceFixture(square(), new double[][]{{3, 0, 1}},
                new int[][]{{0, 1, 4}, {1, 2, 4}, {2, 3, 4}, {3, 0, 4}}).bytes(), FORMAT);
    }

    @Test
    void rejectsBvEscapeDuplicateLeafBadBoxAndNonzeroCapacityTail() {
        Fixture f = fixture(false, true);
        reject(intAt(f.bytes(), f.bv() + 12, Integer.MIN_VALUE), REFERENCE);
        reject(intAt(f.bytes(), f.bv() + 12, -2), REFERENCE);
        reject(intAt(f.bytes(), f.bv() + 32 + 12, 0), REFERENCE);
        reject(intAt(f.bytes(), f.bv() + 16 + 12, 2), REFERENCE);
        reject(shortAt(f.bytes(), f.bv() + 16 + 6, 0), NUMBER);
        reject(shortAt(f.bytes(), f.bv() + 48, 1), FORMAT);
    }

    @Test
    void rejectsOffMeshBadPolySideFlagsRadiusAndNeighbor() {
        Fixture f = fixture(true, true);
        reject(shortAt(f.bytes(), f.offMesh() + 68, 0), REFERENCE);
        byte[] bad = f.bytes().clone(); bad[f.offMesh() + 70] = (byte) 0xff;
        reject(bad, REFERENCE);
        bad = f.bytes().clone(); bad[f.offMesh() + 71] = 1;
        reject(bad, REFERENCE);
        reject(doubleAt(f.bytes(), f.offMesh() + 48, -1), REFERENCE);
        reject(shortAt(f.bytes(), f.polygons() + 64 + 16, 1), REFERENCE);
    }

    @Test
    void rejectsNonzeroOuterTileAndSegmentPadding() {
        Fixture f = fixture(false, true);
        reject(intAt(f.bytes(), 12, 1), FORMAT);
        reject(intAt(f.bytes(), 108, 1), FORMAT);
        reject(shortAt(f.bytes(), 112 + 30, 1), FORMAT);
        reject(intAt(f.bytes(), f.details() + 12, 1), FORMAT);
    }

    @Test
    void appliesGeometryAndByteQuotasWithoutRetainingPartialState() throws Exception {
        Fixture f = fixture(false, true);
        MsetReader small = new MsetReader(new MsetReader.Limits(2048, 1024, 2, 1, 8, 4, 100));
        assertEquals(LIMIT, assertThrows(NavMeshFormatException.class, () -> small.read(f.bytes())).code());
        assertEquals(2, reader.read(f.bytes()).tiles().getFirst().polygons().size());
        assertThrows(NavMeshFormatException.class, () -> reader.read(duplicateTile(f.bytes())));
        assertEquals(2, reader.read(f.bytes()).tiles().getFirst().polygons().size());
    }

    @Test
    void boundsStreamReadsAndDoesNotCloseCallerStream() throws Exception {
        class CountingStream extends InputStream {
            int reads;
            boolean closed;
            @Override public int read() { reads++; return 0; }
            @Override public void close() { closed = true; }
        }
        CountingStream stream = new CountingStream();
        MsetReader bounded = new MsetReader(new MsetReader.Limits(128, 80, 1, 2, 4, 2, 100));
        assertEquals(LIMIT, assertThrows(NavMeshFormatException.class, () -> bounded.read(stream)).code());
        assertEquals(129, stream.reads);
        assertFalse(stream.closed);
    }

    @Test
    void acceptsShortChunkReadsAndPropagatesIoFailure() throws Exception {
        byte[] bytes = fixture(false, true).bytes();
        InputStream chunks = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(3, len));
            }
        };
        assertEquals(1, reader.read(chunks).tiles().size());
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("broken"); }
        };
        assertEquals("broken", assertThrows(IOException.class, () -> reader.read(broken)).getMessage());
    }

    private void reject(byte[] bytes, NavMeshFormatException.Code code) {
        NavMeshFormatException error = assertThrows(NavMeshFormatException.class, () -> reader.read(bytes));
        assertEquals(code, error.code(), error.getMessage());
        assertTrue(error.offset() >= 0);
    }
    private static ByteBuffer wrap(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private static byte[] intAt(byte[] bytes, int offset, int value) {
        byte[] copy = bytes.clone(); wrap(copy).putInt(offset, value); return copy;
    }
    private static byte[] shortAt(byte[] bytes, int offset, int value) {
        byte[] copy = bytes.clone(); wrap(copy).putShort(offset, (short) value); return copy;
    }
    private static byte[] longAt(byte[] bytes, int offset, long value) {
        byte[] copy = bytes.clone(); wrap(copy).putLong(offset, value); return copy;
    }
    private static byte[] doubleAt(byte[] bytes, int offset, double value) {
        byte[] copy = bytes.clone(); wrap(copy).putDouble(offset, value); return copy;
    }
    private static byte[] duplicateTile(byte[] bytes) {
        byte[] copy = Arrays.copyOf(bytes, bytes.length * 2 - 96);
        System.arraycopy(bytes, 96, copy, bytes.length, bytes.length - 96);
        wrap(copy).putInt(8, 2);
        return copy;
    }

    /** 手工单位正方形分成两个三角形；不依赖 C++/protoc/外部文件生成测试输入。 */
    private record Fixture(byte[] bytes, int vertices, int polygons, int links, int details,
                           int triangles, int bv, int offMesh) { }
    private static Fixture fixture(boolean offMesh, boolean bv) {
        ByteBuffer b = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MsetReader.MSET_MAGIC).putInt(1).putInt(1).putInt(0);
        for (double v : new double[]{1, 0, .5, 1, 0, -1, 0, 1, 1}) b.putDouble(v);
        b.putInt(4).putInt(8);
        b.putLong(32).putInt(0).putInt(0);
        int tile = b.position();
        b.putShort((short) 7).putShort((short) 0).putShort((short) (offMesh ? 3 : 2));
        b.putShort((short) (offMesh ? 6 : 4)).putInt(0).putInt(0);
        for (int value : new int[]{10, 2, 0, 2, bv ? 4 : 0, offMesh ? 1 : 0, 2, 0}) b.putShort((short) value);
        for (double v : new double[]{0, -1, 0, 1, 1, 1}) b.putDouble(v);
        int vertices = b.position();
        for (double v : new double[]{0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1}) b.putDouble(v);
        if (offMesh) for (double v : new double[]{.2, 0, .2, 1, 0, .5}) b.putDouble(v);
        int polygons = b.position();
        polygon(b, new int[]{0, 1, 2}, new int[]{0, 0x8000, 2}, 0);
        polygon(b, new int[]{0, 2, 3}, new int[]{1, 0x8002, 0}, 0);
        if (offMesh) polygon(b, new int[]{4, 5}, new int[]{0, 0}, 1);
        int links = b.position(); b.position(b.position() + 10 * 16);
        int details = b.position();
        b.putShort((short) 0).putShort((short) 0).put((byte) 0).put((byte) 1);
        b.putShort((short) 0).putShort((short) 1).put((byte) 0).put((byte) 1);
        b.putInt(0);
        int triangles = b.position();
        b.put(new byte[]{0, 1, 2, 0x15, 0, 1, 2, 0x15});
        int bvOffset = b.position();
        if (bv) {
            for (int index : new int[]{-3, 0, 1}) {
                for (int value : new int[]{0, 1, 0, 1, 1, 1}) b.putShort((short) value);
                b.putInt(index);
            }
            b.position(b.position() + 16);
        }
        int offOffset = b.position();
        if (offMesh) {
            for (double v : new double[]{.2, .1, .2, 1.5, 0, .5, .2, -1}) b.putDouble(v);
            b.putInt(-1).putShort((short) 2).put((byte) 0).put((byte) 0xc0);
        }
        b.putInt(104, b.position() - tile);
        return new Fixture(Arrays.copyOf(b.array(), b.position()), vertices, polygons, links, details,
                triangles, bvOffset, offOffset);
    }
    private static void polygon(ByteBuffer b, int[] vertices, int[] neighbors, int type) {
        b.putInt(-1);
        for (int i = 0; i < 6; i++) b.putShort((short) (i < vertices.length ? vertices[i] : 0));
        for (int i = 0; i < 6; i++) b.putShort((short) (i < neighbors.length ? neighbors[i] : 0));
        b.putShort((short) 1).put((byte) vertices.length).put((byte) (type << 6));
    }

    private static double[][] square() { return new double[][]{{0, 0, 0}, {2, 0, 0}, {2, 0, 2}, {0, 0, 2}}; }

    /** 单 polygon 的独立 detail 曲面，用于真正的覆盖/自交反例。 */
    private static Fixture surfaceFixture(double[][] vertices, double[][] detailVertices, int[][] triangles) {
        ByteBuffer b = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MsetReader.MSET_MAGIC).putInt(1).putInt(1).putInt(0);
        for (double v : new double[]{1, 0, .5, 1, 0, -1, 0, 6, 6}) b.putDouble(v);
        b.putInt(4).putInt(8).putLong(32).putInt(0).putInt(0);
        int tile = b.position();
        b.putShort((short) 7).putShort((short) 0).putShort((short) 1).putShort((short) vertices.length);
        b.putInt(0).putInt(0);
        for (int value : new int[]{vertices.length, 1, detailVertices.length, triangles.length, 0, 0, 1, 0}) {
            b.putShort((short) value);
        }
        for (double v : new double[]{0, -1, 0, 6, 1, 6}) b.putDouble(v);
        int vertexOffset = b.position();
        for (double[] vertex : vertices) for (double v : vertex) b.putDouble(v);
        int polygonOffset = b.position();
        int[] indices = new int[vertices.length];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        polygon(b, indices, new int[indices.length], 0);
        int linkOffset = b.position(); b.position(b.position() + vertices.length * 16);
        int detailOffset = b.position();
        b.putShort((short) 0).putShort((short) 0).put((byte) detailVertices.length).put((byte) triangles.length);
        b.putShort((short) 0);
        for (double[] vertex : detailVertices) for (double v : vertex) b.putDouble(v);
        int triangleOffset = b.position();
        for (int[] triangle : triangles) {
            int flags = 0;
            for (int edge = 0; edge < 3; edge++) {
                int from = triangle[edge], to = triangle[(edge + 1) % 3];
                double[] a = from < vertices.length ? vertices[from] : detailVertices[from - vertices.length];
                double[] c = to < vertices.length ? vertices[to] : detailVertices[to - vertices.length];
                for (int k = 0; k < vertices.length; k++) {
                    double[] pa = vertices[k], pb = vertices[(k + 1) % vertices.length];
                    if (fixtureOnSegment(a, pa, pb) && fixtureOnSegment(c, pa, pb)) { flags |= 1 << (edge * 2); break; }
                }
            }
            b.put((byte) triangle[0]).put((byte) triangle[1]).put((byte) triangle[2]).put((byte) flags);
        }
        if (triangles.length % 2 != 0) b.putInt(0);
        int end = b.position();
        b.putInt(104, end - tile);
        return new Fixture(Arrays.copyOf(b.array(), end), vertexOffset, polygonOffset, linkOffset,
                detailOffset, triangleOffset, end, end);
    }

    private static boolean fixtureOnSegment(double[] p, double[] a, double[] b) {
        double cross = (b[0] - a[0]) * (p[2] - a[2]) - (b[2] - a[2]) * (p[0] - a[0]);
        return Math.abs(cross) < 1e-9 && p[0] >= Math.min(a[0], b[0]) && p[0] <= Math.max(a[0], b[0])
                && p[2] >= Math.min(a[2], b[2]) && p[2] <= Math.max(a[2], b[2]);
    }
}
