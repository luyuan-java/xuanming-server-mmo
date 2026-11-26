package com.game.scene.rewardedad;

import com.game.scene.rewardedad.RewardedAdRules.SlotConfig;
import com.game.table.ConfigTables;
import com.game.table.RewardedAdSlotTable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 激励视频广告位不可变配表视图（{@code RewardedAdSlot}；非法行 fail-closed 拒下发拒领奖）。
 */
public final class RewardedAdTables {

    private static final Logger log = LoggerFactory.getLogger(RewardedAdTables.class);

    private final Map<Integer, SlotConfig> slotsById;
    private final List<SlotConfig> orderedSlots;
    private final boolean allRowsValid;

    public RewardedAdTables(List<RewardedAdSlotTable> rows) {
        Map<Integer, SlotConfig> valid = new LinkedHashMap<>();
        boolean allValid = true;
        for (RewardedAdSlotTable row : rows) {
            int enabled = row.getEnabled();
            int requireSsv = row.getRequireSsv();
            if ((enabled != 0 && enabled != 1) || (requireSsv != 0 && requireSsv != 1)) {
                log.error("[RewardedAd] invalid RewardedAdSlot row id={} reason=enabled_or_require_ssv_out_of_range",
                        Integer.toUnsignedString(row.getId()));
                allValid = false;
                continue;
            }
            SlotConfig cfg = new SlotConfig(
                    row.getId(),
                    enabled == 1,
                    row.getMinLevel(),
                    row.getDailyLimit(),
                    row.getCooldownSec(),
                    row.getMinWatchSec(),
                    row.getSessionTtlSec(),
                    row.getCurrencyType(),
                    row.getRewardAmount(),
                    requireSsv == 1,
                    row.getPlatformMask());
            String reason = RewardedAdRules.validateSlotConfig(cfg);
            if (reason != null || valid.containsKey(cfg.slotId())) {
                log.error("[RewardedAd] invalid RewardedAdSlot row id={} reason={}",
                        Integer.toUnsignedString(row.getId()), reason != null ? reason : "duplicate slot id");
                allValid = false;
                continue;
            }
            valid.put(cfg.slotId(), cfg);
        }
        List<SlotConfig> sorted = new ArrayList<>(valid.values());
        sorted.sort(Comparator.comparingInt(SlotConfig::slotId));
        this.slotsById = Collections.unmodifiableMap(valid);
        this.orderedSlots = List.copyOf(sorted);
        this.allRowsValid = allValid;
    }

    public static RewardedAdTables from(ConfigTables tables) {
        return new RewardedAdTables(tables.rewardedAdSlot().all());
    }

    public static RewardedAdTables empty() {
        return new RewardedAdTables(List.of());
    }

    public List<SlotConfig> slots() {
        return orderedSlots;
    }

    public SlotConfig slot(int slotId) {
        return slotsById.get(slotId);
    }

    public boolean allRowsValid() {
        return allRowsValid;
    }
}
