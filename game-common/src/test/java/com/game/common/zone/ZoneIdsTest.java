package com.game.common.zone;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.junit.jupiter.api.Test;

class ZoneIdsTest {
    private record Value(@JsonDeserialize(using = ZoneIdJsonDeserializer.class) Long zone) {
    }
    private record PrimitiveValue(@JsonDeserialize(using = ZoneIdJsonDeserializer.class) long zone) {
    }

    private record Pair(@JsonDeserialize(using = ZoneIdJsonDeserializer.class) long primitive,
                        @JsonDeserialize(using = ZoneIdJsonDeserializer.class) Long boxed) {
    }

    private record ReversePair(@JsonDeserialize(using = ZoneIdJsonDeserializer.class) Long boxed,
                               @JsonDeserialize(using = ZoneIdJsonDeserializer.class) long primitive) {
    }

    @Test
    void 可选零与三边界无损_必填零负值溢出拒绝() {
        assertThat(ZoneIds.optionalBits(0)).isZero();
        for (long value : new long[] {2147483647L, 2147483648L, 4294967295L}) {
            assertThat(Integer.toUnsignedLong(ZoneIds.requiredBits(value))).isEqualTo(value);
        }
        for (long value : new long[] {-1L, 4294967296L, Long.MAX_VALUE}) {
            assertThatThrownBy(() -> ZoneIds.optionalBits(value)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ZoneIds.requiredBits(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 局部JSON整数策略不截断小数_不改变全局整数策略_空缺仍为null() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertThat(json.isEnabled(DeserializationFeature.ACCEPT_FLOAT_AS_INT)).isTrue();
        assertThat(json.readValue("{}", Value.class).zone()).isNull();
        assertThat(json.readValue("{\"zone\":null}", Value.class).zone()).isNull();
        for (long value : new long[] {0, 2147483647L, 2147483648L, 4294967295L}) {
            assertThat(json.readValue("{\"zone\":" + value + "}", Value.class).zone()).isEqualTo(value);
        }
        for (String invalid : new String[] {"-1", "-0.5", "4294967295.5", "4294967296", "1.0",
                "999999999999999999999999", "\"1\""}) {
            assertThatThrownBy(() -> json.readValue("{\"zone\":" + invalid + "}", Value.class))
                    .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        }
    }
    @Test
    void primitive缺省与null归零_boxed仍保留空值且上下文实例不串扰() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (String body : new String[] {"{}", "{\"zone\":null}", "{\"zone\":0}"}) {
            assertThat(json.readValue(body, PrimitiveValue.class).zone()).isZero();
            Long expected = body.contains(":0") ? Long.valueOf(0L) : null;
            assertThat(json.readValue(body, Value.class).zone()).isEqualTo(expected);
        }
        for (String body : new String[] {"{}", "{\"primitive\":null,\"boxed\":null}",
                "{\"primitive\":0,\"boxed\":0}"}) {
            Long boxed = body.contains(":0") ? Long.valueOf(0L) : null;
            assertThat(json.readValue(body, Pair.class)).isEqualTo(new Pair(0L, boxed));
            assertThat(json.readValue(body, ReversePair.class)).isEqualTo(new ReversePair(boxed, 0L));
            assertThat(json.readValue(body, Pair.class)).isEqualTo(new Pair(0L, boxed));
        }
        assertThat(json.readValue("{\"primitive\":2147483648,\"boxed\":4294967295}", Pair.class))
                .isEqualTo(new Pair(2147483648L, 4294967295L));
        assertThat(json.readValue("{\"boxed\":2147483648,\"primitive\":4294967295}", ReversePair.class))
                .isEqualTo(new ReversePair(2147483648L, 4294967295L));
        // 同一mapper交替读取两种属性，再次确认boxed的空值没有被primitive策略覆盖。
        assertThat(json.readValue("{\"zone\":null}", Value.class).zone()).isNull();
        assertThat(json.readValue("{}", PrimitiveValue.class).zone()).isZero();
    }
}
