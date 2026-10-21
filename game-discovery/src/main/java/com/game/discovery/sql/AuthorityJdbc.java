package com.game.discovery.sql;

import com.game.common.deadline.Deadline;
import java.math.BigInteger;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/** mail/rank共用有界JDBC接缝；只统一预算、uint64和事务，领域锁序/幂等仍归各仓储。 */
public final class AuthorityJdbc {
    @FunctionalInterface public interface Work<T>{T apply(Session session)throws SQLException;}
    @FunctionalInterface public interface Row<T>{T apply(ResultSet row)throws SQLException;}
    private final DataSource source;
    private boolean sessionBound;
    private long actorPlayer, actorRevision;
    private String actorAccount;
    public AuthorityJdbc(DataSource source){this.source=Objects.requireNonNull(source);}
    public AuthorityJdbc forActor(long player, String account, long revision) {
        var bound = new AuthorityJdbc(source); bound.sessionBound = true;
        bound.actorPlayer = player; bound.actorAccount = account; bound.actorRevision = revision; return bound;
    }
    public static Object u64(long bits){return bits>=0?bits:new BigInteger(Long.toUnsignedString(bits));}
    public static long u64(ResultSet row,int column)throws SQLException{Object value=row.getObject(column);if(value instanceof Number number)return number.longValue();throw new SQLException("uint64列不可解码");}
    public <T>T read(Deadline budget,Work<T> work)throws SQLException{return execute(budget,work,false,false);}
    public <T>T snapshot(Deadline budget,Work<T> work)throws SQLException{return execute(budget,work,true,true);}
    public <T>T tx(Deadline budget,Work<T> work)throws SQLException{return execute(budget,work,true,false);}
    private <T>T execute(Deadline budget,Work<T> work,boolean transaction,boolean snapshot)throws SQLException{
        for(int attempt=0;attempt<3;attempt++){
            check(budget);boolean committing=false;
            try(Connection connection=source.getConnection()){
                int oldIsolation=connection.getTransactionIsolation(),oldTimeout=connection.getNetworkTimeout();boolean oldAuto=connection.getAutoCommit();
                connection.setNetworkTimeout(Runnable::run,(int)Math.max(1,Math.min(5000,budget.remainingMillis())));
                try{
                    if(transaction){connection.setTransactionIsolation(snapshot?Connection.TRANSACTION_REPEATABLE_READ:Connection.TRANSACTION_READ_COMMITTED);connection.setAutoCommit(false);}
                    // 无actor后台同样先持全局metaS；维护Freeze持metaX排空，不能漏过奖励/邮件/排名写。
                    if(transaction && !snapshot) com.game.common.player.PlayerRouteAuthority.requireMeta(connection,
                        (int)Math.max(1,Math.min(3,(budget.remainingMillis()+999)/1000)),true);
                    if(transaction && sessionBound) com.game.common.player.PlayerSessionAuthority.lock(connection,
                        actorPlayer, actorAccount, actorRevision, (int)Math.max(1,Math.min(3,(budget.remainingMillis()+999)/1000)));
                    // TiDB8.5默认拒START TRANSACTION READ ONLY；snapshot仅执行SELECT，不启用全局noop。
                    T result=work.apply(new Session(connection,budget));check(budget);
                    if(transaction){committing=true;connection.commit();}check(budget);return result;
                }catch(SQLException|RuntimeException failure){if(transaction)try{connection.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;}
                finally{if(!connection.isClosed()){connection.setAutoCommit(oldAuto);connection.setTransactionIsolation(oldIsolation);connection.setNetworkTimeout(Runnable::run,oldTimeout);}}
            }catch(SQLException failure){
                // COMMIT响应未知不能自动重做；调用方必须按原业务ID复查。
                if(!transaction||committing||attempt==2||(failure.getErrorCode()!=1213&&failure.getErrorCode()!=1205&&failure.getErrorCode()!=9007))throw failure;
                check(budget);try{Thread.sleep((attempt+1)*10L);}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new SQLException("SQL事务重试被取消",cancelled);}
            }
        }
        throw new SQLException("SQL事务重试已耗尽");
    }
    public static void check(Deadline budget)throws SQLException{if(budget==null||budget.expired()||Thread.currentThread().isInterrupted())throw new SQLException("SQL请求已取消或预算到期");}
    public static final class Session{
        private final Connection connection;private final Deadline budget;
        Session(Connection connection,Deadline budget){this.connection=connection;this.budget=budget;}
        public Connection connection()throws SQLException{check(budget);return connection;}
        private PreparedStatement prepare(String sql,Object...args)throws SQLException{
            check(budget);PreparedStatement statement=connection.prepareStatement(sql);boolean ready=false;
            try{statement.setQueryTimeout((int)Math.max(1,Math.min(3,(budget.remainingMillis()+999)/1000)));for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);ready=true;return statement;}finally{if(!ready)statement.close();}
        }
        public int update(String sql,Object...args)throws SQLException{try(PreparedStatement statement=prepare(sql,args)){return statement.executeUpdate();}}
        public <T>List<T> list(String sql,Row<T> row,Object...args)throws SQLException{try(PreparedStatement statement=prepare(sql,args);ResultSet result=statement.executeQuery()){List<T> values=new ArrayList<>();while(result.next()){check(budget);values.add(row.apply(result));}return values;}}
        public <T>T one(String sql,Row<T> row,Object...args)throws SQLException{List<T> values=list(sql,row,args);if(values.size()>1)throw new SQLException("单行SQL返回多行");return values.isEmpty()?null:values.getFirst();}
        public long count(String sql,Object...args)throws SQLException{Long count=one(sql,row->row.getLong(1),args);if(count==null)throw new SQLException("SQL计数没有结果");return count;}
    }
}
