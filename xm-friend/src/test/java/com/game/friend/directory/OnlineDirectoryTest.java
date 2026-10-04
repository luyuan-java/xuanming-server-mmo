package com.game.friend.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.proto.PlayerPresence;
import com.game.common.player.PlayerProfiles.Profile;
import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.proto.friend.RecommendEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class OnlineDirectoryTest {

    private static final String PREFIX = "xm:presence:";

    /** SCAN 游标 → 一批；在线条目与玩家行。 */
    private final Map<Long, OnlineDirectory.ScanPage> pages = new HashMap<>();
    private final Map<String, byte[]> presence = new HashMap<>();
    private final Map<Long, Profile> rows = new HashMap<>();
    private final List<Long> scanned = new ArrayList<>();
    private boolean failProfiles;

    private OnlineDirectory directory() {
        return new OnlineDirectory(new OnlineDirectory.DirectoryRedis() {
            @Override
            public CompletionStage<OnlineDirectory.ScanPage> scan(long cursor) {
                scanned.add(cursor);
                return CompletableFuture.completedFuture(pages.getOrDefault(cursor, new OnlineDirectory.ScanPage(0, List.of())));
            }

            @Override
            public CompletionStage<Map<String, byte[]>> mget(List<String> keys) {
                Map<String, byte[]> out = new HashMap<>();
                keys.forEach(k -> out.put(k, presence.get(k)));
                return CompletableFuture.completedFuture(out);
            }
        }, PREFIX, (ids, deadline) -> {
            if (failProfiles) {
                throw new IllegalStateException("mysql down");
            }
            Map<Long, Profile> out = new HashMap<>();
            ids.forEach(id -> {
                if (rows.containsKey(id)) {
                    out.put(id, rows.get(id));
                }
            });
            return out;
        });
    }

    private void online(long id, String name, int classId, int zone) {
        presence.put(PREFIX + id, PlayerPresence.newBuilder().setPlayerId(id).setOnlineSinceMs(1000 + id).build().toByteArray());
        rows.put(id, new Profile(id, name, 0, classId, 1, "ap" + id, zone));
    }

    private static List<String> keys(long... ids) {
        List<String> out = new ArrayList<>();
        for (long id : ids) {
            out.add(PREFIX + id);
        }
        return out;
    }

    private static List<Long> ids(OnlineDirectory.Page page) {
        return page.candidates().stream().map(RecommendEntry::getCandidatePlayerId).toList();
    }

    @Test
    void 游标解析_只认ASCII十进制_三段_长度与offset上限() {
        assertThat(OnlineDirectory.parseCursor("")).isEqualTo(new OnlineDirectory.Cursor(0, 0));
        assertThat(OnlineDirectory.parseCursor("v1:0:0")).isEqualTo(new OnlineDirectory.Cursor(0, 0));
        assertThat(OnlineDirectory.parseCursor("v1:18446744073709551615:1024"))
                .isEqualTo(new OnlineDirectory.Cursor(-1L, 1024));
        assertThat(OnlineDirectory.parseCursor("v1:007:3")).isEqualTo(new OnlineDirectory.Cursor(7, 3));
        for (String bad : List.of("bad", "v1:-1:0", "v1:+1:0", "v1:18446744073709551616:0", "v1:1:65535", "v1:1:1025",
                "v1:1:2:", "v2:1:2", "v1::2", "v1:１:2", "v1:1", "v1:" + "1".repeat(46))) {
            assertThat(OnlineDirectory.parseCursor(bad)).as(bad).isNull();
        }
        assertThat(OnlineDirectory.encodeCursor(0, 0)).isEmpty();
        assertThat(OnlineDirectory.encodeCursor(0, 5)).isEqualTo("v1:0:5");
        assertThat(OnlineDirectory.encodeCursor(-1L, 0)).isEqualTo("v1:18446744073709551615:0");
        assertThat(OnlineDirectory.validInput("", "x".repeat(64))).isTrue();
        assertThat(OnlineDirectory.validInput("", "😀".repeat(64))).isTrue(); // 按码点计
        assertThat(OnlineDirectory.validInput("", "x".repeat(65))).isFalse();
        assertThat(OnlineDirectory.validInput("bad", "")).isFalse();
    }

    @Test
    void query归一化按Go的TrimSpace与逐码点小写() {
        assertThat(OnlineDirectory.normalizeQuery("　 AbC \t")).isEqualTo("abc");
        assertThat(OnlineDirectory.normalizeQuery("​ x")).isEqualTo("​ x"); // 零宽空格不是 Go 的空白
        assertThat(OnlineDirectory.goLower("İ")).isEqualTo("i"); // 简单映射（完整映射会多出一个组合点）
    }

    @Test
    void 过滤_自己与exclude_名字空_职业0_跨区_坏条目_按名字或编号匹配() {
        pages.put(0L, new OnlineDirectory.ScanPage(0, keys(1, 2, 3, 4, 5, 6, 7, 8)));
        online(1, "caller", 1, 9);  // 自己
        online(2, "  Alice  ", 2, 9);
        online(3, "", 2, 9);        // 名字空
        online(4, "bob", 0, 9);     // 职业 0
        online(5, "carol", 2, 8);   // 别的区
        online(6, "dave", 2, 9);    // exclude
        presence.put(PREFIX + 7, new byte[] {(byte) 0xFF}); // 坏条目
        rows.put(7L, new Profile(7, "eve", 0, 2, 1, "", 9));
        online(8, "frank", 2, 9);
        OnlineDirectory.Page page = directory().list(1, 9, "", 10, List.of(6L), "", Deadline.after(1000));
        assertThat(ids(page)).containsExactly(2L, 8L);
        assertThat(page.nextCursor()).isEmpty();
        RecommendEntry alice = page.candidates().get(0);
        assertThat(alice.getName()).isEqualTo("Alice");
        assertThat(alice.getIsOnline()).isTrue();
        assertThat(alice.getLastActiveMs()).isEqualTo(1002);
        assertThat(alice.getZoneId()).isEqualTo(9);
        assertThat(alice.getLevel()).isZero(); // 等级可为 0，原样返回

        assertThat(ids(directory().list(1, 9, "", 10, List.of(), "ALI", Deadline.after(1000)))).containsExactly(2L);
        assertThat(ids(directory().list(1, 9, "", 10, List.of(), "8", Deadline.after(1000)))).containsExactly(8L);
    }

    @Test
    void 分页_凑满时本批有剩余回同一游标与下一个下标_没剩余回下一批() {
        pages.put(0L, new OnlineDirectory.ScanPage(50, keys(11, 12, 13)));
        pages.put(50L, new OnlineDirectory.ScanPage(0, keys(14)));
        for (long id = 11; id <= 14; id++) {
            online(id, "p" + id, 1, 3);
        }
        OnlineDirectory.Page first = directory().list(99, 3, "", 2, List.of(), "", Deadline.after(1000));
        assertThat(ids(first)).containsExactly(11L, 12L);
        assertThat(first.nextCursor()).isEqualTo("v1:0:2");
        OnlineDirectory.Page second = directory().list(99, 3, first.nextCursor(), 2, List.of(), "", Deadline.after(1000));
        assertThat(ids(second)).containsExactly(13L, 14L);
        assertThat(second.nextCursor()).isEmpty();

        OnlineDirectory.Page exact = directory().list(99, 3, "", 3, List.of(), "", Deadline.after(1000));
        assertThat(ids(exact)).containsExactly(11L, 12L, 13L);
        assertThat(exact.nextCursor()).isEqualTo("v1:50:0");
    }

    @Test
    void 键按字典序发放_offset越过变短的批次整批跳过() {
        pages.put(0L, new OnlineDirectory.ScanPage(0, keys(9, 10)));
        online(9, "nine", 1, 1);
        online(10, "ten", 1, 1);
        assertThat(ids(directory().list(99, 1, "", 5, List.of(), "", Deadline.after(1000)))).containsExactly(10L, 9L);
        assertThat(ids(directory().list(99, 1, "v1:0:7", 5, List.of(), "", Deadline.after(1000)))).isEmpty();
    }

    @Test
    void 四轮用完没凑满_回下一批游标且本页可以为空() {
        for (long c = 0; c < 4; c++) {
            pages.put(c, new OnlineDirectory.ScanPage(c + 1, keys(100 + c))); // 都不在线
        }
        OnlineDirectory.Page page = directory().list(99, 1, "", 5, List.of(), "", Deadline.after(1000));
        assertThat(page.candidates()).isEmpty();
        assertThat(page.nextCursor()).isEqualTo("v1:4:0");
        assertThat(scanned).containsExactly(0L, 1L, 2L, 3L);
    }

    @Test
    void 依赖故障与批次超限() {
        pages.put(0L, new OnlineDirectory.ScanPage(0, keys(2)));
        online(2, "x", 1, 1);
        failProfiles = true;
        assertThatThrownBy(() -> directory().list(99, 1, "", 5, List.of(), "", Deadline.after(1000)))
                .isInstanceOf(DependencyException.class);
        List<String> huge = new ArrayList<>();
        for (int i = 0; i < 1025; i++) {
            huge.add(PREFIX + (1000 + i));
        }
        pages.put(0L, new OnlineDirectory.ScanPage(0, huge));
        assertThatThrownBy(() -> directory().list(99, 1, "", 5, List.of(), "", Deadline.after(1000)))
                .isInstanceOf(DependencyException.class).hasMessageContaining("上限");
    }
}
