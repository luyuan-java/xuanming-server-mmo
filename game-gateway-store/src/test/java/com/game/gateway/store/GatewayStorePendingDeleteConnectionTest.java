package com.game.gateway.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 独立验证争用前的 JDBC 预算收紧与失败关闭；不连接数据库。 */
class GatewayStorePendingDeleteConnectionTest {
    @Test void initializedFiveSecondConnectionIsTightenedAndVerifiedBeforeUse() throws Exception {
        Connection connection=mock(Connection.class);
        AtomicInteger timeout=new AtomicInteger(5000);
        doAnswer(call->{timeout.set(call.getArgument(1));return null;})
                .when(connection).setNetworkTimeout(any(Executor.class),anyInt());
        when(connection.getNetworkTimeout()).thenAnswer(call->timeout.get());
        assertSame(connection,GatewayStorePendingDeleteTiDbTest.tightenNormalConnection(connection));
        assertEquals(3000,timeout.get());
        verify(connection,never()).close();
    }

    @Test void unconfirmedTimeoutCannotEscapeAndItsConnectionIsClosed() throws Exception {
        Connection connection=mock(Connection.class);
        when(connection.getNetworkTimeout()).thenReturn(5000);
        SQLException failure=assertThrows(SQLException.class,
                ()->GatewayStorePendingDeleteTiDbTest.tightenNormalConnection(connection));
        assertEquals("08007",failure.getSQLState());
        verify(connection).close();
    }

    @Test void driverRejectionAndCloseFailureBothRemainVisible() throws Exception {
        Connection connection=mock(Connection.class);
        SQLException rejected=new SQLException("timeout unsupported","0A000");
        SQLException closeFailed=new SQLException("close unconfirmed","08007");
        doThrow(rejected).when(connection).setNetworkTimeout(any(Executor.class),eq(3000));
        doThrow(closeFailed).when(connection).close();
        assertSame(rejected,assertThrows(SQLException.class,
                ()->GatewayStorePendingDeleteTiDbTest.tightenNormalConnection(connection)));
        assertArrayEquals(new Throwable[]{closeFailed},rejected.getSuppressed());
    }
}
