package com.game.trade.service;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.trade.store.Listing;
import com.game.trade.store.ListingQuery;
import com.game.trade.store.ListingStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 服务单测的假存储（照基线 fakeStore，jubaozhai_logic_test.go:50-243）：浏览 / 货架的返回值由用例直接给出，不模拟 SQL 过滤（SQL 在 store 包测）；
 * {@link #failOn} 让某个操作抛 {@link DependencyException}（= 存储故障），{@link #blockOn} 让某个操作「卡住」直到请求预算到期再抛（模拟依赖变慢）。
 * 每次调用记下操作名与收到的 {@link Deadline}（对象同一性 = 共用一个整请求预算）。单线程使用。
 */
public final class FakeListingStore implements ListingStore {

    public record FavoriteKey(long player, long listing) {
    }

    public final Map<Long, Listing> listings = new HashMap<>();
    public final Map<FavoriteKey, Long> favorites = new HashMap<>();

    public long total;
    public List<Listing> page = List.of();
    /** 非 null 时 countFavorites 返回它（测上限而不必真塞满）。 */
    public Long favoriteCountOverride;

    public final Map<String, RuntimeException> failOn = new HashMap<>();
    public final Set<String> blockOn = new HashSet<>();
    public final List<String> calls = new ArrayList<>();
    public final List<Deadline> deadlines = new ArrayList<>();

    public ListingQuery lastQuery;
    public long lastSeller;
    public long lastOffset;
    public int lastLimit;
    public List<Long> lastFavoriteLookup;
    public final List<Listing> insertedListings = new ArrayList<>();
    public final List<long[]> insertedFavorites = new ArrayList<>();
    public final List<FavoriteKey> deletedFavorites = new ArrayList<>();

    private void enter(String op, Deadline deadline) {
        calls.add(op);
        deadlines.add(deadline);
        if (blockOn.contains(op)) {
            // 不响应的依赖：只有预算到期才返回（同 JdbcListingStore：预算到期抛 DependencyException）
            while (!deadline.expired()) {
                try {
                    Thread.sleep(Math.max(1, Math.min(20, deadline.remainingMillis())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            throw new DependencyException("fake: " + op + " 超过请求预算");
        }
        RuntimeException failure = failOn.get(op);
        if (failure != null) {
            throw failure;
        }
    }

    public boolean called(String op) {
        return calls.contains(op);
    }

    @Override
    public long countListings(ListingQuery query, Deadline deadline) {
        enter("CountListings", deadline);
        lastQuery = query;
        return total;
    }

    @Override
    public List<Listing> queryListings(ListingQuery query, long offset, int limit, Deadline deadline) {
        enter("QueryListings", deadline);
        lastQuery = query;
        lastOffset = offset;
        lastLimit = limit;
        return page;
    }

    @Override
    public long countSellerListings(long sellerPlayerId, Deadline deadline) {
        enter("CountSellerListings", deadline);
        lastSeller = sellerPlayerId;
        return total;
    }

    @Override
    public List<Listing> querySellerListings(long sellerPlayerId, long offset, int limit, Deadline deadline) {
        enter("QuerySellerListings", deadline);
        lastSeller = sellerPlayerId;
        lastOffset = offset;
        lastLimit = limit;
        return page;
    }

    @Override
    public Optional<Listing> getListing(long listingId, Deadline deadline) {
        enter("GetListing", deadline);
        return Optional.ofNullable(listings.get(listingId));
    }

    @Override
    public void insertListing(Listing listing, Deadline deadline) {
        enter("InsertListing", deadline);
        insertedListings.add(listing);
        listings.put(listing.listingId(), listing);
    }

    @Override
    public Set<Long> favoriteIds(long playerId, List<Long> listingIds, Deadline deadline) {
        enter("FavoriteIDs", deadline);
        lastFavoriteLookup = List.copyOf(listingIds);
        Set<Long> out = new HashSet<>();
        for (long id : listingIds) {
            if (favorites.containsKey(new FavoriteKey(playerId, id))) {
                out.add(id);
            }
        }
        return out;
    }

    @Override
    public boolean favoriteExists(long playerId, long listingId, Deadline deadline) {
        enter("FavoriteExists", deadline);
        return favorites.containsKey(new FavoriteKey(playerId, listingId));
    }

    @Override
    public long countFavorites(long playerId, Deadline deadline) {
        enter("CountFavorites", deadline);
        if (favoriteCountOverride != null) {
            return favoriteCountOverride;
        }
        return favorites.keySet().stream().filter(k -> k.player() == playerId).count();
    }

    @Override
    public void insertFavorite(long playerId, long listingId, long createdMs, Deadline deadline) {
        enter("InsertFavorite", deadline);
        insertedFavorites.add(new long[] {playerId, listingId, createdMs});
        favorites.putIfAbsent(new FavoriteKey(playerId, listingId), createdMs);
    }

    @Override
    public void deleteFavorite(long playerId, long listingId, Deadline deadline) {
        enter("DeleteFavorite", deadline);
        deletedFavorites.add(new FavoriteKey(playerId, listingId));
        favorites.remove(new FavoriteKey(playerId, listingId));
    }
}
