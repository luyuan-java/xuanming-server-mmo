package com.game.common.sql.testing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 直接调用生产的私有授权方法；JDBC 替身只证明次数与失败门禁，不冒充真实权限。 */
class AuthoritySqlFixtureGrantCacheTest {
    @Test void incrementalTablesAreGrantedOnceAcrossCreateAndLaterScopeRefreshes()throws Exception{
        var probe=new Probe();
        probe.tables.add("__sql_fixture_owner");
        for(int i=0;i<16;i++){
            probe.tables.add("business_"+i);probe.refresh();
        }
        probe.tables.add("route_meta");probe.tables.add("writer_registry");
        probe.refresh();probe.refresh();
        assertEquals(16,probe.grants.size());assertEquals(16,new HashSet<>(probe.grants).size());
        assertEquals(16,probe.confirmed().size());
        for(int i=0;i<16;i++)assertTrue(probe.grants.contains("GRANT SELECT,INSERT,UPDATE,DELETE ON `grant_cache`.`business_"+i+"` TO 'ws_probe'@'%'"));
        assertFalse((boolean)field(probe.fixture,"unknown"));
        verify(probe.admin,never()).setNetworkTimeout(any(),anyInt());
        verify(probe.statement,never()).setQueryTimeout(anyInt());
    }

    @Test void lostGrantResponseNeverEntersCacheAndPermanentlyRevokesTheLease()throws Exception{
        var probe=new Probe();probe.tables.add("first");probe.refresh();
        probe.tables.add("unconfirmed");probe.failure=new SQLNonTransientConnectionException("synthetic lost GRANT response","08S01");
        assertSame(probe.failure,assertThrows(SQLException.class,probe::refresh));
        assertEquals(Set.of("first"),probe.confirmed());assertTrue((boolean)field(probe.fixture,"unknown"));
        assertEquals("FAILED_UNKNOWN",field(probe.lease,"phase").toString());
        int attempts=probe.grants.size();probe.failure=null;
        assertThrows(SQLException.class,probe::refresh);
        assertThrows(SQLException.class,()->probe.lease.withSchema((c,p,d)->{fail("A revoked lease invoked its callback");return null;}));
        assertThrows(SQLException.class,probe.lease::publish);assertThrows(SQLException.class,probe.lease::close);
        assertEquals(attempts,probe.grants.size());assertEquals(Set.of("first"),probe.confirmed());
    }

    private static final class Probe {
        final AuthoritySqlFixture fixture=mock(AuthoritySqlFixture.class);
        final AuthoritySqlFixture.UnpublishedSchemaLease lease;
        final Connection admin=mock(Connection.class);
        final Statement statement=mock(Statement.class);
        final PreparedStatement catalog=mock(PreparedStatement.class);
        final List<String> tables=new ArrayList<>(),grants=new ArrayList<>();
        SQLException failure;
        Probe()throws Exception{
            set(fixture,"user","wf_probe");set(fixture,"database","grant_cache");set(fixture,"owned",true);
            var constructor=AuthoritySqlFixture.UnpublishedSchemaLease.class.getDeclaredConstructor(AuthoritySqlFixture.class);
            constructor.setAccessible(true);lease=constructor.newInstance(fixture);
            when(admin.createStatement()).thenReturn(statement);when(admin.prepareStatement(anyString())).thenReturn(catalog);
            when(catalog.executeQuery()).thenAnswer(call->{
                var rows=mock(ResultSet.class);var index=new AtomicInteger(-1);var snapshot=List.copyOf(tables);
                when(rows.next()).thenAnswer(ignored->index.incrementAndGet()<snapshot.size());
                when(rows.getString(1)).thenAnswer(ignored->snapshot.get(index.get()));return rows;
            });
            when(statement.execute(anyString())).thenAnswer(call->{grants.add(call.getArgument(0));if(failure!=null)throw failure;return false;});
        }
        void refresh()throws Exception{
            Method method=AuthoritySqlFixture.UnpublishedSchemaLease.class.getDeclaredMethod("grantExistingBusinessRows",Connection.class);method.setAccessible(true);
            try{method.invoke(lease,admin);}catch(InvocationTargetException wrapped){if(wrapped.getCause() instanceof Exception e)throw e;if(wrapped.getCause() instanceof Error e)throw e;throw new AssertionError(wrapped);}
        }
        @SuppressWarnings("unchecked") Set<String> confirmed()throws Exception{return Set.copyOf((Set<String>)field(lease,"grantedBusinessTables"));}
    }
    private static Object field(Object target,String name)throws Exception{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{var field=AuthoritySqlFixture.class.getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
}
