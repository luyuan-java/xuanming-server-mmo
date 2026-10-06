package com.game.data.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 整区维护前快照的备注（data-ops-spec §3.4「note 末尾带 job:&lt;id&gt;」）：作业号是从一份 PRE_MAINTENANCE 快照找回作业的唯一线索，
 * 备注再长也不能把它挤掉。落库的那一半见 {@code ZoneSnapshotSqlTest}。
 */
class ZoneSnapshotNoteTest {

    private static final String JOB = "100827479530401792";

    @Test
    void 没给备注_只有作业号_短备注原样加作业号() {
        assertThat(ZoneSnapshotService.noteWithJob("", JOB)).isEqualTo("job:" + JOB);
        assertThat(ZoneSnapshotService.noteWithJob(null, JOB)).isEqualTo("job:" + JOB);
        assertThat(ZoneSnapshotService.noteWithJob("维护窗口", JOB)).isEqualTo("维护窗口 job:" + JOB);
    }

    @Test
    void 合起来刚好到列宽_不截_超出一个字符起只截备注_总长恰好是列宽_作业号完整() {
        String tag = " job:" + JOB;
        int room = ZoneSnapshotService.NOTE_MAX - tag.length();

        String fits = "x".repeat(room);
        assertThat(ZoneSnapshotService.noteWithJob(fits, JOB)).isEqualTo(fits + tag).hasSize(ZoneSnapshotService.NOTE_MAX);

        String oneMore = "x".repeat(room) + "y";
        assertThat(ZoneSnapshotService.noteWithJob(oneMore, JOB)).isEqualTo(fits + tag).hasSize(ZoneSnapshotService.NOTE_MAX);

        // 受理允许的最长备注（256 字符）
        String longest = "备".repeat(256);
        assertThat(ZoneSnapshotService.noteWithJob(longest, JOB)).isEqualTo("备".repeat(room) + tag)
                .hasSize(ZoneSnapshotService.NOTE_MAX);
    }

    @Test
    void 截断点落在代理对中间_整个字符一起去掉_不留半个() {
        String tag = " job:" + JOB;
        int room = ZoneSnapshotService.NOTE_MAX - tag.length();
        String emoji = "😀"; // 一个字符、两个 UTF-16 码元
        // 备注 = (room - 1) 个 x + 一个表情 + 尾巴：第 room 个码元正好是表情的前半个
        String note = "x".repeat(room - 1) + emoji + "尾巴";

        String out = ZoneSnapshotService.noteWithJob(note, JOB);

        assertThat(out).isEqualTo("x".repeat(room - 1) + tag);
        assertThat(out.length()).isLessThanOrEqualTo(ZoneSnapshotService.NOTE_MAX);
        assertThat(out.chars().noneMatch(c -> Character.isSurrogate((char) c))).isTrue();
    }

    @Test
    void 作业号本身就占满列宽时_只留作业号() {
        String hugeJob = "9".repeat(ZoneSnapshotService.NOTE_MAX);
        assertThat(ZoneSnapshotService.noteWithJob("备注", hugeJob)).isEqualTo("job:" + hugeJob);
    }
}
