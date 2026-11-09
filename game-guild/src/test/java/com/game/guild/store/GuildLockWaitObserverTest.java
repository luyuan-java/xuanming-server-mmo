package com.game.guild.store;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GuildLockWaitObserverTest {
    private static final class Probe {
        final Connection connection = mock(Connection.class);
        final PreparedStatement query = mock(PreparedStatement.class);
        final ResultSet rows = mock(ResultSet.class);
        final AtomicLong nanos = new AtomicLong();
        final List<Integer> networkMillis = new ArrayList<>();
        final GuildLockWaitObserver observer;
        Probe() throws Exception {
            when(connection.getNetworkTimeout()).thenReturn(5000);
            when(connection.prepareStatement(anyString())).thenReturn(query);
            doAnswer(call -> { networkMillis.add(call.getArgument(1)); return null; })
                    .when(connection).setNetworkTimeout(any(), anyInt());
            when(query.executeQuery()).thenReturn(rows);
            when(rows.next()).thenReturn(true, false);
            when(rows.getLong(1)).thenReturn(1L);
            observer = new GuildLockWaitObserver(connection, "fixture_only", nanos::get, nanos::addAndGet);
        }
    }

    @Test void observedWaitUsesPreparedDiagnosticConnectionAndRestoresItsNetworkBound() throws Exception {
        Probe probe = new Probe();
        try (var observer = probe.observer) {
            assertThat(observer.await(1, 300)).isTrue();
            verify(probe.query).setString(1, "fixture_only");
            verify(probe.query).setQueryTimeout(1);
            assertThat(probe.networkMillis.getLast()).isEqualTo(5000);
            assertThat(probe.networkMillis.subList(0, probe.networkMillis.size() - 1)).allMatch(value -> value <= 300);
            verify(probe.connection, never()).close();
        }
        verify(probe.connection).close();
    }

    @Test void prepareTimeConsumesSameBudgetAndTimedOutConnectionIsNeverReused() throws Exception {
        Probe probe = new Probe();
        when(probe.connection.prepareStatement(anyString())).thenAnswer(call -> {
            probe.nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(250)); return probe.query;
        });
        when(probe.query.executeQuery()).thenAnswer(call -> {
            assertThat(probe.networkMillis.getLast()).isEqualTo(50);
            probe.nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
            throw new SQLTimeoutException("diagnostic timeout");
        });
        assertThat(probe.observer.await(1, 300)).isFalse();
        assertThat(probe.observer.await(1, 300)).isFalse();
        assertThat(probe.nanos.get()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(600));
        verify(probe.connection).prepareStatement(anyString());
        verify(probe.query).executeQuery();
        verify(probe.connection).close();
        probe.observer.close();
        verify(probe.connection).close();
    }

    @Test void exhaustedPreparationCannotIssueDiagnosticQuery() throws Exception {
        Probe probe = new Probe();
        when(probe.connection.prepareStatement(anyString())).thenAnswer(call -> {
            probe.nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(301)); return probe.query;
        });
        try (var observer = probe.observer) {
            assertThat(observer.await(1, 300)).isFalse();
            verify(probe.query, never()).executeQuery();
            assertThat(probe.networkMillis.getLast()).isEqualTo(5000);
        }
    }

    @Test void unsupportedDiagnosticRemainsUnknownAndOnlyUsesRemainingDelay() throws Exception {
        Probe probe = new Probe();
        when(probe.query.executeQuery()).thenThrow(new SQLException("unsupported metadata", "42S02"));
        assertThat(probe.observer.await(1, 300)).isFalse();
        assertThat(probe.observer.await(1, 100)).isFalse();
        assertThat(probe.nanos.get()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(400));
        verify(probe.query).executeQuery();
        verify(probe.connection).close();
    }

    @Test void failedNetworkRestorationDiscardsConnectionAndCannotReportSuccess() throws Exception {
        Probe probe = new Probe();
        doThrow(new SQLException("restore failed")).when(probe.connection).setNetworkTimeout(any(), eq(5000));
        assertThatThrownBy(() -> probe.observer.await(1, 300)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("期限恢复未确认");
        assertThat(probe.observer.await(1, 100)).isFalse();
        verify(probe.connection).close();
        verify(probe.query).executeQuery();
    }
}
