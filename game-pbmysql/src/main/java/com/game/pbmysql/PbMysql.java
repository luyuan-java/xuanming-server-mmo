package com.game.pbmysql;

import static com.game.pbmysql.MysqlSyntax.escapeName;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * proto ↔ MySQL 表映射的入口：注册表、生成 DDL、只扩不缩地同步结构、按消息做 CRUD。用户自有库 proto2mysql（Go v0.2.0）
 * 的 Java 实现，语义逐项对齐 Go 版，差异写在各方法注释里。
 *
 * <p>用法：
 * <pre>{@code
 * PbMysql db = new PbMysql();
 * db.register(FriendEdgeRecord.getDefaultInstance());          // 表选项从 proto 的 option 读
 * try (Connection c = dataSource.getConnection()) {
 *     db.syncAll(c);                                           // 建表 / 只加列与索引
 * }
 * try (Connection c = dataSource.getConnection()) {
 *     c.setAutoCommit(false);
 *     db.insert(c, edge);
 *     Optional<FriendEdgeRecord> row = db.findOneByPkForUpdate(c, key);
 *     c.commit();
 * }
 * }</pre>
 *
 * <ul>
 *   <li>不持有任何全局状态与连接：所有数据库方法都接收调用方的 {@link Connection}，事务由调用方掌握；本类不提交、不回滚、不关连接。</li>
 *   <li>线程安全：注册表是并发容器，{@link TableSchema} 不可变。</li>
 *   <li>每条语句都显式列出列（从不 {@code SELECT *}），读回时按列名取值；全部走 {@link PreparedStatement} 参数绑定。</li>
 *   <li>{@code where} 参数是原样拼在 {@code WHERE} 之后的裸 SQL，只能来自代码常量，取值一律走 {@code args}。</li>
 * </ul>
 */
public final class PbMysql {

    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    private final ConcurrentHashMap<String, TableSchema> byFullName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, TableSchema> byClass = new ConcurrentHashMap<>();

    // ================================================================ 注册

    /**
     * 注册一个消息类型：先读 proto 描述符里的表选项，再按顺序应用 {@code overrides}（覆盖前者），校验通过后登记。
     * 同一个消息类型（按 proto full name）再次注册会替换前一次。
     *
     * <p>两个不同的消息映射到同一张物理表（表名大小写不敏感）在这里就拒绝、什么都不登记：Go 版在每次 DML 查表时都拒，
     * 不能只靠 {@link #syncAll} 里的检查——由迁移工具建表、从不调 syncAll 的进程会用 V1 写、用 V2 读出错位的数据。
     *
     * @param prototype 该消息类型的任意实例（通常是 {@code Xxx.getDefaultInstance()}）
     * @throws InvalidTableDefinitionException 表定义非法，或与已注册的另一个消息映射到同一张物理表
     */
    public synchronized TableSchema register(Message prototype, TableOption... overrides) {
        TableSchema schema = TableSchema.of(prototype, overrides);
        String fullName = schema.descriptor().getFullName();
        for (TableSchema existing : byFullName.values()) {
            if (!existing.descriptor().getFullName().equals(fullName)
                    && existing.tableName().equalsIgnoreCase(schema.tableName())) {
                List<TableSchema> pair = new ArrayList<>(List.of(existing, schema));
                SchemaSync.checkDuplicateMappings(pair); // 与 syncAll 同一条错误信息
            }
        }
        byFullName.put(fullName, schema);
        if (!(schema.prototype() instanceof DynamicMessage)) {
            byClass.put(schema.prototype().getClass(), schema);
        }
        return schema;
    }

    /** 已注册的全部表。 */
    public Collection<TableSchema> tables() {
        return Collections.unmodifiableCollection(new ArrayList<>(byFullName.values()));
    }

    /** 按消息类取已注册的表。 */
    public TableSchema schema(Class<? extends Message> type) {
        TableSchema schema = byClass.get(type);
        if (schema == null) {
            throw new PbMysqlException("table not found: 消息类型 " + type.getName() + " 没有注册到 PbMysql");
        }
        return schema;
    }

    /** 按消息实例取已注册的表（描述符必须是注册时的同一个）。 */
    public TableSchema schema(Message message) {
        TableSchema schema = byFullName.get(message.getDescriptorForType().getFullName());
        if (schema == null) {
            throw new PbMysqlException("table not found: 消息 " + message.getDescriptorForType().getFullName()
                    + " 没有注册到 PbMysql");
        }
        schema.requireSameType(message);
        return schema;
    }

    // ================================================================ 结构

    /** 某张已注册表的建表语句（带结尾分号），与 Go 版 GetCreateTableSQL 逐字节相同。 */
    public String createTableSql(Class<? extends Message> type) {
        return schema(type).createTableSql();
    }

    /**
     * 对全部已注册的表做只扩不缩的结构同步：表不存在就建；存在就只 ADD 缺的列、索引、唯一键与（线上完全没有的）主键；
     * 任何需要 MODIFY / CHANGE / DROP 才能对齐的差异抛 {@link SchemaDriftException}（该表不执行任何 DDL），从不删列删索引。
     *
     * <p>整轮在 {@code conn} 这一条连接上执行并持有 {@code GET_LOCK} 咨询锁；连接必须处于自动提交模式（DDL 会隐式提交）。
     * 两个不同 message 映射到同一张物理表时在任何 I/O 之前拒绝。
     */
    public void syncAll(Connection conn) throws SQLException {
        List<TableSchema> all = new ArrayList<>(byFullName.values());
        SchemaSync.sync(conn, all, all);
    }

    /** 只同步一张表（同样持锁、同样先对整个注册表做物理表名判重）。 */
    public void syncTable(Connection conn, Class<? extends Message> type) throws SQLException {
        SchemaSync.sync(conn, List.of(schema(type)), new ArrayList<>(byFullName.values()));
    }

    // ================================================================ 写

    /** 整行插入（全部列，零值照写）。 */
    public void insert(Connection conn, Message message) throws SQLException {
        TableSchema t = schema(message);
        executeUpdate(conn, t.insertSql(), t.columnValues(message));
    }

    /**
     * 幂等插入：普通 INSERT，只把 MySQL 1062（主键 / 唯一键冲突）解释成「未插入」返回 false，其它错误照常抛出。
     * 不用 {@code INSERT IGNORE}：它会把截断、越界、NOT NULL 等真实错误降级成 warning。
     */
    public boolean insertIgnore(Connection conn, Message message) throws SQLException {
        TableSchema t = schema(message);
        List<Object> values = t.columnValues(message);
        try {
            return executeUpdate(conn, t.insertSql(), values) > 0;
        } catch (SQLException e) {
            if (isDuplicateKey(e)) {
                return false;
            }
            throw e;
        }
    }

    /** 插入并返回自增主键（驱动的 generated keys）；表没有自增列时返回 0。 */
    public long insertReturningId(Connection conn, Message message) throws SQLException {
        TableSchema t = schema(message);
        List<Object> values = t.columnValues(message);
        try (PreparedStatement ps = conn.prepareStatement(t.insertSql(), Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, values);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    return 0L;
                }
                // BIGINT UNSIGNED 自增值 ≥ 2^63 时驱动给 BigInteger，getLong 会抛（而行已经插入）：取位模式，与 Go 的 int64 同值
                Object key = keys.getObject(1);
                return key instanceof BigInteger big ? big.longValue() : ((Number) key).longValue();
            }
        }
    }

    /**
     * 单语句 upsert：{@code INSERT ... ON DUPLICATE KEY UPDATE}，覆盖全部非主键列，每个赋值都受完整主键守卫
     * （{@code c = IF(pk <=> VALUES(pk), VALUES(c), c)}，同 Go 版 GetSaveSQLWithArgs）。撞上备用唯一键的另一行时
     * 那一行保持原样、本方法不报错——需要把这种冲突报出来时用 {@link #save}。要求表有主键。
     */
    public void upsert(Connection conn, Message message) throws SQLException {
        TableSchema t = schema(message);
        t.requirePrimaryKey();
        executeUpdate(conn, t.upsertSql(), t.columnValues(message));
    }

    /**
     * 按完整主键整行保存（同 Go 版 Save / InsertOnDupUpdate）：先按主键 UPDATE 全部非主键列，没命中再 INSERT；
     * INSERT 撞 1062 时重试一次 UPDATE（并发插入了同主键），仍为 0 再用 {@code SELECT ... FOR UPDATE} 当前读核对整行——
     * 线上那一行正好等于要写的值则成功，否则是备用唯一键撞上了另一行，抛 {@link DuplicateKeyException}，不改那一行。
     * UPDATE 阶段撞上备用唯一键同样抛 {@link DuplicateKeyException}。
     *
     * <p>并发注意（与 Go 版相同）：在 REPEATABLE READ 事务里，两个事务对同一个<b>尚不存在</b>的主键并发 save，
     * 第一步 UPDATE 落空时各自持有间隙锁、随后的 INSERT 互等插入意向锁，会成 1213 死锁——调用方须整事务重试，
     * 或先锁住一行已存在的父行（例如 owner 行 FOR UPDATE）把同主键的写者串行化，或改用 READ COMMITTED。
     */
    public void save(Connection conn, Message message) throws SQLException {
        TableSchema t = schema(message);
        t.requirePrimaryKey();
        List<Object> all = t.columnValues(message);
        List<Object> updateArgs = fullRowUpdateArgs(t, all);
        String update = t.fullRowUpdateSql();
        if (saveUpdate(conn, t, update, updateArgs) > 0) {
            return;
        }
        SQLException duplicate;
        try {
            executeUpdate(conn, t.insertSql(), all);
            return;
        } catch (SQLException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
            duplicate = e;
        }
        if (saveUpdate(conn, t, update, updateArgs) > 0) {
            return;
        }
        // 参数顺序：主键值，再非主键值（复用实际执行过的那组值，不重新序列化）
        List<Object> matchArgs = new ArrayList<>(updateArgs.subList(updateArgs.size() - t.primaryKey().size(), updateArgs.size()));
        matchArgs.addAll(updateArgs.subList(0, updateArgs.size() - t.primaryKey().size()));
        try (PreparedStatement ps = conn.prepareStatement(t.saveMatchSql())) {
            bind(ps, matchArgs);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return; // 同一行、值也完全相同（驱动按 affected rows 计数时 UPDATE 会返回 0）
                }
            }
        }
        throw new DuplicateKeyException("save on table " + t.tableName() + " conflicts with a different unique row", duplicate);
    }

    /**
     * 按主键覆盖全部非主键列（零值 / 未设置字段照写），返回是否命中了行。
     *
     * <p>与 Go 版 {@code Update} 的有意差异：Go 只写 proto3 意义上「已赋值」的字段，把字段改回 0 / "" / false 的写入会被静默跳过；
     * 这里取整行语义（与 Save 的 UPDATE 阶段相同）。只改部分列用 {@link #updateFieldsByPk}。
     * 用 Connector/J 缺省配置时「命中但值没变」也算命中（found rows）。
     */
    public boolean updateByPk(Connection conn, Message message) throws SQLException {
        TableSchema t = schema(message);
        t.requirePrimaryKey();
        return executeUpdate(conn, t.fullRowUpdateSql(), fullRowUpdateArgs(t, t.columnValues(message))) > 0;
    }

    /** 按主键只更新指定列（零值照写），返回是否命中了行（同 Go 版 UpdateFieldsByPK）。 */
    public boolean updateFieldsByPk(Connection conn, Message message, String... columns) throws SQLException {
        if (columns.length == 0) {
            throw new PbMysqlException("no fields to update");
        }
        TableSchema t = schema(message);
        List<String> sets = new ArrayList<>(columns.length);
        List<Object> args = new ArrayList<>(columns.length + t.primaryKey().size());
        for (String column : columns) {
            FieldDescriptor fd = requireField(t, column);
            sets.add(escapeName(column) + " = ?");
            args.add(t.columnValue(message, fd));
        }
        args.addAll(t.primaryKeyValues(message));
        String sql = "UPDATE " + escapeName(t.tableName()) + " SET " + String.join(", ", sets)
                + " WHERE " + t.primaryKeyWhereSql();
        return executeUpdate(conn, sql, args) > 0;
    }

    /** 按主键删除，返回是否删到了行。只序列化主键列（唯一键列的值不影响删除）。 */
    public boolean deleteByPk(Connection conn, Message key) throws SQLException {
        TableSchema t = schema(key);
        List<Object> pk = t.primaryKeyValues(key);
        return executeUpdate(conn, "DELETE FROM " + escapeName(t.tableName()) + " WHERE " + t.primaryKeyWhereSql(), pk) > 0;
    }

    // ================================================================ 读

    /** 按 key 里的主键值查整行；查不到为空。key 只需要填主键字段。 */
    public <T extends Message> Optional<T> findOneByPk(Connection conn, T key) throws SQLException {
        TableSchema t = schema(key);
        List<Object> pk = t.primaryKeyValues(key);
        return queryOne(conn, t, key, t.selectSql() + " WHERE " + t.primaryKeyWhereSql(), pk);
    }

    /**
     * 同 {@link #findOneByPk}，带 {@code FOR UPDATE} 行锁，锁到调用方提交 / 回滚。只在事务里有意义：
     * 连接处于自动提交模式时直接拒绝（单句提交，锁立即释放，等于没锁）。
     */
    public <T extends Message> Optional<T> findOneByPkForUpdate(Connection conn, T key) throws SQLException {
        if (conn.getAutoCommit()) {
            throw new IllegalStateException("findOneByPkForUpdate 必须在事务里调用（连接处于自动提交模式，行锁会随单句提交立即释放）");
        }
        TableSchema t = schema(key);
        List<Object> pk = t.primaryKeyValues(key);
        return queryOne(conn, t, key, t.selectSql() + " WHERE " + t.primaryKeyWhereSql() + " FOR UPDATE", pk);
    }

    /**
     * 按条件查一行：{@code where} 原样拼在 WHERE 后（空 = 全表），{@code args} 绑定到其中的 {@code ?}。
     * 查到多行抛 {@link PbMysqlException}（调用方以为条件唯一但实际不是，不能随便挑一行）。
     */
    public <T extends Message> Optional<T> findOne(Connection conn, Class<T> type, String where, Object... args)
            throws SQLException {
        TableSchema t = schema(type);
        return queryOne(conn, t, t.prototype(), t.selectSql() + " WHERE " + normalizeWhere(where), sqlArgs(args));
    }

    /** 按条件查多行：{@code where} 原样拼在 WHERE 后（空 = 全表），{@code args} 绑定到其中的 {@code ?}。 */
    public <T extends Message> List<T> findAll(Connection conn, Class<T> type, String where, Object... args)
            throws SQLException {
        TableSchema t = schema(type);
        return queryList(conn, t, t.selectSql() + " WHERE " + normalizeWhere(where), sqlArgs(args));
    }

    /**
     * {@code WHERE `column` IN (?, ?, ...)}，占位符按取值个数展开；取值为空直接返回空列表（不发 SQL）。
     * 数值列收到字符串时按列类型解析成带类型的参数（避免 MySQL 走 DOUBLE 比较丢 64 位精度）。
     */
    public <T extends Message> List<T> findAllByKvIn(Connection conn, Class<T> type, String column, Collection<?> values)
            throws SQLException {
        TableSchema t = schema(type);
        FieldDescriptor fd = requireField(t, column);
        if (values.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>(values.size());
        for (Object v : values) {
            args.add(RowCodec.toComparisonArg(fd, v));
        }
        String where = escapeName(column) + " IN (" + String.join(", ", Collections.nCopies(args.size(), "?")) + ")";
        return queryList(conn, t, t.selectSql() + " WHERE " + where, args);
    }

    /** {@code SELECT COUNT(*) FROM t WHERE <where>}（空 = 全表）。 */
    public long count(Connection conn, Class<? extends Message> type, String where, Object... args) throws SQLException {
        TableSchema t = schema(type);
        String sql = "SELECT COUNT(*) FROM " + escapeName(t.tableName()) + " WHERE " + normalizeWhere(where);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, sqlArgs(args));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /**
     * 把 protobuf Java 里 uint64 字段的 long 位模式（≥ 2^63 时为负数）换成可直接绑定到 {@code where} 参数的无符号值。
     * {@code where} 是裸 SQL，本库不知道 {@code ?} 对着哪一列，直接传负的 long 会按有符号数比较、什么都匹配不上。
     * （{@link #findAllByKvIn} 知道列类型，会自动转换。）
     */
    public static Object uint64(long bits) {
        return RowCodec.uint64Value(bits);
    }

    /** 同 {@link #uint64}：protobuf Java 里 uint32 字段的 int 位模式（≥ 2^31 时为负数）换成无符号值再绑定到 {@code where}。 */
    public static long uint32(int bits) {
        return Integer.toUnsignedLong(bits);
    }

    // ================================================================ 内部

    private static List<Object> fullRowUpdateArgs(TableSchema t, List<Object> allValues) {
        List<Object> args = new ArrayList<>(allValues.size());
        List<FieldDescriptor> fields = t.fields();
        for (int i = 0; i < fields.size(); i++) {
            if (!t.primaryKey().contains(fields.get(i).getName())) {
                args.add(allValues.get(i));
            }
        }
        for (String pk : t.primaryKey()) {
            args.add(allValues.get(fields.indexOf(t.field(pk))));
        }
        return args;
    }

    private static FieldDescriptor requireField(TableSchema t, String column) {
        FieldDescriptor fd = t.field(column);
        if (fd == null) {
            throw new PbMysqlException("field not found in message: " + column + " in table " + t.tableName());
        }
        return fd;
    }

    private static String normalizeWhere(String where) {
        return where == null || where.isBlank() ? "1=1" : where;
    }

    private static List<Object> sqlArgs(Object[] args) {
        List<Object> out = new ArrayList<>(args == null ? 0 : args.length);
        if (args != null) {
            for (Object a : args) {
                out.add(RowCodec.toSqlArg(a));
            }
        }
        return out;
    }

    /** save 的 UPDATE 阶段：撞上备用唯一键（1062）同样定性为 {@link DuplicateKeyException}（Go 版 ErrDuplicateKey 同义）。 */
    private static int saveUpdate(Connection conn, TableSchema t, String update, List<Object> args) throws SQLException {
        try {
            return executeUpdate(conn, update, args);
        } catch (SQLException e) {
            if (isDuplicateKey(e)) {
                throw new DuplicateKeyException("save on table " + t.tableName() + " conflicts with a different unique row", e);
            }
            throw e;
        }
    }

    private static int executeUpdate(Connection conn, String sql, List<Object> args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            Object v = args.get(i);
            if (v == null) {
                ps.setNull(i + 1, Types.NULL);
            } else {
                ps.setObject(i + 1, v);
            }
        }
    }

    private static <T extends Message> Optional<T> queryOne(Connection conn, TableSchema t, Message source,
                                                           String sql, List<Object> args)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                T row = readRow(rs, t, source);
                if (rs.next()) {
                    throw new PbMysqlException("multiple rows found: 表 " + t.tableName() + " 的条件命中了不止一行");
                }
                return Optional.of(row);
            }
        }
    }

    private static <T extends Message> List<T> queryList(Connection conn, TableSchema t, String sql, List<Object> args)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(readRow(rs, t, t.prototype()));
                }
                return out;
            }
        }
    }

    /** 当前行 → 消息：逐字段按列名取值（与列在结果集里的位置无关，结果集里多出来的列不读）。 */
    static <T extends Message> T readRow(ResultSet rs, TableSchema t) throws SQLException {
        return readRow(rs, t, t.prototype());
    }

    /**
     * 同上，结果按 {@code source} 的具体类型构造：按主键查时用调用方传进来的 key，生成类与 DynamicMessage 两种形态
     * 混用时也返回调用方期望的 T（Go 版填的就是调用方传入的那个消息）。
     */
    @SuppressWarnings("unchecked")
    static <T extends Message> T readRow(ResultSet rs, TableSchema t, Message source) throws SQLException {
        Message.Builder builder = source.newBuilderForType();
        for (FieldDescriptor fd : t.fields()) {
            RowCodec.applyColumnValue(builder, fd, RowCodec.readColumn(rs, fd.getName(), fd));
        }
        return (T) builder.build();
    }

    /** MySQL 1062 Duplicate entry（顺着 cause 与 next exception 找）。 */
    static boolean isDuplicateKey(SQLException e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof SQLException s) {
                for (SQLException n = s; n != null; n = n.getNextException()) {
                    if (n.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
