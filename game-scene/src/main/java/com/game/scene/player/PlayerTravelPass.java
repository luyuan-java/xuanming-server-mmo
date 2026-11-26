package com.game.scene.player;

import com.game.player.store.state.PlayerTravelPassState;
import com.game.player.store.state.TravelPassSeasonProgressState;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 青云游历手记持久运行态（对齐 C++ {@code PlayerTravelPassComp} 与 {@code travel_pass_rules.h}）。
 *
 * <p>容量与合法性 fail-closed：读到超限、重复期次或非法外显键时保留原始状态并置 {@link #isValid()} 为 false，
 * 业务入口拒回 {@code kTravelPassUnavailable}，绝不静默抹除历史进度或已解锁典藏。
 */
public final class PlayerTravelPass {

    public static final int MAX_SEASONS = 32;
    public static final int MAX_TIERS_PER_SEASON = 64;
    public static final int MAX_COSMETICS_PER_SEASON = 64;
    public static final int MAX_COSMETIC_KEY_LENGTH = 64;

    public static final int COSMETIC_SLOT_FRAME = 1;
    public static final int COSMETIC_SLOT_TITLE = 2;

    public static final class SeasonProgress {
        private final int passId;
        private int progressPoints;
        private boolean premiumUnlocked;
        private long premiumUnlockedAtMs;
        private long freeClaimedMask;
        private long premiumClaimedMask;
        private final List<String> unlockedCosmeticKeys = new ArrayList<>();
        private long lastUpdatedAtMs;
        private UnknownFieldSet unknownFields = UnknownFieldSet.getDefaultInstance();

        SeasonProgress(int passId) {
            this.passId = passId;
        }

        SeasonProgress(TravelPassSeasonProgressState s) {
            this.passId = s.getPassId();
            this.progressPoints = s.getProgressPoints();
            this.premiumUnlocked = s.getPremiumUnlocked();
            this.premiumUnlockedAtMs = s.getPremiumUnlockedAtMs();
            this.freeClaimedMask = s.getFreeClaimedMask();
            this.premiumClaimedMask = s.getPremiumClaimedMask();
            this.unlockedCosmeticKeys.addAll(s.getUnlockedCosmeticKeysList());
            this.lastUpdatedAtMs = s.getLastUpdatedAtMs();
            this.unknownFields = s.getUnknownFields();
        }

        SeasonProgress copy() {
            SeasonProgress out = new SeasonProgress(passId);
            out.progressPoints = progressPoints;
            out.premiumUnlocked = premiumUnlocked;
            out.premiumUnlockedAtMs = premiumUnlockedAtMs;
            out.freeClaimedMask = freeClaimedMask;
            out.premiumClaimedMask = premiumClaimedMask;
            out.unlockedCosmeticKeys.addAll(unlockedCosmeticKeys);
            out.lastUpdatedAtMs = lastUpdatedAtMs;
            out.unknownFields = unknownFields;
            return out;
        }

        public int passId() {
            return passId;
        }

        public int progressPoints() {
            return progressPoints;
        }

        public void setProgressPoints(int progressPoints) {
            this.progressPoints = progressPoints;
        }

        public boolean premiumUnlocked() {
            return premiumUnlocked;
        }

        public void setPremiumUnlocked(boolean premiumUnlocked) {
            this.premiumUnlocked = premiumUnlocked;
        }

        public long premiumUnlockedAtMs() {
            return premiumUnlockedAtMs;
        }

        public void setPremiumUnlockedAtMs(long premiumUnlockedAtMs) {
            this.premiumUnlockedAtMs = premiumUnlockedAtMs;
        }

        public long freeClaimedMask() {
            return freeClaimedMask;
        }

        public void setFreeClaimedMask(long freeClaimedMask) {
            this.freeClaimedMask = freeClaimedMask;
        }

        public long premiumClaimedMask() {
            return premiumClaimedMask;
        }

        public void setPremiumClaimedMask(long premiumClaimedMask) {
            this.premiumClaimedMask = premiumClaimedMask;
        }

        public List<String> unlockedCosmeticKeys() {
            return Collections.unmodifiableList(unlockedCosmeticKeys);
        }

        public boolean addCosmeticKey(String key) {
            if (key == null || key.isEmpty()) {
                return true;
            }
            if (unlockedCosmeticKeys.contains(key)) {
                return true;
            }
            if (unlockedCosmeticKeys.size() >= MAX_COSMETICS_PER_SEASON) {
                return false;
            }
            unlockedCosmeticKeys.add(key);
            return true;
        }

        public long lastUpdatedAtMs() {
            return lastUpdatedAtMs;
        }

        public void setLastUpdatedAtMs(long lastUpdatedAtMs) {
            this.lastUpdatedAtMs = lastUpdatedAtMs;
        }

        TravelPassSeasonProgressState toState() {
            return TravelPassSeasonProgressState.newBuilder()
                    .setPassId(passId)
                    .setProgressPoints(progressPoints)
                    .setPremiumUnlocked(premiumUnlocked)
                    .setPremiumUnlockedAtMs(premiumUnlockedAtMs)
                    .setFreeClaimedMask(freeClaimedMask)
                    .setPremiumClaimedMask(premiumClaimedMask)
                    .addAllUnlockedCosmeticKeys(unlockedCosmeticKeys)
                    .setLastUpdatedAtMs(lastUpdatedAtMs)
                    .setUnknownFields(unknownFields)
                    .build();
        }
    }

    private final TreeMap<Integer, SeasonProgress> seasons = new TreeMap<>(Integer::compareUnsigned);
    private String equippedFrameKey = "";
    private String equippedTitleKey = "";
    private long passRevision;
    private boolean valid = true;
    private PlayerTravelPassState rawInvalidState;
    private UnknownFieldSet unknownFields = UnknownFieldSet.getDefaultInstance();

    private PlayerTravelPass() {
    }

    public static PlayerTravelPass empty() {
        return new PlayerTravelPass();
    }

    public static boolean isFrameKey(String key) {
        return key != null && key.length() > 6 && key.length() <= MAX_COSMETIC_KEY_LENGTH && key.startsWith("frame_");
    }

    public static boolean isTitleKey(String key) {
        return key != null && key.length() > 6 && key.length() <= MAX_COSMETIC_KEY_LENGTH && key.startsWith("title_");
    }

    public static boolean isValidCosmeticKey(String key) {
        return isFrameKey(key) || isTitleKey(key);
    }

    public static long tierBit(int tierIndex) {
        if (tierIndex <= 0 || tierIndex > MAX_TIERS_PER_SEASON) {
            return 0L;
        }
        return 1L << (tierIndex - 1);
    }

    public static boolean isTierClaimed(long mask, int tierIndex) {
        long bit = tierBit(tierIndex);
        return bit != 0L && (mask & bit) != 0L;
    }

    public static PlayerTravelPass restore(PlayerTravelPassState state) {
        PlayerTravelPass out = new PlayerTravelPass();
        if (state == null) {
            return out;
        }
        out.equippedFrameKey = state.getEquippedFrameKey();
        out.equippedTitleKey = state.getEquippedTitleKey();
        out.passRevision = state.getPassRevision();
        out.unknownFields = state.getUnknownFields();
        if (state.getSeasonsCount() > MAX_SEASONS) {
            out.valid = false;
            out.rawInvalidState = state;
            return out;
        }
        for (TravelPassSeasonProgressState s : state.getSeasonsList()) {
            if (s.getPassId() == 0 || out.seasons.containsKey(s.getPassId())
                    || s.getUnlockedCosmeticKeysCount() > MAX_COSMETICS_PER_SEASON) {
                out.valid = false;
                out.rawInvalidState = state;
                return out;
            }
            for (String key : s.getUnlockedCosmeticKeysList()) {
                if (!isValidCosmeticKey(key)) {
                    out.valid = false;
                    out.rawInvalidState = state;
                    return out;
                }
            }
            out.seasons.put(s.getPassId(), new SeasonProgress(s));
        }
        if (!out.equippedFrameKey.isEmpty()
                && (!isFrameKey(out.equippedFrameKey) || !out.hasCosmeticKey(out.equippedFrameKey))) {
            out.valid = false;
            out.rawInvalidState = state;
            return out;
        }
        if (!out.equippedTitleKey.isEmpty()
                && (!isTitleKey(out.equippedTitleKey) || !out.hasCosmeticKey(out.equippedTitleKey))) {
            out.valid = false;
            out.rawInvalidState = state;
            return out;
        }
        return out;
    }

    public PlayerTravelPass copy() {
        PlayerTravelPass out = new PlayerTravelPass();
        for (Map.Entry<Integer, SeasonProgress> e : seasons.entrySet()) {
            out.seasons.put(e.getKey(), e.getValue().copy());
        }
        out.equippedFrameKey = equippedFrameKey;
        out.equippedTitleKey = equippedTitleKey;
        out.passRevision = passRevision;
        out.valid = valid;
        out.rawInvalidState = rawInvalidState;
        out.unknownFields = unknownFields;
        return out;
    }

    public void replaceFrom(PlayerTravelPass candidate) {
        seasons.clear();
        for (Map.Entry<Integer, SeasonProgress> e : candidate.seasons.entrySet()) {
            seasons.put(e.getKey(), e.getValue().copy());
        }
        this.equippedFrameKey = candidate.equippedFrameKey;
        this.equippedTitleKey = candidate.equippedTitleKey;
        this.passRevision = candidate.passRevision;
        this.valid = candidate.valid;
        this.rawInvalidState = candidate.rawInvalidState;
        this.unknownFields = candidate.unknownFields;
    }

    public boolean isValid() {
        return valid;
    }

    public boolean isPristine() {
        return valid && seasons.isEmpty() && equippedFrameKey.isEmpty() && equippedTitleKey.isEmpty()
                && passRevision == 0L && unknownFields.asMap().isEmpty();
    }

    public long passRevision() {
        return passRevision;
    }

    public boolean bumpRevision() {
        if (passRevision == -1L) {
            return false;
        }
        passRevision++;
        return true;
    }

    public String equippedFrameKey() {
        return equippedFrameKey;
    }

    public void setEquippedFrameKey(String key) {
        this.equippedFrameKey = key == null ? "" : key;
    }

    public String equippedTitleKey() {
        return equippedTitleKey;
    }

    public void setEquippedTitleKey(String key) {
        this.equippedTitleKey = key == null ? "" : key;
    }

    public SeasonProgress season(int passId) {
        return seasons.get(passId);
    }

    public List<SeasonProgress> seasons() {
        return List.copyOf(seasons.values());
    }

    public SeasonProgress findOrAddSeason(int passId) {
        SeasonProgress existing = seasons.get(passId);
        if (existing != null) {
            return existing;
        }
        if (passId == 0 || seasons.size() >= MAX_SEASONS) {
            return null;
        }
        SeasonProgress created = new SeasonProgress(passId);
        seasons.put(passId, created);
        return created;
    }

    public boolean hasCosmeticKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        for (SeasonProgress s : seasons.values()) {
            if (s.unlockedCosmeticKeys.contains(key)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> allUnlockedCosmeticsSorted() {
        Set<String> out = new TreeSet<>();
        for (SeasonProgress s : seasons.values()) {
            out.addAll(s.unlockedCosmeticKeys);
        }
        return out;
    }

    public PlayerTravelPassState toState() {
        if (!valid && rawInvalidState != null) {
            return rawInvalidState;
        }
        PlayerTravelPassState.Builder b = PlayerTravelPassState.newBuilder()
                .setEquippedFrameKey(equippedFrameKey)
                .setEquippedTitleKey(equippedTitleKey)
                .setPassRevision(passRevision)
                .setUnknownFields(unknownFields);
        for (SeasonProgress s : seasons.values()) {
            b.addSeasons(s.toState());
        }
        return b.build();
    }
}
