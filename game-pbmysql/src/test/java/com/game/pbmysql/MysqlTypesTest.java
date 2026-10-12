package com.game.pbmysql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 线上列类型兼容性判定，用例逐条取自 Go 版 proto2mysql_test.go / schema_sync_test.go / audit_fixes_test.go。 */
class MysqlTypesTest {

    @ParameterizedTest(name = "isTypeMatch({0}, {1}) = {2}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            // DATETIME 小数秒精度（TestDatetimePrecisionMigration）
            "datetime        | DATETIME(6)                        | false",
            "datetime(0)     | DATETIME(6)                        | false",
            "datetime(3)     | DATETIME(6)                        | false",
            "datetime(6)     | DATETIME(6)                        | true",
            "timestamp(6)    | DATETIME(6)                        | true",
            "datetime(6)     | DATETIME                           | true",
            // 只拓宽、不收窄（TestSchemaSyncNeverNarrows）
            "varchar(64)     | varchar(32)                        | true",
            "varchar(32)     | varchar(64)                        | false",
            "double          | float                              | true",
            "float           | double                             | false",
            "bigint unsigned | int unsigned NOT NULL DEFAULT 0    | true",
            "int unsigned    | bigint unsigned NOT NULL DEFAULT 0 | false",
            "bigint          | tinyint NOT NULL DEFAULT 0         | true",
            "tinyint         | bigint NOT NULL DEFAULT 0          | false",
            "bigint unsigned | int NOT NULL DEFAULT 0             | false",
            "bigint          | int unsigned NOT NULL DEFAULT 0    | false",
            "mediumtext      | varchar(255)                       | true",
            "varchar(255)    | MEDIUMTEXT                         | false",
            "longtext        | MEDIUMTEXT                         | true",
            "text            | MEDIUMTEXT                         | false",
            "mediumblob      | varbinary(255)                     | true",
            "varbinary(255)  | MEDIUMBLOB                         | false",
            "int             | MEDIUMTEXT                         | false",
            "mediumtext      | bigint NOT NULL DEFAULT 0          | false",
            // MySQL 5.7 的显示宽度不算差异；tinyint(1) 即 bool
            "int(11) unsigned| int unsigned NOT NULL DEFAULT 0    | true",
            "tinyint(1)      | tinyint(1) NOT NULL DEFAULT 0      | true",
            "varchar(191)    | VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '' | true",
            "varchar(100)    | VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '' | false",
    })
    void 类型兼容判定(String current, String target, boolean want) {
        assertThat(MysqlTypes.isTypeMatch(current, target)).isEqualTo(want);
    }

    @ParameterizedTest(name = "narrowingSuppressed({0}, {1}) = {2}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "bigint unsigned | int unsigned NOT NULL DEFAULT 0    | true",
            "mediumtext      | varchar(255)                       | true",
            "varchar(64)     | varchar(32)                        | true",
            "double          | float NOT NULL DEFAULT 0           | true",
            "datetime(6)     | DATETIME(3)                        | true",
            "int unsigned    | int unsigned NOT NULL DEFAULT 0    | false",
            "int unsigned    | bigint unsigned NOT NULL DEFAULT 0 | false",
            "mediumtext      | MEDIUMTEXT                         | false",
            "bigint unsigned | int NOT NULL DEFAULT 0             | false",
    })
    void 挡下收窄的判定(String current, String target, boolean want) {
        assertThat(MysqlTypes.narrowingSuppressed(current, target)).isEqualTo(want);
    }

    @ParameterizedTest(name = "alignedColumnType({0}, {1})")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "bigint          | int NOT NULL DEFAULT 0             | bigint NOT NULL DEFAULT 0",
            "bigint unsigned | int unsigned NOT NULL DEFAULT 0    | bigint unsigned NOT NULL DEFAULT 0",
            "varchar(100)    | varchar(50) NOT NULL               | varchar(100) NOT NULL",
            "int             | bigint NOT NULL DEFAULT 0          | bigint NOT NULL DEFAULT 0",
            "int             | int unsigned NOT NULL DEFAULT 0    | int NOT NULL DEFAULT 0",
            "varchar(255)    | VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '' | varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT ''",
            "varbinary(255)  | VARBINARY(191) NOT NULL DEFAULT ''  | varbinary(255) NOT NULL DEFAULT ''",
            "varbinary(64)   | VARBINARY(191) NOT NULL DEFAULT ''  | VARBINARY(191) NOT NULL DEFAULT ''",
    })
    void 对齐语句换类型本体留目标属性(String current, String target, String want) {
        assertThat(MysqlTypes.alignedColumnType(current, target)).isEqualTo(want);
    }

    @ParameterizedTest(name = "isRenameConvertible({0}, {1}) = {2}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "bigint     | int NOT NULL DEFAULT 0    | true",
            "varchar(5) | MEDIUMTEXT                | true",
            "mediumtext | bigint NOT NULL DEFAULT 0 | false",
            "mediumblob | MEDIUMTEXT                | false",
    })
    void 改名能否承接新类型(String current, String target, boolean want) {
        assertThat(MysqlTypes.isRenameConvertible(current, target)).isEqualTo(want);
    }
}
