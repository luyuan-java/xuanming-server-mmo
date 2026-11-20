package com.game.scene.skill;

import static org.assertj.core.api.Assertions.assertThat;
import com.game.table.BuffTable;
import com.game.table.SkillTable;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupportCatalogDurationTest {
    @Test void 新版回合数优先于旧秒数且常驻不伪装有限持续() {
        var skill = SkillTable.newBuilder().setId(10040).addEffect(100400).build();
        var modern = BuffTable.newBuilder().setId(100400).setBattleOp(9).setDurationRounds(2).setDuration(50).build();
        assertThat(new ClassSkillCatalog(List.of(skill), List.of(modern)).durationRounds(skill)).isEqualTo(2);
        var permanent = modern.toBuilder().setInfiniteDuration(1).build();
        assertThat(new ClassSkillCatalog(List.of(skill), List.of(permanent)).durationRounds(skill)).isZero();
        var legacy = modern.toBuilder().clearBattleOp().clearDurationRounds().setDuration(5).build();
        assertThat(new ClassSkillCatalog(List.of(skill), List.of(legacy)).durationRounds(skill)).isEqualTo(1);
    }
}
