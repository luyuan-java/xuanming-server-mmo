package com.game.table.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 与 protoc Java 生成器的命名逐字一致（生成代码直接调用 protoc 生成的 getter）。 */
class JavaNamesTest {

    @Test
    void underscoresToCamelCaseMatchesProtoc() {
        assertThat(JavaNames.underscoresToCamelCase("skill_type", true)).isEqualTo("SkillType");
        assertThat(JavaNames.underscoresToCamelCase("m_uint32_key", true)).isEqualTo("MUint32Key");
        assertThat(JavaNames.underscoresToCamelCase("to_uint32", true)).isEqualTo("ToUint32");
        // 数字后面的字母大写
        assertThat(JavaNames.underscoresToCamelCase("pos2d_x", true)).isEqualTo("Pos2DX");
        // 首字母大写的字段名，在不要求首字母大写时转小写
        assertThat(JavaNames.underscoresToCamelCase("Id", false)).isEqualTo("id");
        assertThat(JavaNames.underscoresToCamelCase("id", false)).isEqualTo("id");
        // 中间的大写字母原样保留
        assertThat(JavaNames.underscoresToCamelCase("cost_HP", true)).isEqualTo("CostHP");
    }

    @Test
    void forbiddenNamesGetTrailingUnderscore() {
        assertThat(JavaNames.capitalizedFieldName("class")).isEqualTo("Class_");
        assertThat(JavaNames.capitalizedFieldName("unknown_fields")).isEqualTo("UnknownFields_");
        assertThat(JavaNames.capitalizedFieldName("class_id")).isEqualTo("ClassId");
    }

    @Test
    void accessorNames() {
        assertThat(JavaNames.accessorName("TestMultiKey")).isEqualTo("testMultiKey");
        assertThat(JavaNames.accessorName("Class")).isEqualTo("classTable");
        assertThat(JavaNames.accessorName("Skill")).isEqualTo("skill");
    }
}
