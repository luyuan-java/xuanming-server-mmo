package com.game.guild.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.id.Snowflake;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** guild_id 发号（guild-spec §1.12、§7.9，D8；基线 guild_id_mint_test.go:24）：租约无效、时钟回拨都拒绝，绝不发 0 或自造 id。 */
class GuildIdsTest {

    @Test
    void 租约有效时发出非0且不重复的号_worker取租约号() {
        GuildIds ids = new GuildIds(new Snowflake(7), () -> true);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            long id = ids.nextId();
            assertThat(id).isNotZero();
            assertThat(Snowflake.workerOf(id)).isEqualTo(7);
            assertThat(seen.add(id)).isTrue();
        }
    }

    @Test
    void 租约无效即拒绝_恢复后自动恢复() {
        AtomicBoolean valid = new AtomicBoolean(false);
        GuildIds ids = new GuildIds(new Snowflake(7), valid::get);
        assertThatThrownBy(ids::nextId).isInstanceOf(IllegalStateException.class).hasMessageContaining("租约");
        valid.set(true);
        assertThat(ids.nextId()).isNotZero();
    }

    @Test
    void 时钟回拨超过容忍值即拒绝() {
        AtomicLong clock = new AtomicLong(Snowflake.DEFAULT_EPOCH_MS + 10_000_000L);
        GuildIds ids = new GuildIds(new Snowflake(7, Snowflake.DEFAULT_EPOCH_MS, clock::get), () -> true);
        assertThat(ids.nextId()).isNotZero();
        clock.addAndGet(-60_000L);
        assertThatThrownBy(ids::nextId).isInstanceOf(RuntimeException.class);
    }
}
