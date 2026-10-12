package com.game.pbmysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.pbmysql.testpb.KitchenSink;
import com.game.pbmysql.testpb.OneofRow;
import com.game.pbmysql.testpb.Player;
import com.game.pbmysql.testpb.ScalarRow;
import com.game.pbmysql.testpb.StringKeyRow;
import com.game.pbmysql.testpb.Tier;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 消息 ↔ 列值（Go 版 pbconv）：不连库，用按列名取值的假 ResultSet 模拟 Connector/J 返回的 Java 类型。 */
class RowCodecTest {

    private static final TableSchema SCALAR = TableSchema.of(ScalarRow.getDefaultInstance());
    private static final TableSchema SINK = TableSchema.of(KitchenSink.getDefaultInstance());

    private static FieldDescriptor fd(Message m, String name) {
        return m.getDescriptorForType().findFieldByName(name);
    }

    private static Object value(TableSchema t, Message m, String name) {
        return t.columnValue(m, fd(m, name));
    }

    /** 只支持按列名取值的 ResultSet：位置取值一律抛异常，证明读取不依赖列顺序。 */
    private static ResultSet rowByName(Map<String, Object> row) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> {
                    if (args != null && args.length >= 1 && args[0] instanceof String label) {
                        if (!row.containsKey(label)) {
                            throw new SQLException("Column '" + label + "' not found.");
                        }
                        Object v = row.get(label);
                        return switch (method.getName()) {
                            case "getObject", "getBytes", "getString" -> v;
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    }
                    throw new UnsupportedOperationException("positional access: " + method.getName());
                });
    }

    private static final long UINT64_MAX_BITS = -1L;
    private static final BigInteger UINT64_MAX = new BigInteger("18446744073709551615");

    private static ScalarRow fullScalar() {
        return ScalarRow.newBuilder()
                .setId(UINT64_MAX_BITS)
                .setI32(Integer.MIN_VALUE)
                .setU32(-1)
                .setI64(Long.MIN_VALUE)
                .setU64(Long.MIN_VALUE) // 2^63
                .setF32(0.1f)
                .setF64(Math.PI)
                .setFlag(true)
                .setText("中文 😀 'quote'")
                .setBlob(ByteString.copyFrom(new byte[] {0, 1, (byte) 0xff, ' '}))
                .setTier(Tier.TIER_GOLD)
                .build();
    }

    // ================================================================ 写入

    @Test
    void 标量写入值_整数为Long_uint64超过2的63次方为BigInteger_bool与enum为Integer() {
        ScalarRow row = fullScalar();
        List<Object> values = SCALAR.columnValues(row);
        assertThat(values).containsExactly(
                UINT64_MAX,
                (long) Integer.MIN_VALUE,
                4294967295L,
                Long.MIN_VALUE,
                new BigInteger("9223372036854775808"),
                (double) 0.1f,
                Math.PI,
                1,
                "中文 😀 'quote'",
                new byte[] {0, 1, (byte) 0xff, ' '},
                1);
        assertThat(value(SCALAR, ScalarRow.newBuilder().setU64(42).build(), "u64")).isEqualTo(42L);
        assertThat(value(SCALAR, ScalarRow.getDefaultInstance(), "flag")).isEqualTo(0);
    }

    @Test
    void 浮点NaN与无穷拒绝写入() {
        assertThatThrownBy(() -> SCALAR.columnValues(ScalarRow.newBuilder().setF32(Float.NaN).build()))
                .isInstanceOf(RowConversionException.class)
                .hasMessage("non-finite float value (NaN/Inf) cannot be stored in MySQL: field f32 = NaN");
        assertThatThrownBy(() -> SCALAR.columnValues(ScalarRow.newBuilder().setF64(Double.NEGATIVE_INFINITY).build()))
                .isInstanceOf(RowConversionException.class)
                .hasMessageContaining("field f64 = -Infinity");
    }

    @Test
    void 非键string列含落单代理项拒绝写入_不让驱动静默改成问号() {
        assertThatThrownBy(() -> SCALAR.columnValues(ScalarRow.newBuilder().setText("a\uD800b").build()))
                .isInstanceOf(RowConversionException.class)
                .hasMessageContaining("string field text");
        assertThat(SCALAR.columnValues(ScalarRow.newBuilder().setText("ok😀").build())).isNotEmpty();
    }

    @Test
    void Timestamp写成UTC的LocalDateTime_截断到微秒_未设置与Go零时刻写NULL() {
        assertThat(value(SINK, KitchenSink.getDefaultInstance(), "created_at")).isNull();
        KitchenSink goZero = KitchenSink.newBuilder()
                .setCreatedAt(Timestamp.newBuilder().setSeconds(RowCodec.GO_ZERO_TIME_SECONDS)).build();
        assertThat(value(SINK, goZero, "created_at")).isNull();
        KitchenSink epoch = KitchenSink.newBuilder().setCreatedAt(Timestamp.getDefaultInstance()).build();
        assertThat(value(SINK, epoch, "created_at")).isEqualTo(LocalDateTime.of(1970, 1, 1, 0, 0));
        KitchenSink precise = KitchenSink.newBuilder()
                .setCreatedAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).setNanos(123_456_789)).build();
        assertThat(value(SINK, precise, "created_at")).isEqualTo(LocalDateTime.of(2023, 11, 14, 22, 13, 20, 123_456_000));
        // 1970 年之前：秒向下取整、纳秒非负，截断同样只砍掉纳秒尾巴
        KitchenSink before = KitchenSink.newBuilder()
                .setCreatedAt(Timestamp.newBuilder().setSeconds(-1).setNanos(999_999_999)).build();
        assertThat(value(SINK, before, "created_at")).isEqualTo(LocalDateTime.of(1969, 12, 31, 23, 59, 59, 999_999_000));
    }

    @Test
    void 子消息与容器写成wire裸字节_空的写空字节而不是NULL() {
        assertThat((byte[]) value(SINK, KitchenSink.getDefaultInstance(), "tags")).isEmpty();
        assertThat((byte[]) value(SINK, KitchenSink.getDefaultInstance(), "counters")).isEmpty();
        assertThat((byte[]) value(SINK, KitchenSink.getDefaultInstance(), "owner")).isEmpty();

        KitchenSink sink = KitchenSink.newBuilder()
                .addTags(1).addTags(300)
                .putCounters("gold", -5)
                .setOwner(Player.newBuilder().setPlayerId(7).setName("alice"))
                .build();
        // 与「只含该字段的同类型消息」的序列化逐字节相同（Go 版 serializeContainer）
        assertThat((byte[]) value(SINK, sink, "tags"))
                .isEqualTo(KitchenSink.newBuilder().addTags(1).addTags(300).build().toByteArray());
        assertThat((byte[]) value(SINK, sink, "counters"))
                .isEqualTo(KitchenSink.newBuilder().putCounters("gold", -5).build().toByteArray());
        assertThat((byte[]) value(SINK, sink, "owner")).isEqualTo(sink.getOwner().toByteArray());
    }

    @Test
    void 真实oneof成员在转换层同样拒绝() {
        OneofRow row = OneofRow.newBuilder().setA(1).build();
        assertThatThrownBy(() -> RowCodec.toColumnValue(row, fd(row, "a")))
                .isInstanceOf(RowConversionException.class)
                .hasMessage("invalid field kind: field a belongs to unsupported oneof \"choice\"");
        assertThatThrownBy(() -> RowCodec.applyColumnValue(OneofRow.newBuilder(), fd(row, "b"), "x"))
                .isInstanceOf(RowConversionException.class);
    }

    // ================================================================ 键列值校验

    @Test
    void 键列超长或非法UTF8在发SQL之前拒绝_消息不回显值() {
        KitchenSink longToken = KitchenSink.newBuilder().setToken(ByteString.copyFrom(new byte[65])).build();
        assertThatThrownBy(() -> SINK.columnValues(longToken))
                .isInstanceOf(InvalidKeyValueException.class)
                .hasMessage("表 kitchen_sink 的键列 token 长 65 字节，超过 VARBINARY(64) 的上限");
        assertThat(SINK.columnValues(KitchenSink.newBuilder().setToken(ByteString.copyFrom(new byte[64])).build())).hasSize(11);

        TableSchema strings = TableSchema.of(StringKeyRow.getDefaultInstance());
        assertThatThrownBy(() -> strings.columnValues(StringKeyRow.newBuilder().setSub("x".repeat(256)).build()))
                .isInstanceOf(InvalidKeyValueException.class)
                .hasMessage("表 string_key_row 的键列 sub 长 256 个字符，超过 VARCHAR(255) 的上限");
        // 4 字节的 emoji 按 1 个字符计
        assertThat(strings.columnValues(StringKeyRow.newBuilder().setSub("😀".repeat(255)).build())).hasSize(5);
        assertThatThrownBy(() -> strings.columnValues(StringKeyRow.newBuilder().setSub("a\ud800b").build()))
                .isInstanceOf(InvalidKeyValueException.class)
                .hasMessageStartingWith("表 string_key_row 的键列 sub 不是合法 UTF-8（");
        // 非键列不校验长度
        assertThat(strings.columnValues(StringKeyRow.newBuilder().setBio("x".repeat(10_000)).build())).hasSize(5);
    }

    // ================================================================ 读出

    @Test
    void 按列名读回_与列顺序无关_多出来的列忽略_JDBC类型还原() throws SQLException {
        ScalarRow expected = fullScalar();
        Map<String, Object> row = new HashMap<>();
        row.put("extra_column_from_v2", "ignored");
        row.put("tier", 1);
        row.put("blob", new byte[] {0, 1, (byte) 0xff, ' '});
        row.put("text", "中文 😀 'quote'");
        row.put("flag", Boolean.TRUE);                         // tinyint(1) → Boolean
        row.put("f64", Math.PI);
        row.put("f32", 0.1f);                                  // FLOAT → Float
        row.put("u64", new BigInteger("9223372036854775808")); // BIGINT UNSIGNED → BigInteger
        row.put("i64", Long.MIN_VALUE);
        row.put("u32", 4294967295L);                           // INT UNSIGNED → Long
        row.put("i32", Integer.MIN_VALUE);                     // INT → Integer
        row.put("id", UINT64_MAX);
        ScalarRow got = PbMysql.readRow(rowByName(row), SCALAR);
        assertThat(got).isEqualTo(expected);
    }

    @Test
    void uint64从BigInteger_BigDecimal_Long都无损还原_负数和越界报错() {
        FieldDescriptor u64 = fd(ScalarRow.getDefaultInstance(), "u64");
        for (Object v : List.of(UINT64_MAX, new BigDecimal(UINT64_MAX), "18446744073709551615")) {
            ScalarRow.Builder b = ScalarRow.newBuilder();
            RowCodec.applyColumnValue(b, u64, v);
            assertThat(b.getU64()).as("from %s", v.getClass().getSimpleName()).isEqualTo(-1L);
        }
        ScalarRow.Builder small = ScalarRow.newBuilder();
        RowCodec.applyColumnValue(small, u64, 42L);
        assertThat(small.getU64()).isEqualTo(42L);

        assertThatThrownBy(() -> RowCodec.applyColumnValue(ScalarRow.newBuilder(), u64, -1L))
                .isInstanceOf(RowConversionException.class)
                .hasMessage("parse uint64 field u64: value out of range (value: -1)");
        assertThatThrownBy(() -> RowCodec.applyColumnValue(ScalarRow.newBuilder(), u64, UINT64_MAX.add(BigInteger.ONE)))
                .isInstanceOf(RowConversionException.class);
        assertThatThrownBy(() -> RowCodec.applyColumnValue(ScalarRow.newBuilder(), u64, new BigDecimal("1.5")))
                .isInstanceOf(RowConversionException.class);
    }

    @Test
    void 有符号与32位整数越界报错而不是截断() {
        FieldDescriptor i32 = fd(ScalarRow.getDefaultInstance(), "i32");
        FieldDescriptor u32 = fd(ScalarRow.getDefaultInstance(), "u32");
        assertThatThrownBy(() -> RowCodec.applyColumnValue(ScalarRow.newBuilder(), i32, 2147483648L))
                .isInstanceOf(RowConversionException.class).hasMessageStartingWith("parse int32 field i32:");
        assertThatThrownBy(() -> RowCodec.applyColumnValue(ScalarRow.newBuilder(), u32, 4294967296L))
                .isInstanceOf(RowConversionException.class);
        assertThatThrownBy(() -> RowCodec.applyColumnValue(ScalarRow.newBuilder(), u32, -1))
                .isInstanceOf(RowConversionException.class);
        ScalarRow.Builder b = ScalarRow.newBuilder();
        RowCodec.applyColumnValue(b, u32, 4294967295L);
        assertThat(b.getU32()).isEqualTo(-1);
    }

    @Test
    void bool只认0和1与ParseBool的写法() {
        FieldDescriptor flag = fd(ScalarRow.getDefaultInstance(), "flag");
        for (Object v : List.of(Boolean.TRUE, 1, 1L, "true", "T", "1")) {
            ScalarRow.Builder b = ScalarRow.newBuilder();
            RowCodec.applyColumnValue(b, flag, v);
            assertThat(b.getFlag()).as("from %s", v).isTrue();
        }
        ScalarRow.Builder f = ScalarRow.newBuilder().setFlag(true);
        RowCodec.applyColumnValue(f, flag, 0);
        assertThat(f.getFlag()).isFalse();
        assertThatThrownBy(() -> RowCodec.applyColumnValue(ScalarRow.newBuilder(), flag, 2))
                .isInstanceOf(RowConversionException.class).hasMessage("parse bool field flag: invalid syntax (value: 2)");
    }

    @Test
    void kitchen_sink整行往返_Timestamp_容器_子消息_optional_bytes键() throws SQLException {
        KitchenSink original = KitchenSink.newBuilder()
                .setOwnerId(UINT64_MAX_BITS)
                .setSlot(3)
                .setToken(ByteString.copyFrom(new byte[] {'a', 0, ' '}))
                .setNote("note")
                .setScore(-7)
                .setCreatedAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).setNanos(123_456_789))
                .addTags(1).addTags(300)
                .putCounters("gold", -5).putCounters("silver", 9)
                .setOwner(Player.newBuilder().setPlayerId(7).setName("alice"))
                .setOptValue(0)
                .setTier(Tier.TIER_SILVER)
                .build();
        List<Object> written = SINK.columnValues(original);
        Map<String, Object> row = new HashMap<>();
        List<FieldDescriptor> fields = KitchenSink.getDescriptor().getFields();
        for (int i = 0; i < fields.size(); i++) {
            row.put(fields.get(i).getName(), written.get(i));
        }
        // 模拟 Connector/J 读回的类型
        row.put("owner_id", UINT64_MAX);
        row.put("score", -7);
        row.put("tier", 2);

        KitchenSink got = PbMysql.readRow(rowByName(row), SINK);
        KitchenSink expected = original.toBuilder()
                .setCreatedAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).setNanos(123_456_000))
                .build();
        assertThat(got).isEqualTo(expected);
        assertThat(got.hasOptValue()).as("proto3 optional 的 0 也带 presence").isTrue();
    }

    @Test
    void SQL_NULL_标量置默认值_optional带presence_子消息Timestamp容器清空() {
        KitchenSink.Builder b = KitchenSink.newBuilder()
                .setScore(5).setNote("x").setOptValue(9)
                .setCreatedAt(Timestamp.newBuilder().setSeconds(1))
                .setOwner(Player.newBuilder().setPlayerId(1))
                .addTags(1).putCounters("k", 1);
        for (String name : List.of("score", "note", "opt_value", "created_at", "owner", "tags", "counters")) {
            RowCodec.applyColumnValue(b, fd(b.build(), name), null);
        }
        assertThat(b.getScore()).isZero();
        assertThat(b.getNote()).isEmpty();
        assertThat(b.hasOptValue()).isTrue();
        assertThat(b.getOptValue()).isZero();
        assertThat(b.hasCreatedAt()).isFalse();
        assertThat(b.hasOwner()).isFalse();
        assertThat(b.getTagsList()).isEmpty();
        assertThat(b.getCountersMap()).isEmpty();
    }

    @Test
    void 复用builder时容器先清空_上一行的map键不残留() {
        KitchenSink.Builder b = KitchenSink.newBuilder().putCounters("alice_only", 1).addTags(9);
        byte[] bob = KitchenSink.newBuilder().putCounters("bob", 2).build().toByteArray();
        RowCodec.applyColumnValue(b, fd(b.build(), "counters"), bob);
        RowCodec.applyColumnValue(b, fd(b.build(), "tags"), new byte[0]);
        assertThat(b.getCountersMap()).containsExactly(Map.entry("bob", 2L));
        assertThat(b.getTagsList()).isEmpty();
    }

    @Test
    void 读回Timestamp接受LocalDateTime与MySQL文本与RFC3339() {
        FieldDescriptor created = fd(KitchenSink.getDefaultInstance(), "created_at");
        Timestamp want = Timestamp.newBuilder().setSeconds(1_700_000_000L).setNanos(123_456_000).build();
        for (Object v : List.of(LocalDateTime.of(2023, 11, 14, 22, 13, 20, 123_456_000),
                "2023-11-14 22:13:20.123456", "2023-11-14T22:13:20.123456Z")) {
            KitchenSink.Builder b = KitchenSink.newBuilder();
            RowCodec.applyColumnValue(b, created, v);
            assertThat(b.getCreatedAt()).as("from %s", v).isEqualTo(want);
        }
    }

    @Test
    void 损坏的BLOB报错且不回显字节() {
        FieldDescriptor owner = fd(KitchenSink.getDefaultInstance(), "owner");
        assertThatThrownBy(() -> RowCodec.applyColumnValue(KitchenSink.newBuilder(), owner, new byte[] {(byte) 0xff, 0x01}))
                .isInstanceOf(RowConversionException.class)
                .hasMessageStartingWith("unmarshal sub-message field owner:")
                .hasMessageEndingWith("(2 bytes)");
    }

    @Test
    void 谓词参数_数值列的字符串按列类型解析_protobuf类型换成驱动类型() {
        FieldDescriptor u64 = fd(ScalarRow.getDefaultInstance(), "u64");
        assertThat(RowCodec.toComparisonArg(u64, "18446744073709551615")).isEqualTo(UINT64_MAX);
        assertThat(RowCodec.toComparisonArg(u64, "42")).isEqualTo(42L);
        // protobuf Java 的 uint64 getter 返回位模式：负的 long 按无符号值解释
        assertThat(RowCodec.toComparisonArg(u64, -1L)).isEqualTo(UINT64_MAX);
        assertThat(RowCodec.toComparisonArg(fd(ScalarRow.getDefaultInstance(), "u32"), -1)).isEqualTo(4294967295L);
        assertThat(RowCodec.toComparisonArg(fd(ScalarRow.getDefaultInstance(), "f32"), 0.1f)).isEqualTo((double) 0.1f);
        // FLOAT 列：double / 整数比较值先舍到 float32（Go normalizeComparisonArg 同义）
        assertThat(RowCodec.toComparisonArg(fd(ScalarRow.getDefaultInstance(), "f32"), 0.1d)).isEqualTo((double) 0.1f);
        assertThat(RowCodec.toComparisonArg(fd(ScalarRow.getDefaultInstance(), "f32"), 3)).isEqualTo(3.0d);
        assertThat(RowCodec.toSqlArg(Tier.TIER_GOLD)).isEqualTo(1);
        assertThat(RowCodec.toSqlArg(ByteString.copyFromUtf8("ab"))).isEqualTo(new byte[] {'a', 'b'});
        assertThatThrownBy(() -> RowCodec.toComparisonArg(u64, "-1")).isInstanceOf(PbMysqlException.class);
    }
}
