package com.game.data.consistency;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidPooledConnection;
import com.game.common.GameNamespace;
import com.game.common.deadline.Deadline;
import java.sql.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcConsistencyReaderTest {
    @Test void copiedPublicUrlCannotInitializeDatabase(){
        assertThatThrownBy(()->new JdbcConsistencyReader("jdbc:mysql://127.0.0.1/nstest?createDatabaseIfNotExist=true","reader","unused",GameNamespace.of("nstest"),true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("隐式建库");
    }
    private static ConsistencyBudget budget(){return new ConsistencyBudget(Deadline.after(60_000),60_000);}
    private static <T extends Connection> T network(T connection)throws Exception{
        var timeout=new AtomicInteger(2_000);
        doAnswer(call->{timeout.set(call.getArgument(1));return null;}).when(connection).setNetworkTimeout(any(),anyInt());
        when(connection.getNetworkTimeout()).thenAnswer(call->timeout.get());return connection;
    }
    @Test void executeAfterBindingConsumedBudgetNeverReachesDriver()throws Exception{
        var raw=network(mock(Connection.class));var statement=mock(PreparedStatement.class);
        when(raw.prepareStatement("SELECT ?")).thenReturn(statement);
        var expired=new AtomicBoolean();var budget=mock(ConsistencyBudget.class);
        when(budget.requestMillis()).thenAnswer(call->{if(expired.get())throw new ConsistencyBudget.Failure("budget_exhausted");return 1_000;});
        when(budget.remainingMillis()).thenReturn(1_000L);
        doAnswer(call->{expired.set(true);return null;}).when(statement).setObject(1,1);
        var guarded=JdbcConsistencyReader.guarded(raw,budget,Deadline.after(2_000));
        var prepared=guarded.prepareStatement("SELECT ?");prepared.setObject(1,1);
        assertThatThrownBy(prepared::executeQuery).isInstanceOf(ConsistencyBudget.Failure.class).hasMessage("budget_exhausted");
        verify(statement,never()).executeQuery();
        guarded.close();verify(raw).close();
    }
    @Test void independentLastNextFailureDoesNotReturnPartialRows()throws Exception{
        var fixture=new Fixture("nstest","8.4.0");var statement=mock(PreparedStatement.class);var rows=mock(ResultSet.class);var metadata=mock(ResultSetMetaData.class);
        when(fixture.connection.prepareStatement("SELECT 1 AS id")).thenReturn(statement);when(statement.executeQuery()).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(metadata);when(metadata.getColumnCount()).thenReturn(1);when(metadata.getColumnLabel(1)).thenReturn("id");
        when(rows.next()).thenReturn(true).thenThrow(new SQLException("secret-dsn-and-account","08006"));when(rows.getObject(1)).thenReturn(1);
        assertThatThrownBy(()->fixture.reader(false).query("SELECT 1 AS id",List.of(),1,budget()))
                .isInstanceOf(ConsistencyBudget.Failure.class).hasMessage("query_failed");
        verify(fixture.connection).close();
    }
    @Test void wrongNamespaceFailsBeforePreparingBusinessRead()throws Exception{
        var fixture=new Fixture("other","8.4.0");
        assertThatThrownBy(()->fixture.reader(false).query("SELECT 1",List.of(),1,budget())).hasMessage("namespace_mismatch");
        verify(fixture.connection,never()).prepareStatement(anyString());
    }
    @Test void productionRejectsMysqlWithoutTiDbProof()throws Exception{
        var fixture=new Fixture("nstest","8.4.0");
        assertThatThrownBy(()->fixture.reader(true).query("SELECT 1",List.of(),1,budget())).hasMessage("strong_read_unproved");
        verify(fixture.connection,never()).prepareStatement(anyString());
    }
    @Test void tidbFollowerIsNotStrongRead()throws Exception{
        var fixture=new Fixture("nstest","8.0-TiDB-v8.5");
        var proof=mock(ResultSet.class);when(proof.next()).thenReturn(true);
        when(proof.getString(1)).thenReturn("");when(proof.getLong(2)).thenReturn(0L);when(proof.getBoolean(3)).thenReturn(false);
        when(proof.getString(4)).thenReturn("strict");when(proof.getString(5)).thenReturn("follower");
        when(fixture.statement.executeQuery(startsWith("SELECT @@SESSION"))).thenReturn(proof);
        assertThatThrownBy(()->fixture.reader(true).query("SELECT 1",List.of(),1,budget())).hasMessage("strong_read_unproved");
        verify(fixture.connection,never()).prepareStatement(anyString());
    }
    @Test void consumedBorrowBudgetCannotReachIdentityQuery()throws Exception{
        var fixture=new Fixture("nstest","8.4.0");var budget=mock(ConsistencyBudget.class);
        when(budget.requestMillis()).thenReturn(2_000).thenThrow(new ConsistencyBudget.Failure("budget_exhausted"));when(budget.remainingMillis()).thenReturn(0L);
        assertThatThrownBy(()->fixture.reader(false).query("SELECT 1",List.of(),1,budget)).hasMessage("budget_exhausted");
        verify(fixture.connection,never()).createStatement();verify(fixture.connection).close();
    }
    @Test void cleanupFailureCannotBeReturnedAsSuccess()throws Exception{
        var fixture=new Fixture("nstest","8.4.0");var statement=mock(PreparedStatement.class);var rows=mock(ResultSet.class);var metadata=mock(ResultSetMetaData.class);
        when(fixture.connection.prepareStatement("SELECT 1")).thenReturn(statement);when(statement.executeQuery()).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(metadata);when(rows.next()).thenReturn(false);
        doThrow(new SQLException("secret close failure","08006")).when(fixture.connection).close();
        assertThatThrownBy(()->fixture.reader(false).query("SELECT 1",List.of(),1,budget())).hasMessage("query_failed");
    }
    @Test void failedCleanupTimeoutSetupAbortsWithoutUnboundedClose()throws Exception{
        var raw=network(mock(Connection.class));var guarded=JdbcConsistencyReader.guarded(raw,budget(),Deadline.after(2_000));
        doThrow(new SQLException("closed")).when(raw).setNetworkTimeout(any(),anyInt());
        assertThatThrownBy(guarded::close).hasMessage("cleanup_failed");verify(raw).abort(any());verify(raw,never()).close();
    }
    @Test void prepareReturningAfterDeadlineAbortsUnclaimedResource()throws Exception{
        var raw=network(mock(Connection.class));var prepared=mock(PreparedStatement.class);
        var budget=mock(ConsistencyBudget.class);when(budget.requestMillis()).thenReturn(1_000);
        doThrow(new ConsistencyBudget.Failure("budget_exhausted")).when(budget).check();
        when(raw.prepareStatement("SELECT 1")).thenReturn(prepared);
        var guarded=JdbcConsistencyReader.guarded(raw,budget,Deadline.after(2_000));
        assertThatThrownBy(()->guarded.prepareStatement("SELECT 1")).hasMessage("budget_exhausted");
        verify(raw).abort(any());verify(prepared,never()).close();
        guarded.close();verify(raw,never()).close();
    }
    private static final class Fixture {
        final DruidDataSource pool=mock(DruidDataSource.class);final DruidPooledConnection connection;
        final Statement statement=mock(Statement.class);
        Fixture(String database,String version)throws Exception{
            connection=network(mock(DruidPooledConnection.class));when(pool.getConnection(anyLong())).thenReturn(connection);
            when(connection.getAutoCommit()).thenReturn(true);when(connection.isReadOnly()).thenReturn(true);
            var metadata=mock(DatabaseMetaData.class);when(connection.getMetaData()).thenReturn(metadata);when(metadata.getDatabaseProductName()).thenReturn("MySQL");
            when(connection.createStatement()).thenReturn(statement);
            var versions=mock(ResultSet.class);when(statement.executeQuery("SELECT VERSION()")).thenReturn(versions);when(versions.next()).thenReturn(true);when(versions.getString(1)).thenReturn(version);
            var databases=mock(ResultSet.class);when(statement.executeQuery("SELECT DATABASE()")).thenReturn(databases);when(databases.next()).thenReturn(true,false);when(databases.getString(1)).thenReturn(database);
        }
        JdbcConsistencyReader reader(boolean requireTiDb){return new JdbcConsistencyReader(pool,GameNamespace.of("nstest"),requireTiDb);}
    }
}
