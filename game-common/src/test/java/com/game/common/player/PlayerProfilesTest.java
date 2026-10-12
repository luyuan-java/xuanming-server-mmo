package com.game.common.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 资料读取受请求预算约束：取连接最多等剩余时长、等完没预算就不发语句，语句带毫秒级 MAX_EXECUTION_TIME。连接是替身。 */
class PlayerProfilesTest {

    @Test
    void 取连接最多等剩余预算_语句带MAX_EXECUTION_TIME() throws Exception {
        List<Long> waits = new ArrayList<>();
        Connection c = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        List<String> sqls = new ArrayList<>();
        when(c.prepareStatement(anyString())).thenAnswer(inv -> {
            sqls.add(inv.getArgument(0));
            return ps;
        });
        when(ps.executeQuery()).thenReturn(rs);
        PlayerProfiles profiles = new PlayerProfiles(maxWait -> {
            waits.add(maxWait);
            return c;
        }, 3);
        assertThat(profiles.loadStrict(List.of(1L, 2L), Deadline.after(800))).isEmpty();
        assertThat(waits).singleElement().satisfies(w -> assertThat(w).isBetween(1L, 800L));
        assertThat(sqls).singleElement().satisfies(sql -> assertThat(sql).matches("SELECT /\\*\\+ MAX_EXECUTION_TIME\\(\\d+\\) \\*/ .*"));
        verify(ps).setQueryTimeout(1);
        verify(c).close();
    }

    @Test
    void 等连接用完了预算_不发语句_宽松读返回空_严格读抛依赖故障() throws Exception {
        Connection c = mock(Connection.class);
        PlayerProfiles profiles = new PlayerProfiles(maxWait -> {
            try {
                Thread.sleep(maxWait + 20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return c;
        }, 3);
        assertThat(profiles.load(List.of(1L), Deadline.after(30))).isEmpty();
        assertThatThrownBy(() -> profiles.loadStrict(List.of(1L), Deadline.after(30))).isInstanceOf(DependencyException.class);
        verify(c, never()).prepareStatement(anyString());
        assertThatThrownBy(() -> profiles.loadStrict(List.of(1L), Deadline.after(0))).isInstanceOf(DependencyException.class);
    }
}
