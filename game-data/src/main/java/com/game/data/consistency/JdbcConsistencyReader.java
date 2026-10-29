package com.game.data.consistency;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.GameNamespace;
import com.game.common.deadline.Deadline;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 专用 SELECT 账号池；不注册 DataSource bean，不借会初始化业务表的公共池。 */
public final class JdbcConsistencyReader implements ConsistencySqlReader, AutoCloseable {
    private final DruidDataSource pool;
    private final GameNamespace namespace;
    private final boolean requireTiDb;

    /** 仅包内替身测试注入池；生产装配只能使用配置专用池的公开构造器。 */
    JdbcConsistencyReader(DruidDataSource pool, GameNamespace namespace, boolean requireTiDb) {
        this.pool = pool; this.namespace = namespace; this.requireTiDb = requireTiDb;
    }

    public JdbcConsistencyReader(String url, String username, String password, GameNamespace namespace,
            boolean requireTiDb) {
        if (url == null || !url.startsWith("jdbc:mysql://") || username == null || username.isBlank())
            throw new IllegalArgumentException("巡检必须配置独立MySQL协议只读连接");
        String decodedUrl=java.net.URLDecoder.decode(url,java.nio.charset.StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if(decodedUrl.contains("createdatabaseifnotexist")||decodedUrl.contains("sessionvariables"))
            throw new IllegalArgumentException("巡检连接禁止隐式建库或自定义会话SQL");
        this.namespace = namespace; this.requireTiDb = requireTiDb;
        pool = new DruidDataSource();
        pool.setUrl(url); pool.setUsername(username); pool.setPassword(password);
        pool.setInitialSize(0); pool.setMinIdle(0); pool.setMaxActive(1); pool.setMaxWait(2_000);
        pool.setConnectionProperties("connectTimeout=2000;socketTimeout=2000;autoReconnect=false");
        pool.setTestOnBorrow(false); pool.setTestWhileIdle(false); pool.setDefaultReadOnly(true);
        new StrongTiDbConnectionPolicy("consistency", requireTiDb).postProcessBeforeInitialization(pool, "consistency");
    }

    @Override public List<Map<String, Object>> query(String sql, List<Object> parameters, int maxRows,
            ConsistencyBudget budget) {
        if (!sql.startsWith("SELECT ") || maxRows < 1 || maxRows > 2_001)
            throw new IllegalArgumentException("只允许有界内部SELECT");
        // 一次请求的两秒包含借连接、身份、强读证明、执行、解码与关闭。
        Deadline request = Deadline.after(budget.requestMillis());
        long borrowMillis=request.remainingMillis();
        // Druid把0解释为无限等待，不能把不足1ms的剩余预算原样交给池。
        if(borrowMillis<1)throw new ConsistencyBudget.Failure("budget_exhausted");
        try (Connection c = guarded(pool.getConnection(borrowMillis), budget, request)) {
            constrain(c, budget, request);
            c.setReadOnly(true);
            if (!c.getAutoCommit() || !c.isReadOnly()) throw new ConsistencyBudget.Failure("strong_read_unproved");
            var metadata = c.getMetaData();
            String product = metadata.getDatabaseProductName();
            if (!"MySQL".equalsIgnoreCase(product) && !"TiDB".equalsIgnoreCase(product))
                throw new ConsistencyBudget.Failure("unsupported_database");
            try { StrongTiDbConnectionPolicy.verify(c, requireTiDb, request); }
            catch (SQLException e) { throw new ConsistencyBudget.Failure("strong_read_unproved"); }
            constrain(c, budget, request);
            try (var statement = c.createStatement(); var rows = statement.executeQuery("SELECT DATABASE()")) {
                if (!rows.next() || !namespace.value().equals(rows.getString(1)) || rows.next())
                    throw new ConsistencyBudget.Failure("namespace_mismatch");
            }
            constrain(c, budget, request);
            var result = new ArrayList<Map<String, Object>>();
            try (var statement = c.prepareStatement(sql)) {
                statement.setQueryTimeout(Math.max(1, (int) ((request.remainingMillis() + 999) / 1_000)));
                statement.setMaxRows(maxRows + 1);
                for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i));
                try (var rows = statement.executeQuery()) {
                    var columns = rows.getMetaData();
                    while (rows.next()) {
                        constrain(c, budget, request);
                        if (result.size() == maxRows) throw new ConsistencyBudget.Failure("response_limit");
                        var row = new LinkedHashMap<String, Object>();
                        for (int i = 1; i <= columns.getColumnCount(); i++)
                            row.put(columns.getColumnLabel(i).toLowerCase(Locale.ROOT), rows.getObject(i));
                        result.add(row);
                    }
                }
            }
            constrain(c, budget, request);
            return result;
        } catch (SQLTimeoutException e) {
            throw new ConsistencyBudget.Failure("query_timeout");
        } catch (SQLException e) {
            String state = e.getSQLState();
            throw new ConsistencyBudget.Failure(state != null && state.startsWith("42") ? "schema_unavailable" : "query_failed");
        }
    }

    private static void constrain(Connection c, ConsistencyBudget budget, Deadline request) throws SQLException {
        long millis = Math.min(budget.requestMillis(), request.remainingMillis());
        if (millis < 1) throw new ConsistencyBudget.Failure("budget_exhausted");
        c.setNetworkTimeout(Runnable::run, (int) millis);
        if (c.getNetworkTimeout() > millis || c.getNetworkTimeout() == 0)
            throw new ConsistencyBudget.Failure("network_budget_unproved");
    }

    /**
     * 覆盖证明查询内部的execute、最后一次next、元数据与资源关闭；装饰原借出会话，
     * 不开后台SQL线程，验证器不能将旧网络超时恢复到本次剩余预算之外。
     */
    static Connection guarded(Connection raw, ConsistencyBudget budget, Deadline request) {
        return (Connection) new Guard(raw,budget,request).wrap(raw,Connection.class);
    }
    private static final class Guard {
        final Connection raw;final ConsistencyBudget budget;final Deadline request;
        boolean quarantined;
        Guard(Connection raw,ConsistencyBudget budget,Deadline request){this.raw=raw;this.budget=budget;this.request=request;}
        Object wrap(Object delegate,Class<?> api) {
          return Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (proxy, method, args) -> {
            String name=method.getName();
            if(name.equals("unwrap")||name.equals("isWrapperFor"))throw new SQLException("巡检不支持绕过预算包装");
            boolean closing=name.equals("close");
            if(quarantined){if(closing)return null;throw new ConsistencyBudget.Failure("connection_quarantined");}
            if(closing) {
                // 必须释放原会话，即使预算刚好用完；没有剩余时以1ms网络界限清理并返回UNKNOWN。
                try { raw.setNetworkTimeout(Runnable::run,(int)Math.max(1,Math.min(request.remainingMillis(),budget.remainingMillis()))); }
                catch(SQLException | RuntimeException failure){
                    // 设置清理时限失败后不能退回旧超时执行close；丢弃物理连接。
                    quarantine(failure);throw new ConsistencyBudget.Failure("cleanup_failed");
                }
                try { method.invoke(delegate,args); }
                catch(InvocationTargetException failure){quarantine(failure.getCause());throw failure.getCause();}
                budget.check();
                if(request.expired())throw new ConsistencyBudget.Failure("budget_exhausted");
                return null;
            } else constrain(raw,budget,request);
            if(name.equals("setNetworkTimeout")) {
                int requested=(Integer)args[1];
                args[1]=(int)Math.min(Math.max(1,requested),Math.min(request.remainingMillis(),budget.requestMillis()));
                if((Integer)args[1]<1)throw new ConsistencyBudget.Failure("budget_exhausted");
            }
            Object result;
            try { result=method.invoke(delegate,args); }
            catch(InvocationTargetException e){throw e.getCause();}
            // close也在成功判断前记入预算；原方法异常则原失败保留。
            try {
                budget.check();
                if(request.expired())throw new ConsistencyBudget.Failure("budget_exhausted");
            } catch(RuntimeException failure) {
                // prepare/execute刚返回的Statement/ResultSet尚未进入调用方的资源作用域，
                // 不能直接丢掉引用；abort整条物理会话一并回收这些新资源。
                quarantine(failure);throw failure;
            }
            if(result instanceof java.sql.PreparedStatement statement)
                return wrap(statement,java.sql.PreparedStatement.class);
            if(result instanceof java.sql.Statement statement)
                return wrap(statement,java.sql.Statement.class);
            if(result instanceof java.sql.ResultSet rows)
                return wrap(rows,java.sql.ResultSet.class);
            if(result instanceof java.sql.DatabaseMetaData metadata)
                return wrap(metadata,java.sql.DatabaseMetaData.class);
            return result;
          });
        }
        void quarantine(Throwable original) {
            if(quarantined)return;quarantined=true;
            var pooled=raw instanceof com.alibaba.druid.pool.DruidPooledConnection p?p:null;
            var holder=pooled==null?null:pooled.getConnectionHolder();
            Connection physical=holder==null?raw:holder.getConnection();
            if(physical instanceof com.alibaba.druid.proxy.jdbc.ConnectionProxy proxy)physical=proxy.getRawObject();
            try { physical.abort(Runnable::run); }
            catch(SQLException|RuntimeException failure){original.addSuppressed(failure);}
            if(pooled!=null) {
                try { if(holder!=null&&holder.getDataSource() instanceof DruidDataSource source)source.discardConnection(holder); }
                catch(RuntimeException failure){original.addSuppressed(failure);}
                finally { pooled.disable(new SQLException("巡检连接已隔离",original)); }
            }
        }
    }
    @Override public void close() { pool.close(); }
}
