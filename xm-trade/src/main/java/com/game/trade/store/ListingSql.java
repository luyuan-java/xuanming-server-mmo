package com.game.trade.store;

import com.game.proto.trade.ListingCategory;
import com.game.proto.trade.ListingSort;
import com.game.proto.trade.ListingTab;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 聚宝斋的 SQL 全目录（基线 listing_repo.go，Java 逐字照搬；trade-spec §1.6、§1.7）：常量、过滤条件与排序片段的拼接、每条语句的参数。
 * 纯函数，不碰连接；{@link JdbcListingStore} 只负责执行这里造出的 {@link Stmt}。
 *
 * <p><b>参数的绑定形式</b>（与基线 Go 参数的类型一一对应，单测逐个比较）：
 * <ul>
 *   <li>有符号 {@code int} 列（{@code category}、{@code status}；Go 里是 {@code int32(...)}）→ {@link Integer}；</li>
 *   <li>{@code int unsigned} 列（{@code market_zone}、{@code subcategory}、{@code level}；Go {@code uint32}）与 LIMIT → {@link Long}
 *       （{@link Integer#toUnsignedLong}，见 {@link #u32}）；</li>
 *   <li>{@code bigint unsigned} 列（各种号、价格、毫秒；Go {@code uint64}）与 OFFSET → {@link Long}，≥ 2^63 的位模式换成 {@link BigInteger}
 *       （BIGINT UNSIGNED 不收负数，见 {@link #u64}）；</li>
 *   <li>文本 → {@link String}。</li>
 * </ul>
 * 列名一律加反引号：{@code level / status / description / version} 是关键字（listing_repo.go:70-72）。
 */
final class ListingSql {

    /** 摘要列 18 列，<b>不含 description</b>（浏览 / 货架不取最长 512 字符的 MEDIUMTEXT；listing_repo.go:73-76）。读取顺序见 JdbcListingStore.readListing。 */
    static final String SUMMARY_COLUMNS = "`listing_id`, `seller_player_id`, `seller_account`, `market_zone`, `seller_zone_at_listing`, "
            + "`category`, `subcategory`, `title`, `level`, `price_fen`, `status`, `summary`, `icon_key`, "
            + "`notice_end_ms`, `sale_end_ms`, `created_ms`, `updated_ms`, `version`";
    /** 详情列 = 摘要列 + description（19 列；listing_repo.go:77）。 */
    static final String DETAIL_COLUMNS = SUMMARY_COLUMNS + ", `description`";

    static final int SUMMARY_COLUMN_COUNT = 18;
    static final int DETAIL_COLUMN_COUNT = 19;

    /** L1 CountListings（listing_repo.go:106）：后接 {@link #filter} 的 WHERE。 */
    static final String COUNT_LISTINGS = "SELECT COUNT(*) FROM trade_listing";
    /** L2 QueryListings（listing_repo.go:122-123）：后接 WHERE、{@code " ORDER BY " + 排序片段}、{@link #LIMIT_OFFSET}。 */
    static final String QUERY_LISTINGS = "SELECT " + SUMMARY_COLUMNS + " FROM trade_listing";
    static final String ORDER_BY = " ORDER BY ";
    static final String LIMIT_OFFSET = " LIMIT ? OFFSET ?";
    /** L3 CountSellerListings（listing_repo.go:135）：走 (seller_player_id, listing_id) 索引。 */
    static final String COUNT_SELLER_LISTINGS = "SELECT COUNT(*) FROM trade_listing WHERE `seller_player_id` = ?";
    /** L4 QuerySellerListings（listing_repo.go:143-144）：货架任意状态，新上架在前。 */
    static final String QUERY_SELLER_LISTINGS = "SELECT " + SUMMARY_COLUMNS + " FROM trade_listing WHERE `seller_player_id` = ?"
            + " ORDER BY `listing_id` DESC LIMIT ? OFFSET ?";
    /** L5 GetListing（listing_repo.go:176）。 */
    static final String GET_LISTING = "SELECT " + DETAIL_COLUMNS + " FROM trade_listing WHERE `listing_id` = ?";
    /**
     * L6 InsertListing（listing_repo.go:193）：按详情列序 19 个占位。<b>主键冲突按故障</b>：listing_id 来自发号器，撞号说明发号源出了问题，
     * 绝不能静默覆盖，所以刻意不用 INSERT IGNORE / ON DUPLICATE KEY UPDATE（:187-188）。
     */
    static final String INSERT_LISTING = "INSERT INTO trade_listing (" + DETAIL_COLUMNS
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    /** F1 FavoriteIDs 的前缀（listing_repo.go:222）：后接 {@code ?,?,…)}。一页最多 MaxPageSize 个 id，IN 列表很短。 */
    static final String FAVORITE_IDS_PREFIX = "SELECT `listing_id` FROM trade_favorite WHERE `player_id` = ? AND `listing_id` IN (";
    /** F2 FavoriteExists（listing_repo.go:246）。 */
    static final String FAVORITE_EXISTS = "SELECT 1 FROM trade_favorite WHERE `player_id` = ? AND `listing_id` = ?";
    /** F3 CountFavorites（listing_repo.go:262）：主键前缀 player_id；不 join 商品表，过期收藏照样计数（§2.7、Q8）。 */
    static final String COUNT_FAVORITES = "SELECT COUNT(*) FROM trade_favorite WHERE `player_id` = ?";
    /**
     * F4 InsertFavorite（listing_repo.go:285-286）。ODKU 的空更新让重复收藏（客户端重试 / 并发双击 / 多端）幂等，且不刷新原收藏时间。
     *
     * <p><b>为什么不是 INSERT IGNORE</b>（2026-09-21 死锁审计 #9，listing_repo.go:268-284）：收藏 → 取消 → 再收藏时，主键可能是刚被删掉、
     * 尚未 purge 的删除标记记录。有第三方（未提交的取消收藏 DELETE，或先到后回滚的收藏）持着它的 X 时，两个 INSERT IGNORE 的重复键检查
     * 同时拿到 S、再各自申请 X → 1213；ODKU 的重复键检查直接取 X，排队者只能逐个拿到：先到者复活记录，后到者看见活行走空更新。
     * {@code ListingSqlTest} 用纯文本钉住「必须是 ODKU、不得含 IGNORE」，{@code FavoriteRevivalMysqlTest} 在真库上红绿对照。
     */
    static final String INSERT_FAVORITE = "INSERT INTO trade_favorite (`player_id`, `listing_id`, `created_ms`) VALUES (?, ?, ?)"
            + " ON DUPLICATE KEY UPDATE `created_ms` = `created_ms`";
    /** F5 DeleteFavorite（listing_repo.go:319）：自动提交；会话级 RC 保证它不拿间隙锁（servicecontext.go:229-235）。 */
    static final String DELETE_FAVORITE = "DELETE FROM trade_favorite WHERE `player_id` = ? AND `listing_id` = ?";

    private static final int TAB_PUBLIC_NOTICE = ListingTab.LISTING_TAB_PUBLIC_NOTICE_VALUE;
    private static final int TAB_ON_SALE = ListingTab.LISTING_TAB_ON_SALE_VALUE;

    private ListingSql() {
    }

    /** 一条待执行的语句：SQL 原文与按占位顺序排好的绑定值。 */
    record Stmt(String sql, List<Object> args) {
        Stmt {
            args = Collections.unmodifiableList(new ArrayList<>(args));
        }
    }

    /** {@link #filter} 的结果：{@code " WHERE ..."} 与参数。 */
    record Filter(String where, List<Object> args) {
    }

    // ================================================================ 浏览：过滤条件与排序

    /**
     * buildListingFilter（listing_repo.go:351-401）：条件拼接顺序固定，全部参数化。
     * <ol>
     *   <li>页签：公示 = {@code status = LISTED AND notice_end_ms > now}；寄售 = {@code status IN (LISTED, LOCKED) AND notice_end_ms <= now
     *       AND sale_end_ms > now}；其余页签拒绝（防御：缺页签不能退化成不带状态条件的全表查询）；</li>
     *   <li>类目为 0 拒绝；</li>
     *   <li>分区非 0 → {@code market_zone = ?}；</li>
     *   <li>{@code category = ?}；</li>
     *   <li>子类非 0 → {@code subcategory = ?}；</li>
     *   <li>搜索：有编号 → {@code (listing_id = ? OR title LIKE ? ESCAPE '!')}，没编号 → {@code title LIKE ? ESCAPE '!'}；</li>
     *   <li>只看收藏 → {@code EXISTS (SELECT 1 FROM trade_favorite f WHERE ...)}。</li>
     * </ol>
     *
     * @throws IllegalArgumentException 页签不是公示 / 寄售，或类目为 0
     */
    static Filter filter(ListingQuery q) {
        List<String> conds = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (q.tab() == TAB_PUBLIC_NOTICE) {
            conds.add("`status` = ?");
            conds.add("`notice_end_ms` > ?");
            args.add(ListingStatuses.LISTED);
            args.add(u64(q.nowMs()));
        } else if (q.tab() == TAB_ON_SALE) {
            conds.add("`status` IN (?, ?)");
            conds.add("`notice_end_ms` <= ?");
            conds.add("`sale_end_ms` > ?");
            args.add(ListingStatuses.LISTED);
            args.add(ListingStatuses.LOCKED);
            args.add(u64(q.nowMs()));
            args.add(u64(q.nowMs()));
        } else {
            throw new IllegalArgumentException("trade: unsupported listing tab " + q.tab());
        }
        if (q.category() == ListingCategory.LISTING_CATEGORY_UNSPECIFIED_VALUE) {
            throw new IllegalArgumentException("trade: listing query requires a category");
        }
        if (q.marketZone() != 0) {
            conds.add("`market_zone` = ?");
            args.add(u32(q.marketZone()));
        }
        conds.add("`category` = ?");
        args.add(q.category());
        if (q.subcategory() != 0) {
            conds.add("`subcategory` = ?");
            args.add(u32(q.subcategory()));
        }
        if (!q.titleLikePattern().isEmpty()) {
            if (q.searchListingId() != 0) {
                conds.add("(`listing_id` = ? OR `title` LIKE ? ESCAPE '!')");
                args.add(u64(q.searchListingId()));
                args.add(q.titleLikePattern());
            } else {
                conds.add("`title` LIKE ? ESCAPE '!'");
                args.add(q.titleLikePattern());
            }
        }
        if (q.favoritesOf() != 0) {
            conds.add("EXISTS (SELECT 1 FROM trade_favorite f WHERE f.`player_id` = ? AND f.`listing_id` = trade_listing.`listing_id`)");
            args.add(u64(q.favoritesOf()));
        }
        return new Filter(" WHERE " + String.join(" AND ", conds), args);
    }

    /**
     * listingOrderBy（listing_repo.go:403-424）：ORDER BY 片段的固定映射，只能从这里取，绝不拼用户输入。每种排序都以 listing_id 作最终决胜列，
     * 保证翻页不重不漏（jubaozhai.proto:46）。REMAINING_ASC 按页签选列：公示列表按公示结束、寄售列表按寄售结束。
     *
     * @throws IllegalArgumentException 未知排序（不能回落到任意 ORDER BY）
     */
    static String orderBy(int sort, int tab) {
        return switch (sort) {
            case ListingSort.LISTING_SORT_DEFAULT_VALUE -> "`listing_id` DESC";
            case ListingSort.LISTING_SORT_PRICE_ASC_VALUE -> "`price_fen` ASC, `listing_id` ASC";
            case ListingSort.LISTING_SORT_PRICE_DESC_VALUE -> "`price_fen` DESC, `listing_id` DESC";
            case ListingSort.LISTING_SORT_LEVEL_DESC_VALUE -> "`level` DESC, `listing_id` DESC";
            case ListingSort.LISTING_SORT_REMAINING_ASC_VALUE -> tab == TAB_PUBLIC_NOTICE
                    ? "`notice_end_ms` ASC, `listing_id` ASC"
                    : "`sale_end_ms` ASC, `listing_id` ASC";
            default -> throw new IllegalArgumentException("trade: unsupported listing sort " + sort);
        };
    }

    // ================================================================ 每条语句

    /** L1。 */
    static Stmt countListings(ListingQuery q) {
        Filter f = filter(q);
        return new Stmt(COUNT_LISTINGS + f.where(), f.args());
    }

    /** L2：参数 = 过滤参数…, limit, offset（listing_repo.go:125）。 */
    static Stmt queryListings(ListingQuery q, long offset, int limit) {
        Filter f = filter(q);
        String order = orderBy(q.sort(), q.tab());
        List<Object> args = new ArrayList<>(f.args());
        args.add(u32(limit));
        args.add(u64(offset));
        return new Stmt(QUERY_LISTINGS + f.where() + ORDER_BY + order + LIMIT_OFFSET, args);
    }

    /** L3。 */
    static Stmt countSellerListings(long seller) {
        return new Stmt(COUNT_SELLER_LISTINGS, List.of(u64(seller)));
    }

    /** L4：参数 = seller, limit, offset。 */
    static Stmt querySellerListings(long seller, long offset, int limit) {
        return new Stmt(QUERY_SELLER_LISTINGS, List.of(u64(seller), u32(limit), u64(offset)));
    }

    /** L5。 */
    static Stmt getListing(long listingId) {
        return new Stmt(GET_LISTING, List.of(u64(listingId)));
    }

    /** L6：按详情列序（listing_repo.go:194-198；description 在最后）。 */
    static Stmt insertListing(Listing l) {
        return new Stmt(INSERT_LISTING, List.of(
                u64(l.listingId()), u64(l.sellerPlayerId()), l.sellerAccount(), u32(l.marketZone()), u32(l.sellerZoneAtListing()),
                l.category(), u32(l.subcategory()), l.title(), u32(l.level()), u64(l.priceFen()),
                l.status(), l.summary(), l.iconKey(),
                u64(l.noticeEndMs()), u64(l.saleEndMs()), u64(l.createdMs()), u64(l.updatedMs()), u64(l.version()),
                l.description()));
    }

    /**
     * F1：参数 = player, ids…（listing_repo.go:212-222）。
     *
     * @throws IllegalArgumentException ids 为空（调用方应直接返回空集合、不查库，listing_repo.go:209-211）
     */
    static Stmt favoriteIds(long playerId, List<Long> listingIds) {
        if (listingIds.isEmpty()) {
            throw new IllegalArgumentException("favoriteIds 的 id 列表为空：调用方应直接返回空集合");
        }
        List<Object> args = new ArrayList<>(listingIds.size() + 1);
        args.add(u64(playerId));
        for (long id : listingIds) {
            args.add(u64(id));
        }
        String placeholders = String.join(",", Collections.nCopies(listingIds.size(), "?"));
        return new Stmt(FAVORITE_IDS_PREFIX + placeholders + ")", args);
    }

    /** F2。 */
    static Stmt favoriteExists(long playerId, long listingId) {
        return new Stmt(FAVORITE_EXISTS, List.of(u64(playerId), u64(listingId)));
    }

    /** F3。 */
    static Stmt countFavorites(long playerId) {
        return new Stmt(COUNT_FAVORITES, List.of(u64(playerId)));
    }

    /** F4：参数 = player, listing, created_ms。 */
    static Stmt insertFavorite(long playerId, long listingId, long createdMs) {
        return new Stmt(INSERT_FAVORITE, List.of(u64(playerId), u64(listingId), u64(createdMs)));
    }

    /** F5。 */
    static Stmt deleteFavorite(long playerId, long listingId) {
        return new Stmt(DELETE_FAVORITE, List.of(u64(playerId), u64(listingId)));
    }

    // ================================================================ 执行期改写与绑定形式

    /**
     * 给 SELECT 加服务端执行时限：紧跟 {@code SELECT} 插入优化器提示 {@code MAX_EXECUTION_TIME(n)}（n 毫秒；trade-spec §5.4；写法照
     * PlayerProfiles.readBatch）。MySQL 按毫秒中止超时的只读 SELECT；JDBC 查询超时只能按秒，作网络停顿的兜底。非 SELECT 原样返回
     * （写语句靠查询超时与 {@code innodb_lock_wait_timeout} 封顶）。
     */
    static String withExecutionTimeHint(String sql, long millis) {
        if (!sql.startsWith("SELECT ")) {
            return sql;
        }
        return "SELECT /*+ MAX_EXECUTION_TIME(" + Math.max(1, millis) + ") */ " + sql.substring("SELECT ".length());
    }

    /** uint64 位模式的绑定值：≥ 2^63 的用 {@link BigInteger}（BIGINT UNSIGNED 不收负数）。 */
    static Object u64(long bits) {
        return bits >= 0 ? (Object) bits : new BigInteger(Long.toUnsignedString(bits));
    }

    /** uint32 位模式的绑定值。 */
    static Long u32(int bits) {
        return Integer.toUnsignedLong(bits);
    }
}
