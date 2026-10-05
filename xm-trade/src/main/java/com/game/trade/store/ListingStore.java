package com.game.trade.store;

import com.game.common.deadline.Deadline;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 服务层眼里的聚宝斋存储（基线 data.ListingStore，listing_repo.go:44-68；trade-spec §1.6）。{@link JdbcListingStore} 是 MySQL 实现，
 * 服务单测注入假实现。
 *
 * <p>契约：
 * <ul>
 *   <li>每个方法自带单次上限 {@code min(STORE_OP_TIMEOUT_MS = 2000 ms, 请求剩余)}（listing_repo.go:44-50、:93-95）；请求预算已经用完就不取连接、
 *       不发语句；</li>
 *   <li>任何失败都抛 {@link RuntimeException}：SQL / 连接 / 超时 / 预算用完是 {@link Deadline.DependencyException}，查询描述本身非法（缺页签、
 *       缺类目、未知排序——服务层已校验，走到就是程序错误）是 {@link IllegalArgumentException}。调用方把本接口抛出的<b>任何</b>
 *       RuntimeException 定性为存储故障 1003（基线「返回的 error 一律是存储故障」，listing_repo.go:48-49）；唯一的业务结果「不存在」由
 *       {@link #getListing} 的 {@link Optional#empty()} 表达；</li>
 *   <li>分页参数由调用方钳制，本层不再二次钳制（listing_repo.go:50）。</li>
 * </ul>
 * 玩家号、商品号、偏移量都是 uint64 位模式。全部方法阻塞（JDBC），只在 trade-worker 线程（或播种接口的 Tomcat 线程）上调用。
 */
public interface ListingStore {

    /** L1：浏览条件命中的总数（分页的 total_count / page_count；listing_repo.go:97-110）。 */
    long countListings(ListingQuery query, Deadline deadline);

    /** L2：浏览条件命中的一页（不取 description；listing_repo.go:112-127）。 */
    List<Listing> queryListings(ListingQuery query, long offset, int limit, Deadline deadline);

    /** L3：卖家任意状态的商品数（货架；listing_repo.go:129-139）。 */
    long countSellerListings(long sellerPlayerId, Deadline deadline);

    /** L4：卖家货架的一页，任意状态，{@code listing_id DESC}（不取 description；listing_repo.go:141-146）。 */
    List<Listing> querySellerListings(long sellerPlayerId, long offset, int limit, Deadline deadline);

    /** L5：按主键取含 description 的完整行；不存在返回 {@link Optional#empty()}（listing_repo.go:171-185）。 */
    Optional<Listing> getListing(long listingId, Deadline deadline);

    /** L6：插入一条完整商品行。<b>主键冲突按故障抛出</b>，绝不静默覆盖（listing_repo.go:187-204）。 */
    void insertListing(Listing listing, Deadline deadline);

    /** F1：{@code listingIds} 中该玩家已收藏的子集；{@code listingIds} 为空时不查库（listing_repo.go:206-238）。 */
    Set<Long> favoriteIds(long playerId, List<Long> listingIds, Deadline deadline);

    /** F2：按主键 (player_id, listing_id) 判断是否已收藏（listing_repo.go:240-254）。 */
    boolean favoriteExists(long playerId, long listingId, Deadline deadline);

    /** F3：玩家收藏条数，<b>包括已经看不见的收藏</b>（不 join 商品表；listing_repo.go:256-266；§2.7）。 */
    long countFavorites(long playerId, Deadline deadline);

    /** F4：收藏，幂等：重复收藏不报错、不刷新原收藏时间（ODKU 空更新，不是 INSERT IGNORE；listing_repo.go:268-312；§1.9）。 */
    void insertFavorite(long playerId, long listingId, long createdMs, Deadline deadline);

    /** F5：取消收藏，幂等：行不存在也不报错（listing_repo.go:314-323）。 */
    void deleteFavorite(long playerId, long listingId, Deadline deadline);
}
