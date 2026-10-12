package com.game.gateway.store;

import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 区服目录 / 白名单 / 公告的读写（同 mmorpg gateway 的 ZoneConfigRepository / ZoneWhitelistRepository / AnnouncementRepository）。
 *
 * <p>写接口都是「一条按主键（唯一键）的写 + 同一事务里的回读」：写语句持有该行的 X 锁直到提交，回读必然看得到自己的写；
 * 「改 / 状态」的存在性按回读判定（不看受影响行数——连接参数 useAffectedRows 会让「值没变」报 0 行）。
 * 参数校验（名字长度、状态值、文案长度）在这里做，库的 sql_mode 不严格时也不会把非法值静默截断写进去。
 *
 * <p>线程安全；阻塞（MySQL），不得在 Netty I/O 线程上调用。
 */
public final class GatewayStore {

    public static final int NAME_MAX = 64;
    public static final int MAINTENANCE_MSG_MAX = 256;
    public static final int NOTE_MAX = 128;
    public static final int ACCOUNT_MAX = 64;
    public static final int TITLE_MAX = 128;
    public static final int TYPE_MAX = 32;
    /** TEXT 列最多 65535 字节，utf8mb4 一个字符至多 4 字节：16000 字符一定放得下（不靠库的 sql_mode 截断 / 报错）。 */
    public static final int CONTENT_MAX = 16_000;
    /** InnoDB 死锁（1213）时整事务重做的次数上限（同基线 InnoDbDeadlockRetry 的有界重试：同键并发写入时先到者回滚）。 */
    static final int DEADLOCK_ATTEMPTS = 3;

    private final GatewayStoreMapper mapper;
    private final TransactionOperations tx;
    private final LongSupplier nowMs;

    public GatewayStore(GatewayStoreMapper mapper, TransactionOperations tx, LongSupplier nowMs) {
        this.mapper = mapper;
        this.tx = tx;
        this.nowMs = nowMs;
    }

    // ------------------------------------------------------------------ 区服目录

    /** 全部区服，按 sort_order、zone_id 升序。 */
    public List<ZoneRow> zones() {
        return mapper.selectZones();
    }

    public Optional<ZoneRow> zone(int zoneId) {
        return Optional.ofNullable(mapper.selectZone(zoneId));
    }

    /** 创建；已存在则覆盖全部业务列（保留 created_at）。返回库里的那一行。 */
    public ZoneRow upsertZone(ZoneRow zone) {
        checkZone(zone);
        long now = nowMs.getAsLong();
        return retryOnDeadlock(() -> tx.execute(status -> {
            mapper.upsertZone(zone, now);
            return zone(zone.zoneId()).orElseThrow(() -> new IllegalStateException(
                    "zone_config upsert 之后同一事务内回读不到该行 zone_id=" + zone.zoneId()));
        }));
    }

    /** 启动播种：不存在才插入，已有的不动。@return 是否插入了 */
    public boolean seedZone(ZoneRow zone) {
        checkZone(zone);
        return mapper.insertZoneIfAbsent(zone, nowMs.getAsLong()) > 0;
    }

    /** 覆盖已存在区服的业务列（{@code zoneId} 以路径为准，忽略 values 里的）；不存在为空，绝不插入。 */
    public Optional<ZoneRow> updateZone(int zoneId, ZoneRow values) {
        checkZone(new ZoneRow(zoneId, values.name(), values.manualStatus(), values.capacity(), values.maintenanceMsg(),
                values.openTime(), values.recommended(), values.sortOrder(), 0, 0));
        long now = nowMs.getAsLong();
        return tx.execute(status -> {
            mapper.updateZone(zoneId, values, now);
            return zone(zoneId);
        });
    }

    /** 只改状态与文案（文案 null = 保留原文案）；不存在为空。 */
    public Optional<ZoneRow> setZoneStatus(int zoneId, ZoneManualStatus status, String maintenanceMsg) {
        if (maintenanceMsg != null && (tooLong(maintenanceMsg, MAINTENANCE_MSG_MAX) || hasControl(maintenanceMsg))) {
            throw new IllegalArgumentException("maintenance_msg 不超过 " + MAINTENANCE_MSG_MAX + " 字符、不含控制字符");
        }
        long now = nowMs.getAsLong();
        return tx.execute(txStatus -> {
            mapper.updateZoneStatus(zoneId, status.code(), maintenanceMsg, now);
            return zone(zoneId);
        });
    }

    /** @return 是否删了（不存在为 false） */
    public boolean deleteZone(int zoneId) {
        return mapper.deleteZone(zoneId) > 0;
    }

    private static void checkZone(ZoneRow zone) {
        if (zone.zoneId() <= 0) {
            throw new IllegalArgumentException("zone_id 必须大于 0");
        }
        if (zone.name() == null || zone.name().isBlank() || tooLong(zone.name(), NAME_MAX) || hasControl(zone.name())) {
            throw new IllegalArgumentException("name 必填，1–" + NAME_MAX + " 字符、不含控制字符");
        }
        if (ZoneManualStatus.fromCode(zone.manualStatus()).isEmpty()) {
            throw new IllegalArgumentException("manual_status 只能是 0 OPEN / 1 MAINTENANCE / 2 CLOSED / 3 PREVIEW");
        }
        if (zone.capacity() < 0) {
            throw new IllegalArgumentException("capacity 不能为负");
        }
        if (zone.maintenanceMsg() == null || tooLong(zone.maintenanceMsg(), MAINTENANCE_MSG_MAX)
                || hasControl(zone.maintenanceMsg())) {
            throw new IllegalArgumentException("maintenance_msg 不超过 " + MAINTENANCE_MSG_MAX + " 字符、不含控制字符");
        }
        if (zone.openTime() != null && zone.openTime() < 0) {
            throw new IllegalArgumentException("open_time 不能为负");
        }
    }

    // ------------------------------------------------------------------ 白名单

    public List<WhitelistRow> whitelist(int zoneId) {
        return mapper.selectWhitelist(zoneId);
    }

    /** 加入（幂等，备注以最后一次为准）。返回库里的那一行。 */
    public WhitelistRow addWhitelist(int zoneId, String account, String note) {
        if (zoneId <= 0) {
            throw new IllegalArgumentException("zone_id 必须大于 0");
        }
        if (account == null || account.isBlank() || tooLong(account, ACCOUNT_MAX) || hasControl(account)) {
            throw new IllegalArgumentException("account 必填，1–" + ACCOUNT_MAX + " 字符、不含控制字符");
        }
        String checkedNote = note == null ? "" : note;
        if (tooLong(checkedNote, NOTE_MAX) || hasControl(checkedNote)) {
            throw new IllegalArgumentException("note 不超过 " + NOTE_MAX + " 字符、不含控制字符");
        }
        return retryOnDeadlock(() -> tx.execute(status -> {
            mapper.upsertWhitelist(zoneId, account, checkedNote);
            WhitelistRow row = mapper.selectWhitelistEntry(zoneId, account);
            if (row == null) {
                throw new IllegalStateException("zone_whitelist upsert 之后同一事务内回读不到该行 zone_id=" + zoneId);
            }
            return row;
        }));
    }

    /** 移出（不存在也算成功）。账号含控制字符拒绝（它会进审计日志）。 */
    public void removeWhitelist(int zoneId, String account) {
        if (account == null || hasControl(account)) {
            throw new IllegalArgumentException("account 不含控制字符");
        }
        mapper.deleteWhitelist(zoneId, account);
    }

    // ------------------------------------------------------------------ 公告

    /** 此刻生效中的公告，新的在前。 */
    public List<AnnouncementRow> activeAnnouncements(long nowSec) {
        return mapper.selectActiveAnnouncements(nowSec);
    }

    public List<AnnouncementRow> announcements() {
        return mapper.selectAnnouncements();
    }

    /**
     * 新建一条（总是插入：请求里的 id 一律忽略——基线 save(entity) 带 id 时会覆盖已有公告）。{@code type} 为空取 notice。
     * 返回库里的那一行。
     */
    public AnnouncementRow createAnnouncement(String title, String content, String type, Long startTime, Long endTime) {
        if (title == null || title.isBlank() || tooLong(title, TITLE_MAX) || hasControl(title)) {
            throw new IllegalArgumentException("title 必填，1–" + TITLE_MAX + " 字符、不含控制字符");
        }
        if (content != null && tooLong(content, CONTENT_MAX)) {
            throw new IllegalArgumentException("content 不超过 " + CONTENT_MAX + " 字符");
        }
        String checkedType = type == null || type.isBlank() ? "notice" : type;
        if (tooLong(checkedType, TYPE_MAX) || hasControl(checkedType)) {
            throw new IllegalArgumentException("type 不超过 " + TYPE_MAX + " 字符、不含控制字符");
        }
        if (startTime != null && endTime != null && endTime < startTime) {
            throw new IllegalArgumentException("end_time 早于 start_time");
        }
        AnnouncementRow row = new AnnouncementRow(0, title, content, checkedType, startTime, endTime, nowMs.getAsLong());
        return tx.execute(status -> {
            GatewayStoreMapper.GeneratedKey key = new GatewayStoreMapper.GeneratedKey();
            mapper.insertAnnouncement(row, key);
            AnnouncementRow stored = key.getId() == null ? null : mapper.selectAnnouncement(key.getId());
            if (stored == null) {
                throw new IllegalStateException("announcement 插入之后同一事务内回读不到该行");
            }
            return stored;
        });
    }

    /** 删除（不存在也算成功）。 */
    public void deleteAnnouncement(long id) {
        mapper.deleteAnnouncement(id);
    }

    private static boolean tooLong(String s, int maxCodePoints) {
        return s.codePointCount(0, s.length()) > maxCodePoints;
    }

    /** 含控制字符（会进审计日志 / 客户端界面的短文本一律不收：防日志注入）。 */
    private static boolean hasControl(String s) {
        return s.codePoints().anyMatch(Character::isISOControl);
    }

    /** InnoDB 死锁（1213）时整事务重做，至多 {@link #DEADLOCK_ATTEMPTS} 次；别的异常原样抛出。 */
    private static <T> T retryOnDeadlock(Supplier<T> transaction) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.get();
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= DEADLOCK_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }
}
