package com.game.data.tools.release;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 限长、拒绝重复键/未知字段/尾随 JSON；结构错误不回显输入。 */
final class ReleaseJson {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(262144).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            // 全局标量开关不约束 String；计划字段与嵌套 Map 值都必须原生为 JSON 字符串。
            .withCoercionConfig(LogicalType.Textual, coercion -> coercion
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private ReleaseJson() { }
    static <T> T read(Path path, Class<T> type) throws IOException {
        ReleaseFiles.regular(path);
        ReleaseFailure.require(Files.size(path) <= 8 * 1024 * 1024, "JSON_TOO_LARGE");
        try (var input = Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(8 * 1024 * 1024 + 1);
            ReleaseFailure.require(bytes.length <= 8 * 1024 * 1024, "JSON_TOO_LARGE");
            try { return JSON.readValue(bytes, type); }
            catch (IOException failure) { throw new ReleaseFailure("JSON_INVALID"); }
        }
    }
    static byte[] bytes(Object value) throws IOException {
        byte[] valueBytes = JSON.writeValueAsBytes(value);
        byte[] result = java.util.Arrays.copyOf(valueBytes, valueBytes.length + 1);
        result[result.length - 1] = '\n';
        return result;
    }
}
