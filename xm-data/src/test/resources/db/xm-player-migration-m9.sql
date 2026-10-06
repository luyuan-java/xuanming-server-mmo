-- docs/design/db-migrations.md M9 的迁移语句（与文档逐字相同；XmDataMysqlIntegrationTest 用它把 M9 之前的库迁到新结构再与新建库比对）。
ALTER TABLE player ADD KEY idx_player_zone (zone_id);
