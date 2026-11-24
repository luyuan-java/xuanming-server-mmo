package com.game.match.script;

import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleScript;
import com.game.proto.BattleTeamScript;
import com.game.proto.eBattleScriptOrigin;
import com.game.proto.eBattleScriptSide;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 建房前每方战斗剧本解析器（对齐 Go {@code go/match/internal/battlescript/resolve.go} 与 {@code battle_script_gather.go}）。
 *
 * <p>优先级：选用者（队长优先，否则阵位第一）的选用（具体→通用） > 官方绑定 > 不带剧本；评分局自动忽略玩家剧本。
 * 读库或校验失败时 fail-open 退回官方绑定或不带剧本并打 ERROR 日志，不阻断正常开局。
 */
public final class BattleScriptResolver {

    private static final Logger log = LoggerFactory.getLogger(BattleScriptResolver.class);

    public record Choice(int matchMode, int battleConfigId, eBattleScriptOrigin origin,
                         int officialScriptId, long playerScriptId) {
    }

    public record Member(long playerId, boolean captain) {
    }

    public record Side(int teamIndex, boolean monster, List<Member> members) {
    }

    public record ApprovedScript(int revision, BattleScript script) {
    }

    public interface PlayerScripts {
        List<Choice> loadout(long playerId) throws Exception;
        Optional<ApprovedScript> approved(long scriptId, long ownerPlayerId) throws Exception;
    }

    public static final String OUTCOME_CHOSEN_OFFICIAL = "chosen_official";
    public static final String OUTCOME_CHOSEN_PLAYER = "chosen_player";
    public static final String OUTCOME_BOUND = "bound";
    public static final String OUTCOME_NONE = "none";
    public static final String OUTCOME_FELL_BACK = "fell_back";

    public record Resolution(BattleTeamScript script, String outcome, String reason) {
    }

    private final BattleScriptCatalog catalog;
    private final BattleScriptValidator.Tables tables;
    private final PlayerScripts players;

    public BattleScriptResolver(BattleScriptCatalog catalog, BattleScriptValidator.Tables tables, PlayerScripts players) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.players = players;
    }

    public static Optional<Choice> pickChoice(List<Choice> choices, int matchMode, int battleConfigId) {
        for (BattleScriptCatalog.ModeKey key : BattleScriptCatalog.lookupKeys(matchMode, battleConfigId)) {
            for (Choice c : choices) {
                if (c.matchMode() == key.matchMode() && c.battleConfigId() == key.battleConfigId()) {
                    return Optional.of(c);
                }
            }
        }
        return Optional.empty();
    }

    public static long chooser(List<Member> members) {
        for (Member m : members) {
            if (m.captain()) {
                return m.playerId();
            }
        }
        return members.isEmpty() ? 0L : members.getFirst().playerId();
    }

    /** 为一场对局解析所有参战方（含 PVE 怪物方）的冻结剧本列表。 */
    public List<BattleTeamScript> resolveForBattle(long battleId, int matchMode, int battleConfigId,
                                                   List<BattlePlayerSnapshot> playersList) {
        List<Member> team0 = new ArrayList<>();
        List<Member> team1 = new ArrayList<>();
        for (BattlePlayerSnapshot snap : playersList) {
            if (snap.getTeamIndex() == 0) {
                team0.add(new Member(snap.getPlayerId(), false));
            } else if (snap.getTeamIndex() == 1) {
                team1.add(new Member(snap.getPlayerId(), false));
            }
        }
        boolean pve = BattleScriptValidator.isPveMatchMode(matchMode);
        boolean officialOnly = BattleScriptValidator.isRatedMode(matchMode);
        List<Side> sides = new ArrayList<>(2);
        if (!team0.isEmpty()) {
            sides.add(new Side(0, false, team0));
        }
        if (!team1.isEmpty()) {
            sides.add(new Side(1, false, team1));
        } else if (pve) {
            sides.add(new Side(1, true, List.of()));
        }
        List<BattleTeamScript> out = new ArrayList<>(sides.size());
        for (Side side : sides) {
            Resolution res = resolveSide(matchMode, battleConfigId, side, officialOnly);
            if (OUTCOME_FELL_BACK.equals(res.outcome())) {
                log.error("[gather] Battle script choice not used, fell back battle={} team={}: {}",
                        Long.toUnsignedString(battleId), side.teamIndex(), res.reason());
            }
            if (res.script() != null) {
                out.add(res.script());
            }
        }
        return out;
    }

    public Resolution resolveSide(int matchMode, int battleConfigId, Side side, boolean officialOnly) {
        if (side.monster()) {
            return catalog.bound(matchMode, battleConfigId, eBattleScriptSide.BATTLE_SCRIPT_SIDE_MONSTER)
                    .map(s -> new Resolution(official(side.teamIndex(), s, 0L), OUTCOME_BOUND, ""))
                    .orElseGet(() -> new Resolution(null, OUTCOME_NONE, ""));
        }
        long chooserId = chooser(side.members());
        String reason = "";
        if (chooserId != 0L && players != null) {
            try {
                Chosen chosen = resolveChosen(chooserId, matchMode, battleConfigId, officialOnly);
                if (chosen != null) {
                    BattleTeamScript script = chosen.script().toBuilder()
                            .setTeamIndex(side.teamIndex())
                            .setChooserPlayerId(chooserId)
                            .build();
                    return new Resolution(script, chosen.outcome(), "");
                }
            } catch (Exception e) {
                reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
        Optional<BattleScript> bound = catalog.bound(matchMode, battleConfigId, eBattleScriptSide.BATTLE_SCRIPT_SIDE_PLAYER);
        if (!reason.isEmpty()) {
            return new Resolution(bound.map(s -> official(side.teamIndex(), s, 0L)).orElse(null), OUTCOME_FELL_BACK, reason);
        }
        return bound.map(s -> new Resolution(official(side.teamIndex(), s, 0L), OUTCOME_BOUND, ""))
                .orElseGet(() -> new Resolution(null, OUTCOME_NONE, ""));
    }

    private record Chosen(BattleTeamScript script, String outcome) {
    }

    private Chosen resolveChosen(long chooserId, int matchMode, int battleConfigId, boolean officialOnly) throws Exception {
        List<Choice> choices = players.loadout(chooserId);
        if (officialOnly) {
            choices = choices.stream()
                    .filter(c -> c.origin() == eBattleScriptOrigin.BATTLE_SCRIPT_ORIGIN_OFFICIAL)
                    .toList();
        }
        Optional<Choice> pickedOpt = pickChoice(choices, matchMode, battleConfigId);
        if (pickedOpt.isEmpty()) {
            return null;
        }
        Choice pick = pickedOpt.get();
        return switch (pick.origin()) {
            case BATTLE_SCRIPT_ORIGIN_OFFICIAL -> {
                BattleScript script = catalog.official(pick.officialScriptId())
                        .orElseThrow(() -> new IllegalStateException("official script " + pick.officialScriptId() + " no longer exists"));
                BattleScriptValidator.checkSide(script, eBattleScriptSide.BATTLE_SCRIPT_SIDE_PLAYER, tables);
                yield new Chosen(official(0, script, chooserId), OUTCOME_CHOSEN_OFFICIAL);
            }
            case BATTLE_SCRIPT_ORIGIN_PLAYER -> {
                ApprovedScript approved = players.approved(pick.playerScriptId(), chooserId)
                        .orElseThrow(() -> new IllegalStateException("player script " + pick.playerScriptId() + " not approved or missing"));
                BattleScript normalized = BattleScriptValidator.normalize(approved.script(), tables);
                BattleScriptValidator.checkSide(normalized, eBattleScriptSide.BATTLE_SCRIPT_SIDE_PLAYER, tables);
                normalized = normalized.toBuilder().setScriptId(0).build();
                yield new Chosen(BattleTeamScript.newBuilder()
                        .setScript(normalized)
                        .setOrigin(eBattleScriptOrigin.BATTLE_SCRIPT_ORIGIN_PLAYER)
                        .setPlayerScriptId(pick.playerScriptId())
                        .setPlayerScriptRevision(approved.revision())
                        .build(), OUTCOME_CHOSEN_PLAYER);
            }
            default -> throw new IllegalStateException("loadout has unknown origin " + pick.origin());
        };
    }

    private static BattleTeamScript official(int teamIndex, BattleScript script, long chooserId) {
        return BattleTeamScript.newBuilder()
                .setTeamIndex(teamIndex)
                .setScript(script)
                .setOrigin(eBattleScriptOrigin.BATTLE_SCRIPT_ORIGIN_OFFICIAL)
                .setChooserPlayerId(chooserId)
                .build();
    }
}
