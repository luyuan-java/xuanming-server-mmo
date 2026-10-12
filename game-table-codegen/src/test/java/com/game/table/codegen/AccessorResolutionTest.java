package com.game.table.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.table.codegen.TableSchema.Field;
import com.game.table.codegen.TableSchema.Kind;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 访问器名以 protoc 实际生成的方法为准。方法名集合取自 protoc 4.35.1 对下列写法的真实输出：
 * {@code repeated uint32 monster = 5; uint32 monster_count = 6;} → {@code getMonster5List()} / {@code getMonsterCount6()}；
 * {@code uint32 class = 2;} → {@code getClass_()}；{@code uint32 serializedsize = 3;} → {@code getSerializedsize()}。
 */
class AccessorResolutionTest {

    @Test
    void plainFieldUsesProtocName() {
        Field f = new Field("skill_type", 2, true, Kind.INT, false);
        assertThat(ConfigTableProcessor.chooseAccessorBase(f, Set.of("getSkillTypeList", "getSkillTypeCount")))
                .isEqualTo("SkillType");
    }

    @Test
    void conflictingFieldsGetFieldNumberSuffix() {
        Set<String> methods = Set.of("getMonster5List", "getMonster5Count", "getMonsterCount6");
        Field monster = new Field("monster", 5, true, Kind.INT, false);
        Field monsterCount = new Field("monster_count", 6, false, Kind.INT, false);
        assertThat(ConfigTableProcessor.chooseAccessorBase(monster, methods)).isEqualTo("Monster5");
        assertThat(ConfigTableProcessor.chooseAccessorBase(monsterCount, methods)).isEqualTo("MonsterCount6");
    }

    @Test
    void forbiddenNamesFollowProtocExactCase() {
        Field clazz = new Field("class", 2, false, Kind.INT, false);
        assertThat(clazz.accessorBase()).isEqualTo("Class_");
        assertThat(ConfigTableProcessor.chooseAccessorBase(clazz, Set.of("getClass_"))).isEqualTo("Class_");
        // 只有大小写完全等于禁用词时 protoc 才加下划线
        Field run = new Field("serializedsize", 3, false, Kind.INT, false);
        assertThat(run.accessorBase()).isEqualTo("Serializedsize");
        assertThat(ConfigTableProcessor.chooseAccessorBase(run, Set.of("getSerializedsize"))).isEqualTo("Serializedsize");
    }

    @Test
    void enumAndMissingAccessor() {
        Field kind = new Field("kind", 4, false, Kind.INT, true);
        assertThat(ConfigTableProcessor.chooseAccessorBase(kind, Set.of("getKind", "getKindValue"))).isEqualTo("Kind");
        assertThat(kind.withAccessorBase("Kind").getter()).isEqualTo("getKindValue()");
        assertThat(ConfigTableProcessor.chooseAccessorBase(kind, Set.of("getOther"))).isNull();
    }
}
