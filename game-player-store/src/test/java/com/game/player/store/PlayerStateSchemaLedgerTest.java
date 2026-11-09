package com.game.player.store;

import com.game.contract.check.persist.PersistentSchemaLedger;
import com.game.contract.check.persist.PersistentSchemaSnapshot;
import com.game.player.store.state.PlayerState;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 正常 Maven test/verify 的持久字段门禁；不写台账，不在缺少原件时自动重新建立基线。 */
class PlayerStateSchemaLedgerTest {
    // 第一代来自技能扩展前的真实生成类；第二代只追加持久技能字段，历史原件不得覆盖。
    private static final String GENESIS_SHA256 = "3cd2f18d61634eaabe7d5805df027877451816242bb41ff5aecb704177d7877d";
    private static final String HEAD_SHA256 = "5c13dc94d4269abc54d2f5c1bdcd0fd7ae5569e5689e3d855b237708bfbb522d";

    // 仅登记已经存在的领域 restore→修改→toState 未知字段往返证据；protobuf 自己的 parse/serialize 不能替代它。
    private static final Set<String> ALLOW_ADDITIONS = Set.of(
            "game.storage.PlayerState", // ScenePlayer 持有根未知字段，技能修习后存盘的领域回归保护。
            "game.storage.AttributeState", // AttributeFeatureTest：加载规整后未知字段仍在。
            "game.storage.BattleLedgerState", "game.storage.BattleLedgerEntry", // BattleLedgerTest / BattleLedgerEntryFieldsTest。
            "game.storage.AssetOpLedgerState", "game.storage.AssetOpStreamLedgerState",
            "game.storage.AssetOpRejectionState"); // AssetOpLedgerTest：各层记账前后保留。
    // 当前根闭包没有 enum；未来 enum 新值须另有原始数值往返证据，不能沿用 unknown fields 证明。

    @Test void playerStateDescriptorMatchesRegisteredPersistentHistory() throws Exception {
        var current = PersistentSchemaSnapshot.capture(PlayerState.getDescriptor());
        int generation = verifyRegisteredSchema(Path.of("src/test/resources/persistent-schema"),
                GENESIS_SHA256, HEAD_SHA256, current, ALLOW_ADDITIONS);
        assertThat(generation).isEqualTo(2);
    }

    /** 已合法退役为不可达的类型仍须保留历史解冻政策，不能只用当前闭包判定策略名有效。 */
    static int verifyRegisteredSchema(Path directory, String genesisSha256, String headSha256,
                                      PersistentSchemaSnapshot current, Set<String> allowAdditions) throws Exception {
        var history = PersistentSchemaLedger.read(directory, genesisSha256, headSha256);
        var knownTypes = new TreeSet<>(current.messages().keySet());
        knownTypes.addAll(current.enums().keySet());
        for (var entry : history) {
            knownTypes.addAll(entry.snapshot().messages().keySet());
            knownTypes.addAll(entry.snapshot().enums().keySet());
        }
        assertThat(knownTypes).as("允许新增策略必须对应历史或当前闭包中的 message/enum 类型").containsAll(allowAdditions);
        return PersistentSchemaLedger.verify(directory, genesisSha256, headSha256, current, allowAdditions);
    }
}
