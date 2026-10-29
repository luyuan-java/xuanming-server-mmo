package com.game.common.zone;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import java.io.IOException;

/** 仅由 zone 字段显式引用，不改变其它整数或全局 Jackson 的数值策略。 */
public final class ZoneIdJsonDeserializer extends JsonDeserializer<Long> implements ContextualDeserializer {
    private final boolean primitive;

    public ZoneIdJsonDeserializer() {
        this(false);
    }

    private ZoneIdJsonDeserializer(boolean primitive) {
        this.primitive = primitive;
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property)
            throws JsonMappingException {
        JavaType target = property == null ? context.getContextualType() : property.getType();
        Class<?> rawType = target == null ? Long.class : target.getRawClass();
        if (rawType != long.class && rawType != Long.class) {
            throw JsonMappingException.from(context.getParser(), "zone 字段类型必须是 long 或 Long");
        }
        // Jackson 可共享未上下文化的实例；每个属性用独立不可变策略，避免 primitive 与 boxed 串扰。
        return new ZoneIdJsonDeserializer(rawType == long.class);
    }

    @Override
    public Long getNullValue(DeserializationContext context) {
        return primitive ? Long.valueOf(0L) : null;
    }

    @Override
    public Long getAbsentValue(DeserializationContext context) {
        return getNullValue(context);
    }
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            throw JsonMappingException.from(parser, "zone_id 必须是 JSON 整数");
        }
        long value = parser.getLongValue();
        try {
            ZoneIds.optionalBits(value);
        } catch (IllegalArgumentException invalid) {
            throw JsonMappingException.from(parser, invalid.getMessage(), invalid);
        }
        return value;
    }
}
