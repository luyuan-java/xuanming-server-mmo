package com.game.pbmysql;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.pbmysql.testpb.TestTables;
import com.game.proto.friend.FriendEdgeRecord;
import com.game.proto.friend.FriendFriendTableOuterClass;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 与 Go 版逐字节一致的字符串工具，以及按字段号读选项的入口。 */
class MysqlSyntaxTest {

    @Test
    void FNV1a_32位标准向量() {
        assertThat(MysqlSyntax.fnv1a32(new byte[0])).isEqualTo(0x811c9dc5L);
        assertThat(MysqlSyntax.fnv1a32("a".getBytes(StandardCharsets.UTF_8))).isEqualTo(0xe40c292cL);
        assertThat(MysqlSyntax.fnv1a32("foobar".getBytes(StandardCharsets.UTF_8))).isEqualTo(0xbf9cf968L);
    }

    @Test
    void 标识符截断按码点切_指纹算整个名字的UTF8字节() {
        assertThat(MysqlSyntax.truncateIdentifier("x".repeat(64))).isEqualTo("x".repeat(64));
        // 指纹另用 PowerShell 独立实现的 FNV-1a 算出
        String chinese = "玩家".repeat(35);
        assertThat(MysqlSyntax.truncateIdentifier(chinese)).isEqualTo("玩家".repeat(27) + "玩" + "_61b1a5be");
        assertThat(MysqlSyntax.truncateIdentifier("idx_" + chinese + "_0"))
                .hasSize(64).endsWith("_141339c9").startsWith("idx_玩家");
    }

    @Test
    void 注释转义先双写反斜杠再倍写单引号_换行变空格() {
        assertThat(MysqlSyntax.escapeComment("evil\\")).isEqualTo("evil\\\\");
        assertThat(MysqlSyntax.escapeComment("it's")).isEqualTo("it''s");
        assertThat(MysqlSyntax.escapeComment("a\nb\rc")).isEqualTo("a b c");
        assertThat(MysqlSyntax.escapeComment("\\'")).isEqualTo("\\\\''");
        assertThat(MysqlSyntax.escapeName("we`ird.name")).isEqualTo("`we``ird.name`");
    }

    @Test
    void 拆分保留空分量_去空白按Go的unicode_IsSpace() {
        assertThat(MysqlSyntax.goSplit("", ',')).containsExactly("");
        assertThat(MysqlSyntax.goSplit("a,", ',')).containsExactly("a", "");
        assertThat(MysqlSyntax.goSplit("a,,b", ',')).containsExactly("a", "", "b");
        assertThat(MysqlSyntax.splitTrimmed(" a , ,b ")).containsExactly("a", "b");
        assertThat(MysqlSyntax.goTrimSpace(" 　 a \t\u0085")).isEqualTo("a");
        // U+001C 在 Java 的 isWhitespace 里算空白，Go 不算
        assertThat(MysqlSyntax.goTrimSpace("\u001ca")).isEqualTo("\u001ca");
    }

    @Test
    void 文件级db选项与表名读取() {
        assertThat(DescriptorOptions.fileHasDbOption(TestTables.getDescriptor())).isTrue();
        assertThat(DescriptorOptions.fileHasDbOption(FriendFriendTableOuterClass.getDescriptor())).isFalse();
        assertThat(DescriptorOptions.tableName(FriendEdgeRecord.getDescriptor())).contains("friend");
        assertThat(DescriptorOptions.tableName(com.game.pbmysql.testpb.Player.getDescriptor())).isEmpty();
    }
}
