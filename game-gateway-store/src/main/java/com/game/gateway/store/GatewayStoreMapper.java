package com.game.gateway.store;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 区服目录 / 白名单 / 公告的 SQL。结果按记录的构造参数顺序映射，SELECT 的列序必须与记录字段一致。
 * 每条写都是按主键（或唯一键）的单行语句，只锁一条记录。
 */
@Mapper
public interface GatewayStoreMapper {

    String ZONE_COLUMNS = "zone_id, name, manual_status, capacity, maintenance_msg, open_time, recommended, sort_order,"
            + " created_at, updated_at";

    // ------------------------------------------------------------------ 区服目录

    @Select("SELECT " + ZONE_COLUMNS + " FROM zone_config ORDER BY sort_order, zone_id")
    List<ZoneRow> selectZones();

    @Select("SELECT " + ZONE_COLUMNS + " FROM zone_config WHERE zone_id = #{zoneId}")
    ZoneRow selectZone(@Param("zoneId") int zoneId);

    /** 创建或整行覆盖业务列（保留 created_at），同基线 create 的 upsert 语义。 */
    @Insert("INSERT INTO zone_config (" + ZONE_COLUMNS + ") VALUES (#{z.zoneId}, #{z.name}, #{z.manualStatus},"
            + " #{z.capacity}, #{z.maintenanceMsg}, #{z.openTime}, #{z.recommended}, #{z.sortOrder}, #{now}, #{now})"
            + " ON DUPLICATE KEY UPDATE name = VALUES(name), manual_status = VALUES(manual_status),"
            + " capacity = VALUES(capacity), maintenance_msg = VALUES(maintenance_msg), open_time = VALUES(open_time),"
            + " recommended = VALUES(recommended), sort_order = VALUES(sort_order), updated_at = VALUES(updated_at)")
    int upsertZone(@Param("z") ZoneRow zone, @Param("now") long nowMs);

    /** 只在不存在时插入（启动时按配置播种，已有的不动）。 */
    @Insert("INSERT IGNORE INTO zone_config (" + ZONE_COLUMNS + ") VALUES (#{z.zoneId}, #{z.name}, #{z.manualStatus},"
            + " #{z.capacity}, #{z.maintenanceMsg}, #{z.openTime}, #{z.recommended}, #{z.sortOrder}, #{now}, #{now})")
    int insertZoneIfAbsent(@Param("z") ZoneRow zone, @Param("now") long nowMs);

    @Update("UPDATE zone_config SET name = #{z.name}, manual_status = #{z.manualStatus}, capacity = #{z.capacity},"
            + " maintenance_msg = #{z.maintenanceMsg}, open_time = #{z.openTime}, recommended = #{z.recommended},"
            + " sort_order = #{z.sortOrder}, updated_at = #{now} WHERE zone_id = #{zoneId}")
    int updateZone(@Param("zoneId") int zoneId, @Param("z") ZoneRow values, @Param("now") long nowMs);

    /** 只改状态与（给了的）文案；文案为 null 保留原文案。 */
    @Update("UPDATE zone_config SET manual_status = #{status}, maintenance_msg = COALESCE(#{msg}, maintenance_msg),"
            + " updated_at = #{now} WHERE zone_id = #{zoneId}")
    int updateZoneStatus(@Param("zoneId") int zoneId, @Param("status") int status, @Param("msg") String maintenanceMsg,
                         @Param("now") long nowMs);

    @Delete("DELETE FROM zone_config WHERE zone_id = #{zoneId}")
    int deleteZone(@Param("zoneId") int zoneId);

    // ------------------------------------------------------------------ 白名单

    @Select("SELECT zone_id, account, note FROM zone_whitelist WHERE zone_id = #{zoneId} ORDER BY account")
    List<WhitelistRow> selectWhitelist(@Param("zoneId") int zoneId);

    @Select("SELECT zone_id, account, note FROM zone_whitelist WHERE zone_id = #{zoneId} AND account = #{account}")
    WhitelistRow selectWhitelistEntry(@Param("zoneId") int zoneId, @Param("account") String account);

    @Insert("INSERT INTO zone_whitelist (zone_id, account, note) VALUES (#{zoneId}, #{account}, #{note})"
            + " ON DUPLICATE KEY UPDATE note = VALUES(note)")
    int upsertWhitelist(@Param("zoneId") int zoneId, @Param("account") String account, @Param("note") String note);

    @Delete("DELETE FROM zone_whitelist WHERE zone_id = #{zoneId} AND account = #{account}")
    int deleteWhitelist(@Param("zoneId") int zoneId, @Param("account") String account);

    // ------------------------------------------------------------------ 公告

    String ANNOUNCEMENT_COLUMNS = "id, title, content, type, start_time, end_time, created_at";

    /** 生效中的（起点为空或已到、终点为空或未过），新的在前。 */
    @Select("SELECT " + ANNOUNCEMENT_COLUMNS + " FROM announcement"
            + " WHERE (start_time IS NULL OR start_time <= #{now}) AND (end_time IS NULL OR end_time >= #{now})"
            + " ORDER BY created_at DESC, id DESC")
    List<AnnouncementRow> selectActiveAnnouncements(@Param("now") long nowSec);

    @Select("SELECT " + ANNOUNCEMENT_COLUMNS + " FROM announcement ORDER BY id")
    List<AnnouncementRow> selectAnnouncements();

    @Select("SELECT " + ANNOUNCEMENT_COLUMNS + " FROM announcement WHERE id = #{id}")
    AnnouncementRow selectAnnouncement(@Param("id") long id);

    @Insert("INSERT INTO announcement (title, content, type, start_time, end_time, created_at)"
            + " VALUES (#{a.title}, #{a.content}, #{a.type}, #{a.startTime}, #{a.endTime}, #{a.createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "key.id", keyColumn = "id")
    int insertAnnouncement(@Param("a") AnnouncementRow announcement, @Param("key") GeneratedKey key);

    @Delete("DELETE FROM announcement WHERE id = #{id}")
    int deleteAnnouncement(@Param("id") long id);

    /** 自增主键的回填容器（记录是不可变的，MyBatis 回填不进去）。 */
    final class GeneratedKey {
        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }
}
