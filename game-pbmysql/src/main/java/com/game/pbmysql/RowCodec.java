package com.game.pbmysql;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.ProtocolMessageEnum;
import com.google.protobuf.Timestamp;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;

/**
 * 消息字段 ↔ JDBC 列值，对应 Go 版 pbconv/convert.go。
 *
 * <p>写入（{@link #toColumnValue}）：
 * <ul>
 *   <li>int32 / int64 / uint32 → {@code Long}；uint64 → {@code Long}，≥ 2^63 时 {@code BigInteger}（不丢符号）；</li>
 *   <li>bool → {@code Integer} 1 / 0（列是 tinyint(1)）；enum → {@code Integer} 枚举号；</li>
 *   <li>float → {@code Double}（float32 精确展宽，比较谓词不会因十进制字面量与 FLOAT 列的精度差判成不等）；double → {@code Double}；
 *       NaN / ±Inf 拒绝（MySQL 无法表示，非严格模式下会被静默存成 0）；</li>
 *   <li>string → {@code String}；bytes → {@code byte[]}；</li>
 *   <li>子消息 → 其 wire 字节，未设置 → 空 {@code byte[]}；repeated / map → 只含该字段的同类型消息（holder）的 wire 字节，
 *       空容器 → 空 {@code byte[]}（与 Go 版逐字节相同，可与手写 SQL 混用）；</li>
 *   <li>Timestamp → UTC 的 {@code LocalDateTime}（截断到微秒，同 DATETIME(6)）；未设置或 Go 零时刻（0001-01-01T00:00:00Z）→ SQL NULL。</li>
 * </ul>
 *
 * <p>读出（{@link #applyColumnValue}）是逆过程：按列名取值，uint64 从 BigInteger / BigDecimal / Long 都能无损还原，
 * 超出字段取值范围的值报错而不是截断；SQL NULL 把标量字段置为默认值（proto3 optional 因此带上 presence，与 Go 的 Set 一致），
 * 子消息 / Timestamp / 容器字段清空。
 */
final class RowCodec {

    /** Go {@code time.Time{}.IsZero()} 对应的 Unix 秒（0001-01-01T00:00:00Z）：Go 版把它当「未设置」写 NULL。 */
    static final long GO_ZERO_TIME_SECONDS = -62135596800L;

    static final String TIMESTAMP_FULL_NAME = Timestamp.getDescriptor().getFullName();

    private static final BigInteger UINT64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);

    /** MySQL 原生 DATETIME 文本：带 0~9 位小数秒。 */
    private static final DateTimeFormatter MYSQL_DATETIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .toFormatter();

    private RowCodec() {
    }

    /** 单值 google.protobuf.Timestamp 字段（不含 repeated / map），对应 pbconv.isTimestampField。 */
    static boolean isTimestampField(FieldDescriptor fd) {
        return !fd.isRepeated() && isTimestampMessage(fd);
    }

    /** 字段的消息类型是 Timestamp（含 repeated），对应 Go 版 getMySQLFieldType 里的 {@code fieldDesc.Message()} 判定。 */
    static boolean isTimestampMessage(FieldDescriptor fd) {
        return fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE
                && fd.getMessageType().getFullName().equals(TIMESTAMP_FULL_NAME);
    }

    /** 真实（非 synthetic）oneof 的成员没有「未选中」的列表示，一律拒绝；proto3 optional 的 synthetic oneof 放行。 */
    static void rejectRealOneof(FieldDescriptor fd) {
        OneofDescriptor oneof = fd.getRealContainingOneof();
        if (oneof != null) {
            throw new RowConversionException("invalid field kind: field " + fd.getName()
                    + " belongs to unsupported oneof " + MysqlSyntax.goQuote(oneof.getName()));
        }
    }

    // ------------------------------------------------------------------ 写入

    /** 字段 → 下发给 JDBC 的参数值（见类注释）。 */
    static Object toColumnValue(MessageOrBuilder message, FieldDescriptor fd) {
        rejectRealOneof(fd);
        if (isTimestampField(fd)) {
            return timestampValue(message, fd);
        }
        if (fd.isRepeated()) {
            if (message.getRepeatedFieldCount(fd) == 0) {
                return new byte[0];
            }
            Message holder = message.getDefaultInstanceForType().newBuilderForType()
                    .setField(fd, message.getField(fd))
                    .build();
            return holder.toByteArray();
        }
        Object value = message.getField(fd);
        return switch (fd.getType()) {
            case INT32 -> (long) (Integer) value;
            case INT64 -> (Long) value;
            case UINT32 -> Integer.toUnsignedLong((Integer) value);
            case UINT64 -> uint64Value((Long) value);
            case FLOAT -> {
                float f = (Float) value;
                if (Float.isNaN(f) || Float.isInfinite(f)) {
                    throw nonFinite(fd, Float.toString(f));
                }
                yield (double) f;
            }
            case DOUBLE -> {
                double d = (Double) value;
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw nonFinite(fd, Double.toString(d));
                }
                yield d;
            }
            case STRING -> (String) value;
            case BOOL -> ((Boolean) value) ? 1 : 0;
            case ENUM -> ((EnumValueDescriptor) value).getNumber();
            case BYTES -> ((ByteString) value).toByteArray();
            case MESSAGE, GROUP -> message.hasField(fd) ? ((Message) value).toByteArray() : new byte[0];
            default -> throw new RowConversionException(
                    "invalid field kind: " + typeName(fd) + " (field: " + fd.getName() + ")");
        };
    }

    /** uint64 的 Java long 位模式 → 无符号值：非负照旧 Long，否则 BigInteger。 */
    static Object uint64Value(long bits) {
        return bits >= 0 ? (Object) bits : BigInteger.valueOf(bits).add(TWO_64);
    }

    private static RowConversionException nonFinite(FieldDescriptor fd, String value) {
        return new RowConversionException("non-finite float value (NaN/Inf) cannot be stored in MySQL: field "
                + fd.getName() + " = " + value);
    }

    private static LocalDateTime timestampValue(MessageOrBuilder message, FieldDescriptor fd) {
        if (!message.hasField(fd)) {
            return null;
        }
        Message ts = (Message) message.getField(fd);
        long seconds = (Long) ts.getField(ts.getDescriptorForType().findFieldByNumber(1));
        int nanos = (Integer) ts.getField(ts.getDescriptorForType().findFieldByNumber(2));
        Instant instant;
        try {
            instant = Instant.ofEpochSecond(seconds, nanos);
        } catch (RuntimeException e) {
            throw new RowConversionException("timestamp field " + fd.getName() + " is out of range", e);
        }
        if (instant.getEpochSecond() == GO_ZERO_TIME_SECONDS && instant.getNano() == 0) {
            return null;
        }
        int micros = instant.getNano() - instant.getNano() % 1000;
        try {
            return LocalDateTime.ofEpochSecond(instant.getEpochSecond(), micros, ZoneOffset.UTC);
        } catch (RuntimeException e) {
            throw new RowConversionException("timestamp field " + fd.getName() + " is out of range", e);
        }
    }

    // ------------------------------------------------------------------ 读出

    /** 按列名、按字段类型选合适的 getter 取一列（Timestamp 取 LocalDateTime，二进制类取 byte[]，其余 getObject）。 */
    static Object readColumn(ResultSet rs, String column, FieldDescriptor fd) throws SQLException {
        if (isTimestampField(fd)) {
            return rs.getObject(column, LocalDateTime.class);
        }
        if (fd.isRepeated()) {
            return rs.getBytes(column);
        }
        return switch (fd.getType()) {
            case BYTES, MESSAGE, GROUP -> rs.getBytes(column);
            case STRING -> rs.getString(column);
            default -> rs.getObject(column);
        };
    }

    /** 把一列的值写回消息字段（见类注释）；value 为 null 表示 SQL NULL。 */
    static void applyColumnValue(Message.Builder builder, FieldDescriptor fd, Object value) {
        rejectRealOneof(fd);
        if (isTimestampField(fd)) {
            applyTimestamp(builder, fd, value);
            return;
        }
        if (fd.isRepeated()) {
            applyContainer(builder, fd, value);
            return;
        }
        if (value == null) {
            setScalarDefault(builder, fd);
            return;
        }
        String name = fd.getName();
        switch (fd.getType()) {
            case INT32 -> builder.setField(fd, (int) integerInRange(value, fd, "int32",
                    BigInteger.valueOf(Integer.MIN_VALUE), BigInteger.valueOf(Integer.MAX_VALUE)).longValue());
            case INT64 -> builder.setField(fd, integerInRange(value, fd, "int64",
                    BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE)).longValue());
            case UINT32 -> builder.setField(fd, (int) integerInRange(value, fd, "uint32",
                    BigInteger.ZERO, BigInteger.valueOf(0xffffffffL)).longValue());
            case UINT64 -> builder.setField(fd, integerInRange(value, fd, "uint64",
                    BigInteger.ZERO, UINT64_MAX).longValue());
            case ENUM -> builder.setField(fd, enumValue(fd, (int) integerInRange(value, fd, "enum",
                    BigInteger.valueOf(Integer.MIN_VALUE), BigInteger.valueOf(Integer.MAX_VALUE)).longValue()));
            case FLOAT -> builder.setField(fd, toFloat(value, fd));
            case DOUBLE -> builder.setField(fd, toDouble(value, fd));
            case BOOL -> builder.setField(fd, toBool(value, fd));
            case STRING -> builder.setField(fd, value instanceof byte[] raw
                    ? new String(raw, StandardCharsets.UTF_8) : value.toString());
            case BYTES -> builder.setField(fd, toByteString(value, fd));
            case MESSAGE, GROUP -> {
                byte[] raw = toBytes(value, fd);
                if (raw.length == 0) {
                    builder.clearField(fd);
                    return;
                }
                try {
                    builder.setField(fd, builder.newBuilderForField(fd).mergeFrom(raw).build());
                } catch (InvalidProtocolBufferException e) {
                    // 不打原始字节：里面是 proto 裸字节，进日志会喷控制字符
                    throw new RowConversionException("unmarshal sub-message field " + name + ": "
                            + e.getMessage() + " (" + raw.length + " bytes)", e);
                }
            }
            default -> throw new RowConversionException(
                    "invalid field kind: " + typeName(fd) + " (field: " + name + ")");
        }
    }

    /** SQL NULL：标量置默认值（Set 而非 Clear，proto3 optional 因此带 presence，与 Go 版一致），子消息清空。 */
    private static void setScalarDefault(Message.Builder builder, FieldDescriptor fd) {
        if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
            builder.clearField(fd);
        } else {
            builder.setField(fd, fd.getDefaultValue());
        }
    }

    private static void applyTimestamp(Message.Builder builder, FieldDescriptor fd, Object value) {
        if (value == null) {
            builder.clearField(fd);
            return;
        }
        Instant instant = toInstant(value, fd);
        Message.Builder ts = builder.newBuilderForField(fd);
        ts.setField(ts.getDescriptorForType().findFieldByNumber(1), instant.getEpochSecond());
        ts.setField(ts.getDescriptorForType().findFieldByNumber(2), instant.getNano());
        builder.setField(fd, ts.build());
    }

    /** 容器先清空再整体替换：复用 builder 时上一行的 map 键 / list 元素不会残留。 */
    private static void applyContainer(Message.Builder builder, FieldDescriptor fd, Object value) {
        builder.clearField(fd);
        if (value == null) {
            return;
        }
        byte[] raw = toBytes(value, fd);
        if (raw.length == 0) {
            return;
        }
        Message holder;
        try {
            holder = builder.getDefaultInstanceForType().newBuilderForType().mergeFrom(raw).build();
        } catch (InvalidProtocolBufferException e) {
            throw new RowConversionException("parse field " + fd.getName() + ": " + e.getMessage()
                    + " (" + raw.length + " bytes)", e);
        }
        if (holder.getRepeatedFieldCount(fd) == 0) {
            return;
        }
        builder.setField(fd, holder.getField(fd));
    }

    private static Instant toInstant(Object value, FieldDescriptor fd) {
        if (value instanceof LocalDateTime ldt) {
            return ldt.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof java.sql.Timestamp ts) {
            // 驱动按会话时区把 DATETIME 文本解析成了 java.sql.Timestamp；取回墙钟字段，按 UTC 解释（写入时就是 UTC 墙钟）
            return ts.toLocalDateTime().toInstant(ZoneOffset.UTC);
        }
        String raw = value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
        try {
            return LocalDateTime.parse(raw, MYSQL_DATETIME).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // 落到下面的 RFC3339 / 仅日期
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException ignored) {
            // 落到仅日期
        }
        try {
            return LocalDate.parse(raw).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new RowConversionException("parse timestamp field " + fd.getName() + ": " + e.getMessage()
                    + " (value: " + raw + ")", e);
        }
    }

    private static BigInteger integerInRange(Object value, FieldDescriptor fd, String kind, BigInteger min, BigInteger max) {
        BigInteger v;
        try {
            v = toBigInteger(value);
        } catch (ArithmeticException | NumberFormatException e) {
            throw parseError(kind, fd, value, e.getMessage());
        }
        if (v.compareTo(min) < 0 || v.compareTo(max) > 0) {
            throw parseError(kind, fd, value, "value out of range");
        }
        return v;
    }

    private static BigInteger toBigInteger(Object value) {
        if (value instanceof BigInteger bi) {
            return bi;
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof BigDecimal bd) {
            return bd.toBigIntegerExact();
        }
        if (value instanceof Boolean b) {
            return b ? BigInteger.ONE : BigInteger.ZERO;
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new NumberFormatException("non-finite value");
            }
            return new BigDecimal(d).toBigIntegerExact();
        }
        String raw = value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
        return new BigInteger(raw.trim());
    }

    private static float toFloat(Object value, FieldDescriptor fd) {
        if (value instanceof Float f) {
            return f;
        }
        if (value instanceof Number n) {
            return n.floatValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1f : 0f;
        }
        String raw = value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            throw parseError("float", fd, value, e.getMessage());
        }
    }

    private static double toDouble(Object value, FieldDescriptor fd) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1d : 0d;
        }
        String raw = value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw parseError("double", fd, value, e.getMessage());
        }
    }

    /** 驱动对 tinyint(1) 给 Boolean；数值只认 0 / 1，字符串按 Go strconv.ParseBool 的集合。 */
    private static boolean toBool(Object value, FieldDescriptor fd) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            BigInteger v;
            try {
                v = toBigInteger(n);
            } catch (ArithmeticException | NumberFormatException e) {
                throw parseError("bool", fd, value, e.getMessage());
            }
            if (v.equals(BigInteger.ONE)) {
                return true;
            }
            if (v.signum() == 0) {
                return false;
            }
            throw parseError("bool", fd, value, "invalid syntax");
        }
        String raw = value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
        return switch (raw) {
            case "1", "t", "T", "TRUE", "true", "True" -> true;
            case "0", "f", "F", "FALSE", "false", "False" -> false;
            default -> throw parseError("bool", fd, value, "invalid syntax");
        };
    }

    private static EnumValueDescriptor enumValue(FieldDescriptor fd, int number) {
        EnumDescriptor type = fd.getEnumType();
        EnumValueDescriptor v = type.findValueByNumber(number);
        if (v != null) {
            return v;
        }
        if (type.isClosed()) {
            throw new RowConversionException("parse enum field " + fd.getName() + ": closed enum "
                    + type.getFullName() + " has no value " + number);
        }
        return type.findValueByNumberCreatingIfUnknown(number);
    }

    private static ByteString toByteString(Object value, FieldDescriptor fd) {
        if (value instanceof byte[] raw) {
            return ByteString.copyFrom(raw);
        }
        if (value instanceof ByteString bs) {
            return bs;
        }
        if (value instanceof String s) {
            return ByteString.copyFromUtf8(s);
        }
        throw parseError("bytes", fd, value, "unsupported column value type " + value.getClass().getName());
    }

    private static byte[] toBytes(Object value, FieldDescriptor fd) {
        if (value instanceof byte[] raw) {
            return raw;
        }
        if (value instanceof ByteString bs) {
            return bs.toByteArray();
        }
        if (value instanceof String s) {
            return s.getBytes(StandardCharsets.UTF_8);
        }
        throw new RowConversionException("parse field " + fd.getName() + ": unsupported column value type "
                + value.getClass().getName());
    }

    private static RowConversionException parseError(String kind, FieldDescriptor fd, Object value, String reason) {
        String shown = value instanceof byte[] raw ? new String(raw, StandardCharsets.UTF_8) : String.valueOf(value);
        return new RowConversionException("parse " + kind + " field " + fd.getName() + ": " + reason
                + " (value: " + shown + ")");
    }

    static String typeName(FieldDescriptor fd) {
        return fd.getType().name().toLowerCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------------ 谓词参数

    /** 调用方传入的 WHERE 参数：把 protobuf 侧的类型换成驱动认识的值（ByteString → byte[]、枚举 → 数字、Timestamp → UTC LocalDateTime）。 */
    static Object toSqlArg(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ByteString bs) {
            return bs.toByteArray();
        }
        if (value instanceof ProtocolMessageEnum e) {
            return e.getNumber();
        }
        if (value instanceof EnumValueDescriptor e) {
            return e.getNumber();
        }
        if (value instanceof Timestamp ts) {
            Instant instant = Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
            return LocalDateTime.ofEpochSecond(instant.getEpochSecond(),
                    instant.getNano() - instant.getNano() % 1000, ZoneOffset.UTC);
        }
        if (value instanceof Message m) {
            return m.toByteArray();
        }
        if (value instanceof Float f) {
            // FLOAT 列与十进制字面量比较时会先提升成 DOUBLE；精确展宽后再下发，0.1f 才能等于自己
            return (double) f;
        }
        return value;
    }

    /**
     * 某一列的等值 / IN 谓词参数：数值列收到字符串时按列类型解析成带类型的参数（避免 MySQL 走 DOUBLE 比较丢 64 位精度）；
     * uint64 列收到 Long、uint32 列收到 Integer 时按 protobuf Java 的位模式解释成无符号值（getter 返回的就是位模式）。
     */
    static Object toComparisonArg(FieldDescriptor fd, Object value) {
        Object v = toSqlArg(value);
        if (fd.isRepeated()) {
            return v;
        }
        if (fd.getType() == FieldDescriptor.Type.UINT64 && v instanceof Long bits) {
            return uint64Value(bits);
        }
        if (fd.getType() == FieldDescriptor.Type.UINT32 && v instanceof Integer bits) {
            return Integer.toUnsignedLong(bits);
        }
        // 与 Go 版 normalizeComparisonArg 同义：FLOAT 列存的是 float32，比较值先舍到 float32，否则 0.1 永远匹配不上
        if (v instanceof Number n && !(v instanceof BigDecimal) && !(v instanceof BigInteger)) {
            if (fd.getType() == FieldDescriptor.Type.FLOAT) {
                return (double) n.floatValue();
            }
            if (fd.getType() == FieldDescriptor.Type.DOUBLE) {
                return n.doubleValue();
            }
        }
        if (!(v instanceof String raw)) {
            return v;
        }
        try {
            return switch (fd.getType()) {
                case INT32, ENUM -> (long) Integer.parseInt(raw);
                case INT64 -> Long.parseLong(raw);
                case UINT32 -> {
                    long n = Long.parseLong(raw);
                    if (n < 0 || n > 0xffffffffL) {
                        throw new NumberFormatException("value out of range");
                    }
                    yield n;
                }
                case UINT64 -> {
                    BigInteger n = new BigInteger(raw);
                    if (n.signum() < 0 || n.compareTo(UINT64_MAX) > 0) {
                        throw new NumberFormatException("value out of range");
                    }
                    yield n.bitLength() < 64 ? (Object) n.longValue() : n;
                }
                case FLOAT -> (double) Float.parseFloat(raw);
                case DOUBLE -> Double.parseDouble(raw);
                case BOOL -> switch (raw) {
                    case "1", "t", "T", "TRUE", "true", "True" -> true;
                    case "0", "f", "F", "FALSE", "false", "False" -> false;
                    default -> throw new NumberFormatException("invalid syntax");
                };
                default -> v;
            };
        } catch (NumberFormatException e) {
            throw new PbMysqlException("normalize comparison field " + fd.getName() + ": " + e.getMessage()
                    + " (value: " + raw + ")");
        }
    }
}
