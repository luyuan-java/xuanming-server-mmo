# combat（实时技能 / buff / 战斗状态、回合制战斗引擎与 battle 节点、宝宝）

mmorpg has two combat stacks. (1) Realtime combat in the scene (cpp/libs/services/scene/combat, combat_state, actor/action_state). It covers ReleaseSkill(84) validation, cast/recovery/channel timers, the 70/33 pushes, cooldowns, a full buff framework and damage settlement. In production, though, almost nothing past validation and the 70 broadcast ever runs: players never get SkillContextCompMap or CooldownTimeListComp attached, so damage, skill effects, cooldowns and recovery are all no-ops (only skill_test attaches them). (2) The real gameplay is a deterministic turn-based battle (梦幻/问道 style). Its pieces: a pure engine library (cpp/libs/services/battle); a separate battle node (cpp/nodes/battle) that clients reach over a second, ticket-authenticated direct TCP connection; and scene-side freeze/settlement (scene/battle/player_battle.cpp) with battle:lock/ctx in Redis, a persisted settlement ledger and a durable settlement outbox. The match service (Go) orchestrates it via PrepareBattle → CreateBattle → Confirm → Settlement. Monsters exist only as turn-battle units (DungeonTable.monster → MonsterTable). Their 'AI' is a random-enemy basic attack, and there is no realtime monster spawning or AI. Navmesh (Detour) gives snap-to-mesh on enter and raycast clamp on movement. Java today has only ListSkills, a thin ReleaseSkill (ownership check + 70 broadcast), a gate rejection of battle uplink, and a spawn point without navmesh. Everything else in this area is missing, including the HP/attribute base that combat depends on.

### skill-list-initial — 技能列表与初始技能发放
- mmorpg: cpp/nodes/scene/handler/rpc/player/player_skill_handler.cpp (ListSkills); cpp/libs/services/scene/player/system/player_skill.cpp (RegisterPlayer/SanitizeSkillList/HasSkill)
- client messages: 77 SceneSkillClientPlayerListSkills (C2S, ListSkillsResponse)
- tables: Class; Skill
- depends on: none
- behavior: skill_list is always present, even as an empty list (the robot uses it as its ready signal). On load, invalid or duplicate skill ids are removed. Then the skill arrays of every Class table row are merged in order (skipping 0, ids missing from the Skill table and duplicates), regardless of class. PlayerSkillListComp is persisted in mmorpg.
- internal: PlayerSkillListComp lives in player data. Java currently re-grants from the table on every scene entry and does not persist skills.
- java: done — xm-scene ClientRequestHandler.listSkills; GeneratedSceneTables.initialSkills (all Class rows, deduplicated); ScenePlayer.skills/hasSkill. Not persisted (accepted difference noted in a PARITY comment).
- size: S
- robot: robot_smoke (Go), xm-robot smoke
- hazards: Once skills become learnable they must be persisted. The baseline grants every class's skills to every player.

### skill-release-validation — 放技能校验链与 70 广播
- mmorpg: cpp/nodes/scene/handler/rpc/player/player_skill_handler.cpp (ReleaseSkill); cpp/libs/services/scene/combat/skill/system/skill.cpp (ReleaseSkill/CheckSkillPrerequisites/ValidateTarget/BroadcastSkillUsedMessage/LookAtTargetPosition); cpp/libs/services/scene/combat/skill/constants/skill.h
- client messages: 84 SceneSkillClientPlayerReleaseSkill (C2S, ReleaseSkillResponse); 70 SceneSkillClientPlayerNotifySkillUsed (S2C push)
- tables: Skill; SkillPermission; ActorActionState; ActorActionCombatState; Cooldown
- depends on: skill-list-initial; skill-cast-phases-interrupt; skill-cooldown; actor-action-combat-state; in-battle-gates
- behavior: Check order: (1) skill_table_id is 0, not in the Skill table, or not owned → 1001 kInvalidTableId; per a PARITY note, TRANSFER_ERROR_MESSAGE may blank it on the wire. (2) Caster in a turn battle (InBattleComp) → 7004 kSkillCannotBeCastInCurrentState. (3) ValidateTarget: a non-empty targeting_mode with target_id<=0 → 7001. This applies even to no-target and AOE skills, because the zero check runs before the per-mode loop. targeting_mode holds bit numbers (1<<bit), and the first recognised mode decides: NoTarget(bit0) or AOE(bit2) → OK; Targeted(bit1) → the target entity must exist and be a Player or Npc (else 7001), and a target in a turn battle → 7002 kSkillInvalidTarget. (4) Cooldown 7003. (5) Casting/recovery/channel phase checks 7000 (see cast-phases). (6) Level check (stub). (7) CheckBuff via SkillPermission. (8) CheckState via ActorActionState and ActorActionCombatState. (9) Item check (stub). On success: face the target (rotation is written but no dirty bit is set), then broadcast 70 SkillUsedS2C {entity, target_entity=[target_id], skill_table_id, position} to observers who can see the caster, excluding the caster. ReleaseSkillResponse carries the error tip. Target ids are scene entity ids.
- internal: Error tips go through TipInfoMessage in the global registry. The broadcast uses the AOI grid.
- java: partial — xm-scene ClientRequestHandler.releaseSkill: only the skillExists && hasSkill → 1001 check, then 70 is broadcast to the caster plus watchers via world.broadcastToSelfAndWatchers. Missing: target validation (7001/7002), in-battle 7004, phase, cooldown and state checks. The 70 recipients include self, an intentional difference recorded in PARITY.
- size: M
- robot: robot_smoke/stress (Go AI cast_skill 85%, targets visible entities); not exercised by xm-robot
- hazards: The zero-target check also rejects no-target and AOE skills; reproduce it or document a deliberate difference. All current Skill rows have targeting_mode [1,…] or [2,…], so every skill needs a valid visible Player or Npc target. LookAtPosition mutates the rotation without marking 66 dirty. Skill and targeting enums are bit numbers in the table but masks in code; IsSkillOfType compares 1<<bit.

### skill-cast-phases-interrupt — 施法前摇/后摇/引导相位与打断 33
- mmorpg: cpp/libs/services/scene/combat/skill/system/skill.cpp (SetupCastingTimer/HandleGeneralSkillSpell/HandleSkillRecovery/HandleChannel*/CheckTimerPhase/SendSkillInterruptedMessage); cpp/libs/services/scene/combat/skill/comp/skill_comp.h
- client messages: 33 SceneSkillClientPlayerNotifySkillInterrupted (S2C push); 84 ReleaseSkill (tip 7000)
- tables: Skill
- depends on: skill-release-validation; realtime-skill-damage
- behavior: Only General (skill_type bit 1) and Channel (bit 2) skills arm a CastingTimerComp, which fires after cast_point seconds. General: spell → effect → recovery timer (recovery_time) → finish. Channel: spell → channel_finish timer plus a RunEvery(channel_think) interval timer (the think handler is empty) → recovery. Passive, Toggle, Activate and BasicAttack skills get no timer and do nothing. CheckTimerPhase runs for the casting, recovery and channel components in turn. If a phase timer is active and the new skill is immediate=1: send 33 SkillInterruptedS2C {entity, skill_table_id = the NEW skill's id} to observers (not the caster), drop that cast's context, remove the timer and continue. If immediate=0 → 7000 kSkillUnInterruptible. Callbacks are no-ops when the caster is frozen (cross-zone handoff).
- internal: Per-entity timer components; callbacks capture entity and skill-instance id. Java would use scene-thread timers or frame-tick deadlines.
- java: missing — No timers or phases in xm-scene; 33 is never sent (SceneMessageIds has no notifySkillInterrupted).
- size: M
- robot: robot_smoke/stress count 33 pushes but assert nothing
- hazards: 33 reports the interrupting skill's id instead of the interrupted one; target_entity, reason_code and skill_id are never filled. Interrupting a channel removes ChannelFinishTimerComp but leaves the RunEvery ChannelIntervalTimerComp running. Several phases can each send a 33 on one release. In production the context map never exists, so the spell, effect and recovery steps do nothing; only the casting-timer gating (7000/33) is observable.

### skill-cooldown — 技能冷却(按 cooldown_id 分组)
- mmorpg: cpp/libs/services/scene/combat/skill/system/skill.cpp (StartCooldown/CheckCooldown); time/system/time_cooldown.h (CoolDownTimeMillisecondSystem)
- client messages: 84 ReleaseSkill (tip 7003 kSkillCooldownNotReady)
- tables: Skill; Cooldown
- depends on: skill-release-validation
- behavior: Cooldown is keyed by SkillTable.cooldown_id, so skills sharing an id share the cooldown. Duration comes from CooldownTable.duration in ms (500..20000). It starts on a successful release (before the cast point). Casting while still cooling → 7003.
- internal: CooldownTimeListComp {cooldown_table_id → start ms}, which is not persisted.
- java: missing — No cooldown code in xm-scene.
- size: S
- robot: none effective
- hazards: In the baseline this is dead in production: CooldownTimeListComp is never attached to players (only skill_test emplaces it), so StartCooldown and CheckCooldown are no-ops and 7003 never appears. Turning it on in Java changes what the robot observes: stress casts every 3s against 0.5–20s cooldowns. Decide on parity: either keep it off to match, or turn it on and register it in PARITY.

### actor-action-combat-state — 行为状态机与战斗状态(沉默等)及 66 combat_state_flags
- mmorpg: cpp/libs/services/scene/actor/action_state/system/actor_action_state.cpp; cpp/libs/services/scene/actor/action_state/constants/actor_state.h; cpp/libs/services/scene/combat_state/system/combat_state.cpp; cpp/libs/services/scene/combat_state/constants/combat_state.h; cpp/nodes/scene/handler/event/actor_combat_state_event_handler.cpp; cpp/libs/services/scene/actor/attribute/system/actor_attribute_calculator.cpp (ResetCombatStateFlags)
- client messages: 84 ReleaseSkill (state tips from table, e.g. 10000); 66 ScenePlayerSyncSyncBaseAttribute (ActorBaseAttributesS2C.combat_state_flags + entity_id)
- tables: ActorActionState; ActorActionCombatState; SkillPermission
- depends on: realtime-buff-effects
- behavior: Actions (UseSkill=0, JoinFollow, Mount, Unmount) are checked against current actor states (Combat=0, TeamFollow=1, Mounted=2) using ActorActionState rows. Modes: MutualExclusion=0 → returns state_tip; Permitted=1; Interrupt=2 → fire InterruptCurrentStateEvent and remove that state. A successful UseSkill adds the Combat state, which is never removed. Combat states (only Silence=1 today) come from buff sources: CombatStateAddedEvent/RemovedEvent maintain a map state → set of source buff ids. ValidateSkillUsage looks up ActorActionCombatState[action].state[stateKey], and MutualExclusion(=1) returns state_tip. CheckBuff additionally looks up SkillPermission[state].skill_type[skillTypeBit] and returns that cell (1000 = allow). Any change to the combat-state set marks kCombatState, so the next 66 carries combat_state_flags {state → true} plus entity_id.
- internal: Pure in-memory components, not persisted. The only consumer of ActorActionState in the baseline is skill CheckState.
- java: missing — Java 66 carries only transform and velocity (ScenePlayer DIRTY_TRANSFORM/DIRTY_VELOCITY); there are no action or combat states.
- size: M
- robot: none
- hazards: With current data, silence blocks nothing. ActorActionCombatState row 0 marks Silence as mutual-exclusion but its tip is 1000 (= kSuccess), and SkillPermission row 1 is 1000 everywhere. Several rows have state_tip=0 under mutual exclusion; the realtime path treats 0 as an error (0 != kSuccess), while the turn engine maps an unfilled 0 to kInvalidTableData. Iteration needs a snapshot because the Interrupt mode erases while iterating (a fixed C++ bug). The Combat state is never cleared.

### table-expression-evaluator — 配表字符串公式求值(伤害/回血/附加伤害)
- mmorpg: cpp/libs/engine/config/table_expression.h (exprtk ExcelExpression); generated table managers: SkillTableManager::SetDamageParam/GetDamage, BuffTableManager::SetHealthRegenerationParam/GetHealthRegeneration/GetBonusDamage; tools/data_table_exporter/core/schema.py (expression_params)
- client messages: none
- tables: Skill; Buff
- depends on: none
- behavior: Formula columns: Skill.damage ('100*level', '1000*level', '10000*level'; param level); Buff.health_regeneration ('0.013*level*health'; params level and lost health); Buff.bonus_damage ('66'). Parameter names come from the Excel header. exprtk also registers a random() function based on C rand().
- internal: Java needs a formula evaluator. xm-table/src/main/java/com/game/table/** is a sync artifact that must not be hand-edited, and the Java template in the mmorpg exporter does not emit expression support. So either extend the mmorpg Java generator or wrap the evaluator in xm-scene/xm-battle. Library choice must follow the ≥20k-star rule; Spring SpEL counts as built-in, or write a tiny parser.
- java: missing — SkillTableManager.java/BuffTableManager.java expose only find/exists/index methods; there is no getDamage.
- size: S
- robot: none directly (battle_smoke via skill damage)
- hazards: random() in a formula would break the turn engine's determinism (seeded RNG only). Reject or forbid random() in battle-side evaluation. The table manager's SetParam-then-Get pair keeps shared mutable state; Java should evaluate as a pure function.

### combat-damage-rules — 共用伤害公式(比例减伤)
- mmorpg: cpp/libs/services/battle/system/combat_damage_rules.h; cpp/tests/turn_battle_engine_test/combat_damage_rules_test.cpp
- client messages: none
- tables: none
- depends on: none
- behavior: raw = base*(1+strength*0.1) + attack*mult, where mult 0 → 1 and negative or non-finite → 0. received = max(0.4, K/(armor+defense+K)*(1-resist%)) with K = 360+120*clamp(level,1..85). base<=0 or non-finite → 0, so pure-utility skills never deal damage. damage_type 0 = magic attack, 1 = physical, anything else → attack 0. DamageToHealth = ceil, capped at current HP; NaN/Inf/<=0 → 0. Sums are done in double to avoid uint64 overflow. Crit chance is an integer percent clamped to [0,1] and doubles the damage. PVP multiplies by 0.3 (turn engine only).
- internal: Pure functions with unit tests; port them as a Java utility shared by the realtime and battle code.
- java: missing — none
- size: S
- robot: battle_smoke / features_smoke (indirect)
- hazards: Defense-unit ×12 scaling is coupled to Class.init_armor and Monster.armor. kLevelFactorMaxLevel=85 must match the player level cap.

### realtime-skill-damage — 实时技能结算(上下文/伤害/死亡事件)
- mmorpg: cpp/libs/services/scene/combat/skill/system/skill.cpp (CreateSkillContext/AddSkillContext/HandleSkillSpell/CalculateSkillDamage/CalculateFinalDamage/DealDamage/HandleTargetDeath/TriggerSkillEffect); cpp/nodes/scene/handler/event/combat_event_handler.cpp (BeKillEvent stub); cpp/nodes/scene/handler/event/skill_event_handler.cpp (SkillExecutedEvent stub)
- client messages: none
- tables: Skill; Buff
- depends on: skill-cast-phases-interrupt; combat-damage-rules; table-expression-evaluator; realtime-buff-core; death-revive
- behavior: At the cast point: target invalid or in a turn battle → drop the whole skill. Damage = Skill.damage(level=caster level) through the shared formula, using DerivedAttributes attack and defense, critchance as a percent and a 2x crit. A frozen target takes no damage. A dead target (health 0) is skipped. Buff hooks run before and after give/take damage. HP drops (ceil, capped). At 0 HP: OnBeforeDead/OnAfterDead/OnKill stubs, then BeKillEvent (handler empty). Each Skill.effect buff id is applied to the target, and SkillExecutedEvent fires (handler empty). ApplySkillHitEffectIfValid runs at release time and resets the target's combat-idle buff.
- internal: SkillContextComp maps (caster plus mirrored target). HP changes have no S2C channel: 66 carries no health field.
- java: missing — Java has no HP or attributes on ScenePlayer.
- size: M
- robot: none effective
- hazards: Dead in the production baseline: SkillContextCompMap is never emplaced on players, so the context lookup fails and no damage or effect ever lands. If Java implements it, players' HP would drop invisibly under robot stress (no HP sync, no death handling) until relogin revive, and 0-HP players are refused by PrepareBattle. Recommend keeping it unported until there is a design for HP sync and death, or registering a PARITY difference.

### realtime-buff-core — 实时 buff 框架(增删/叠层/驱散/免疫/子 buff/到期/周期)
- mmorpg: cpp/libs/services/scene/combat/buff/system/buff.cpp; cpp/libs/services/scene/combat/buff/system/buff.h; cpp/libs/services/scene/combat/buff/comp/buff_comp.h; cpp/libs/services/scene/combat/buff/constants/buff.h; cpp/libs/services/scene/world/world.cpp (BuffSystem::Update each frame)
- client messages: none
- tables: Buff
- depends on: table-expression-evaluator
- behavior: AddOrUpdateBuff steps: entity valid → drop silently if frozen or in battle → row exists → immunity (any existing buff's immune_tag covers a tag of the new buff → 8001 kBuffTargetImmuneToBuff) → dispel existing buffs whose tag hits the new dispel_tag (a pure Dispel type-35 buff is consumed and not added) → same table and same processed_caster gives layer+1 up to max_layer, with refresh as a TODO → otherwise a new entry. duration>0 schedules an expiry timer; duration==0 expires immediately; infinite_duration keeps it. Sub-buffs are added to the holder; target_sub_buff goes to the other side. Periodic: per-frame accumulator, interval seconds, interval_count limit (0 = unlimited), at most 5 ticks per frame, re-lookup after each callback. Frozen entities are excluded from ticking.
- internal: BuffListComp is a map buffId → entry. Buff ids come from an id generator. Buffs are not persisted in the baseline, though the battle snapshot reads them.
- java: missing — none
- size: L
- robot: none
- hazards: Callbacks can re-enter and erase the current buff, so always re-look it up by id (several fixed C++ use-after-free bugs). OnBuffRefresh is a TODO, so duration is not reset on restack in realtime, while the turn engine does reset it. Buffs have no S2C at all, so this is invisible to clients. Current data: Skill.effect lists buff 1 eight times; buff 1 has duration 0 (instant) and dispel_tag Control, which matches its own tag.

### realtime-buff-effects — buff 效果实现(沉默/隐身/移速/下次普攻/脱战增益/按损血回血)
- mmorpg: cpp/libs/services/scene/combat/buff/system/buff_impl.cpp; cpp/libs/services/scene/combat/buff/system/modifier_buff_impl.cpp; cpp/libs/services/scene/combat/buff/system/motion_modifier_impl.cpp (all empty); cpp/libs/services/scene/spatial/system/view.cpp (IsStealthed); cpp/libs/services/scene/actor/attribute/system/actor_attribute_calculator.cpp (UpdateMoveSpeed)
- client messages: 66 combat_state_flags (silence); 21/47/64 visibility (stealth)
- tables: Buff
- depends on: realtime-buff-core; actor-action-combat-state
- behavior: Silence (31): start/destroy fire CombatStateAdded/Removed. Stealth (33): maintains StealthedTagComp, and the AOI hides stealthed targets. Move speed boost/reduction (13/0): recomputes MoveSpeedComp = sum(boost) - sum(reduction), floored at 0, with no S2C and no effect on movement validation. NextBasicAttack (36): on the next given damage, adds bonus_damage, removes itself and applies sub_buff and target_sub_buff. NoDamageOrSkillHitInLastSeconds (43): each interval, if idle ≤ combat_idle_seconds it adds sub-buffs once; taking damage or being hit by a skill resets the timer and strips the sub-buffs. HealthRegenerationBasedOnLostHealth (42): heals per interval by formula(level, lost HP), capped at max_health.
- internal: Hooks on the buff lifecycle and damage events.
- java: missing — none
- size: M
- robot: none
- hazards: The idle-time check compares milliseconds against combat_idle_seconds (a unit mismatch in the baseline). Regen uses get<> on derived attributes and level; in C++ it would throw or UB if they are missing. Stun and freeze have no realtime implementation; they only exist in the turn engine.

### death-revive — 死亡与复活(基础复活规则)
- mmorpg: cpp/libs/services/scene/player/system/player_revive.h; player_database_loader.cpp (ReviveBaseAttributesIfDead on load); cpp/libs/services/scene/battle/system/player_battle.cpp (settlement revive, PrepareBattle 0-HP reject); cpp/libs/services/scene/combat/skill/system/skill.cpp (HandleTargetDeath stubs)
- client messages: none
- tables: Class
- depends on: player base attributes / attribute-allocation (other area)
- behavior: A brand-new character (health, strength and speed all 0) gets the Class init_* stats plus full HP/MP. A dead character (health 0 with stats present) on login, or after a battle settlement with is_dead or health 0, is restored to full HP/MP at the DerivedAttributes max, falling back to Class init values. Alive characters are untouched, so they keep reduced HP after battle. PrepareBattle rejects players at 0 HP with kFeatureUnavailable 1006. Realtime death has no state, no client notification and no revive flow.
- internal: Pure rule plus ECS entry points.
- java: missing — PlayerData/ScenePlayer have no HP, MP or base attributes.
- size: S
- robot: battle_smoke (implicit: repeated battles need a revived player)
- hazards: Revive must use the derived max, not the Class init value, or level growth is lost (a C++ bug fix). class_id is not threaded through, so the first Class row is used.

### realtime-npc-monster-ai — 实时场景 NPC/怪物刷新与 AI(基线未实现)
- mmorpg: cpp/nodes/scene/handler/event/npc_event_handler.cpp (stub); cpp/libs/services/scene/spatial/system/scene_crowd.cpp (dtCrowd agent, unused); cpp/libs/services/scene/spatial/constants/aoi_priority.h (kBoss/kQuestNpc tags only); cpp/libs/services/scene/spatial/system/nav_query.cpp FindPath (no callers)
- client messages: 21/47 with ActorType NPC would be used if it existed
- tables: Monster
- depends on: navmesh-queries
- behavior: Nothing observable: no NPC or monster entities are ever created in scenes. InitializeNpcCompsEvent only validates the entity. dtCrowd agents are added through tlsEcs.sceneRegistry.try_get using the player entity (wrong registry), so nothing happens; leaving the scene is a TODO.
- internal: none
- java: not_applicable — The baseline has stubs only; nothing to port. Monsters exist only inside the turn-battle engine (see turn-battle-engine-core).
- size: S
- robot: none
- hazards: Do not port the dtCrowd code, which uses the wrong registry and never removes agents. If realtime monsters are ever designed, do it in mmorpg first.

### navmesh-queries — 导航网格加载与查询(吸附/射线夹持/寻路)
- mmorpg: cpp/libs/services/scene/spatial/system/navigation.cpp (LoadNavBins + spawn probe); cpp/libs/services/scene/spatial/system/nav_query.cpp/.h (SnapToMesh/ValidateMove/FindPath, axis swap); cpp/libs/services/scene/spatial/system/recast.cpp; cpp/libs/services/scene/spatial/manager/scene_nav.h; cpp/libs/services/scene/spatial/comp/nav_comp.h; cpp/libs/services/scene/spatial/constants/nav.h; cpp/libs/services/scene/spatial/system/scene_spawn.cpp (EnsureValidEnterLocation/ResolveSpawnOnMesh/FallbackLocationForPlayer)
- client messages: 79/21 enter position (relocation); 137 SceneMovementClientPlayerNotifyMoveAck (clamp correction > 0.5 m)
- tables: BaseScene
- depends on: none
- behavior: Loads Detour .bin files per BaseScene.nav_bin_file (6 distinct files, 21 rows); each file is loaded once. A scene's nav is registered only if its BaseScene spawn snaps onto the mesh; otherwise the scene has no nav (fail-open, movement unchecked). On scene entry, a position off the mesh, or (0,0,0), is relocated to the spawn snapped onto the mesh; on mesh, only height is snapped; a map change always uses the spawn. Movement raycasts from the server position to the reported one and clamps to the hit point pulled back 0.05 m, then sends 137 when horizontal deviation exceeds 0.5 m. Snap extents are ±2 m horizontal and ±4 m vertical. Server Z-up ↔ nav Y-up axis swap: nav = (y, z, x).
- internal: Recast/Detour C++ library; thread-local manager; NavComp is non-copyable.
- java: missing — Java uses a MoveGuard token bucket (12 m/s, 24 m cap) and GeneratedSceneTables.spawnPoint without snapping. The PARITY row 移动位移校验 documents this deliberate alternative.
- size: L
- robot: robot movement scenarios (Go); xm-robot movement (Java, without nav)
- hazards: The Java Detour port (recast4j) is far below the ≥20k-star rule. Options: keep MoveGuard and register the difference, write a minimal Detour .bin reader with findNearestPoly and raycast yourself, or run nav out of process. The nav bins are not synced by ContractSync today (config-data/tables has no .bin files). Stale UE placeholder bins are rejected by the spawn probe; keep that guard.

### turn-battle-engine-core — 回合制引擎核心(开局/行动收集/出手序/普攻防御逃跑/胜负/快照)
- mmorpg: cpp/libs/services/battle/system/turn_battle_engine.cpp/.h (Initialize/InitPlayers/InitPets/InitMonsters/SubmitAction/ValidateAction/SetActorAuto/AllPlayersReady/ResolveCurrentRound/FillDefaultActions/BuildTurnOrder/ExecuteAttack/ExecuteDefend/ExecuteFlee/HandleDeath/UpdateOutcome/BuildStateSnapshot/RandIndex/Rand01); cpp/libs/services/battle/constants/turn_battle_constants.h; cpp/libs/services/battle/data/battle_data_provider.h, table_battle_data_provider.cpp
- client messages: 139 NotifyTurnResult payload (TurnResultS2C: events, state, action_order); BattleStateS2C/BattleActorState/BattleEventItem shapes (battle_data.proto)
- tables: Dungeon; Monster; Skill; SkillPermission; Buff; Cooldown; Item
- depends on: combat-damage-rules; table-expression-evaluator
- behavior: Initialize: battle_id≠0; at least one player; ≤5 players per team; player_id must have bit 63 clear and be unique; team_index ≤1. Pets are appended after players (actor_id = bit63|2<<32 + n, is_auto=true). For PVE only (match_mode 4/5), monsters come from DungeonTable.monster, or one default monster (HP 300, speed 60) per player if the group is empty; they get actor_id = bit63|1<<32 + i, team 1, name '野怪', level = the highest player level. Both sides must have units. Snapshot buffs are sanitized: drop stun, freeze, silence and instant buffs, remap unknown casters to 0, clamp duration. maxRounds = ceil(Dungeon.time_limit s / 6 s), default 30. Formation slot = insertion order within the team (0–4 front row). Players only submit actions; the latest submission before resolve wins; invalid ones are not recorded. Resolve: default ATTACK (target 0) for everyone not submitted, including monsters and pets. Order is speed descending, then actor_id ascending, fixed at round start (action_order includes units skipped mid-round). Units stunned or frozen mid-round lose their action without an event. ATTACK targets a random alive enemy when its target is invalid or on the same team. Basic attack base damage 10, physical, multiplier 1. DEFEND halves damage until round end, including DoT. FLEE: PVE only, chance clamp(0.5 + (speed - max enemy speed)/1200, 0.05, 0.95). Death clears buffs, emits a DEATH event and records the kill only for a team-0 player or pet killing a team-1 monster. Outcome: both wiped = DRAW; one side wiped; roundIndex ≥ maxRounds → SIDE_B_WIN (the attacker loses). Event group_id/hit_index rules (D2). Hit rate is fixed at 100 with no RNG used. RNG = mt19937_64(seed); RandIndex = rng() % n; Rand01 = (rng()>>11)·2^-53.
- internal: Pure deterministic library with no I/O, using proto messages as state. Java should be a plain library module (for example xm-battle-engine) with an injectable data provider and replay tests.
- java: missing — none
- size: L
- robot: battle_smoke (PVE solo + auto), battle_smoke_cross_zone (1v1 PVP auto), features_smoke (PVE1 victory)
- hazards: Determinism: never use other RNGs or iterate unordered maps in order-sensitive paths. An exact mt19937_64 port is needed only for cross-language replay parity. Time limits: Dungeon time_limit of 1800 s means 300 rounds, so in practice the match deadline (forced DRAW) ends long battles before the max-rounds rule. The max-rounds rule makes side A lose even in PVP. Pets carry skill ids but always basic-attack; monsters never use skills. NextFormationSlot counts every unit of the team.

### turn-battle-skills-buffs — 回合制技能/耗蓝/冷却回合/buff 回合化
- mmorpg: cpp/libs/services/battle/system/turn_battle_engine.cpp (CheckActionPrerequisites/ValidateSkillTarget/CheckCooldown/CheckBuff/CheckState/CheckSkillCost/IsTurnBattleCastableSkill/ExecuteSkill/ApplySkillToTarget/ConsumeSkillMana/AddBuffToActor/StackOrRefreshExistingBuff/DispelBuffsByTag/IsImmuneToBuff/TickActorBuffs/ApplyBuffIntervalEffect/DecayCooldowns/SanitizeSnapshotBuffs)
- client messages: 149 SubmitBattleAction tips: 1001, 1005, 1009, 7001, 7003, 7004, 7006, 1002; 139 events SKILL/DAMAGE/MANA/BUFF_ADD/BUFF_REMOVE/BUFF_TICK/MISS
- tables: Skill; Buff; Cooldown; SkillPermission
- depends on: turn-battle-engine-core; table-expression-evaluator
- behavior: Validation order: dead or fled → 1009; stunned or frozen → 7006. Skill row missing → 1001. Not in the actor's skill list → 1005. Passive, toggle or channel skill (blacklist) → 7004. Target check: non-empty targeting_mode with target 0 → 7001; for a targeted skill the target must be alive and not fled → 7001. Same cooldown_id still cooling → 7003. Silenced → SkillPermission[1].skill_type[bit]; a cell of 0 → 1002. Mana (cost_resource id 1 only) above current → 7004. At execution the checks run again; on failure the action falls back to a basic attack. AOE (targeting bit 2) hits all alive enemies in insertion order without using RNG. An invalid single target is re-picked at random. Cooldown is set to ceil(Cooldown.duration ms / 6000) rounds, keyed by skill id, and drops by 1 at round end. A MANA event is emitted when cost > 0. Damage base is evaluated once from the formula with the caster level, then the shared formula is applied with Skill.damage_type and attack_multiplier, ×0.3 in PVP, plus crit. Effect buffs go only onto targets still alive. Buffs: immunity → dispel → pure dispel skipped → stack (no_caster, or same caster: layer+1 up to max, refresh remaining rounds) → new entry (remain = ceil(duration s/6 s), infinite → 0, instant → added then removed). Sub-buffs recurse to depth ≤8; target_sub_buff goes to the caster. Round-end tick per active unit (one group each): regen types 40/42 heal by formula; poison/burn 50/51 deal interval_effect[0]·layer (×0.5 if defending), may kill and stop that unit's tick; remain 1 → removed, else decremented.
- internal: Inside the engine.
- java: missing — none
- size: L
- robot: battle_smoke (auto: monsters, pets and auto players only basic-attack, so skills are mostly not exercised)
- hazards: The interval tick alignment differs for finite buffs (elapsed rounds) and infinite ones (global round index). The buff-instance id counter starts above the highest snapshot id. Snapshot caster ids are scene entity ints and must be remapped (G5). Insufficient mana reuses tip 7004 (no dedicated tip). The cooldown map is keyed by skill id while grouping is by cooldown_id via reverse lookup.

### turn-battle-items-drops-rewards — 回合制道具/掉落/经验金币结算数据
- mmorpg: cpp/libs/services/battle/system/turn_battle_engine.cpp (CheckItemUse/ExecuteItem/RollDrops/BuildSettlement/SelfItems/FindItemEntry)
- client messages: 149 SubmitBattleAction ITEM tips: 1001, 1005, 6007 kBagInsufficientItems, 7001, 7002; 139 events ITEM; 150 BattleEndS2C.settlement (exp_gain, gold_gain, items_consumed, items_gained, defeated_monsters, pets, health, mana, is_dead, fled, total_rounds); BattleStateS2C.self_items
- tables: Item; Monster; Dungeon
- depends on: turn-battle-engine-core
- behavior: ITEM checks: the Item row must exist (else 1001); battle_usable must be ≠0 and battle_heal_hp or battle_heal_mp must be >0 (else 1005); the snapshot copy must have count >0 (else 6007); PVP allows at most 5 item uses per player per battle (else 1005); the target must be self (target 0) or an alive teammate (else 7001/7002). At execution, an item aimed at an invalid target falls back to self-use. It never becomes an attack. One unit is consumed even if the heal is 0 at full HP. HP and MP can both be healed. Drops are rolled only on SIDE_A_WIN: for each eligible player (team 0, not fled, not dead) × each killed monster in kill order × each drop slot, with drop_rate/10000. Exp and gold = sums of exp_reward and gold_reward over killed monsters, given in full to each eligible player. defeated_monsters is copied for mission progress. BuildSettlement is const and safe to call repeatedly.
- internal: Item counts exist only in the engine's copy of the CreateBattleRequest.
- java: missing — none
- size: M
- robot: features_smoke (PVE1 victory → mission 12 kill progress)
- hazards: Drop rolls must come after all combat RNG use, or replay baselines shift. Every defeated_monsters entry has count=1 (do not multiply). Drops and exp are only meaningful together with scene-battle-settlement-apply.

### battle-table-fingerprint — 战斗配表指纹
- mmorpg: cpp/libs/services/battle/data/battle_table_fingerprint.cpp/.h
- client messages: none
- tables: Skill; Buff; Cooldown; SkillPermission; Dungeon; Monster; Item
- depends on: none
- behavior: sha256 over the deterministic serialization of 7 tables in fixed order (skill, buff, cooldown, skillpermission, dungeon, monster, item), each section prefixed with the table name, a NUL byte and an 8-byte big-endian length. The hex digest is truncated to 32 characters. The scene puts it in the snapshot and in PrepareBattleResponse; match checks all values are equal; battle compares at CreateBattle per battle_table_fingerprint_mode (warn/enforce/off, default warn); a mismatch under enforce → kFeatureUnavailable with a message parameter.
- internal: Cached, refreshed on table load.
- java: missing — none
- size: S
- robot: none
- hazards: Only needs to be consistent inside Java, since Java does not mix with C++ nodes. Java protobuf deterministic serialization may not byte-match C++ for map fields, so do not compare fingerprints across languages.

### battle-room-lifecycle — battle 节点房间生命周期(建房/回合计时/整场期限/收尾/作废/确认补发/对局结果)
- mmorpg: cpp/nodes/battle/logic/battle_room_manager.cpp/.h (HandleCreateBattle/HandleDestroyBattle/ArmRoundTimer/ResolveRound/OnBattleDeadline/ResendBattleConfirmed/FinishBattle/AbortAllRooms); cpp/nodes/battle/battle_room_table.h; cpp/nodes/battle/handler/grpc/battle_node.cpp; cpp/nodes/battle/main.cpp
- client messages: 143 BattleClientPlayerNotifyBattleStart (S2C, lobby announcement); 139 NotifyTurnResult (direct); 150 NotifyBattleEnd (direct); internal: 146 BattleNodeCreateBattle, 147 BattleNodeDestroyBattle
- tables: Dungeon
- depends on: turn-battle-engine-core; battle-direct-connect-edge; battle-ticket-assignment; battle-settlement-outbox; battle-table-fingerprint; match service (other area)
- behavior: CreateBattle is idempotent on battle_id. It rejects with 1005 when routing instance ids are empty, kFeatureUnavailable on a fingerprint mismatch under enforce, and 1002 when the engine init fails. Missing deadline_ms → now + 32·6 s. Before inserting the room, it pre-signs a ticket for every participant; any failure → 1003 with no side effects. Then it starts the battle timer and the first round timer. For each player: send 177 Assigned, then a per-player 143 BattleStart (state redacted, self_items, action_deadline_ms), and a BattleConfirmedEvent to the scene, resent every 10 s for 180 s. The round window is 6000 ms, or 2000 ms when every alive player is on auto; when all players are ready the round resolves early. After each resolve, the next round timer is armed and 139 goes to every participant (redacted per viewer) and to observers. Battle over: per player, 150 BattleEnd (direct), then durable settlement dispatch; SpectateEnd(FINISHED) to observers; close direct connections; send BattleResultEvent to match-results (or the durable activity channel when activity_context.kind≠NONE). The battle deadline forces outcome DRAW, and observers get ABORTED. Destroy and abort (on shutdown) send no settlement; observers get ABORTED; connections close.
- internal: Single-threaded rooms in memory; a crash voids every battle, and scene reapers recover. The baseline uses Kafka producers to scene, gate and match-results. Java would use Dubbo or Redis pub/sub for scene and gate pushes and its own event channel for match.
- java: missing — No xm-battle module; the gate routes the battle domain to unsupported.
- size: L
- robot: battle_smoke, battle_smoke_cross_zone, features_smoke
- hazards: ResolveRound can erase the room, so never touch the room afterwards (the response must be filled before). Timer callbacks re-look up rooms by id. The forced DRAW is stamped by the node, because the engine outcome is still ONGOING (no rewards). After the scene has seen Confirm, it refuses CancelBattlePrepare, so a Destroy after confirm leaves players frozen until the reaper deadline. The room limit and Agones-unit accounting must fire exactly once per insert and remove.

### battle-client-actions-push — 参战者客户端协议(提交行动/拉状态/自动战斗/视角裁剪)
- mmorpg: cpp/nodes/battle/logic/battle_room_manager.cpp (HandleSubmitBattleAction/HandleGetBattleState/HandleSetAutoBattle/RedactStateForViewer/FillSelfItems/BroadcastTurnResult); proto/battle/player_battle.proto
- client messages: 149 BattleClientPlayerSubmitBattleAction (C2S); 140 BattleClientPlayerGetBattleState (C2S → BattleStateS2C); 162 BattleClientPlayerSetAutoBattle (C2S); 139 NotifyTurnResult (S2C); 143 NotifyBattleStart (S2C); 150 NotifyBattleEnd (S2C)
- tables: none
- depends on: battle-room-lifecycle; battle-direct-connect-edge
- behavior: Identity comes only from the verified ticket. Submit: player_id 0 → 1012; room missing or player not a participant → 1005; otherwise the engine's ValidateAction tip (0 is mapped to 1005); success leaves error_message unset. A submit that makes everyone ready resolves the round immediately. GetBattleState: a non-member or missing room gets an empty BattleStateS2C (battle_id 0, meaning the client drops its battle UI). Otherwise a full snapshot with action_deadline_ms, other actors' skill_cooldown_rounds cleared (own unit and own pet keep theirs), and self_items only for participants (observers see all cooldowns cleared). SetAutoBattle: 1012/1005, engine 1005 or 1009 for dead or fled; if this call turns the room ready, resolve immediately; repeated calls on an all-auto room do not speed it up. Pushes are sent only on the direct connection: with no live direct connection a battle frame is dropped (sampled log), and the client must call GetBattleState after reconnecting.
- internal: Per-viewer copies of the state.
- java: missing — none
- size: M
- robot: battle_smoke (GetBattleState after direct connect, SetAutoBattle), features_smoke
- hazards: The engine snapshot is all-knowing: forgetting to redact on any path (start, turn, get-state, spectate) leaks opponents' cooldowns and items. Do not let SetAutoBattle toggling break the 2 s auto rhythm.

### battle-direct-connect-edge — 战斗直连面(第二条 TCP、票据握手、安全闸)
- mmorpg: cpp/nodes/battle/client/battle_client_edge.cpp/.h; cpp/nodes/battle/battle_security.h; cpp/nodes/battle/tests/battle_ticket_test.cpp
- client messages: BattleTokenVerifyRequest / BattleTokenVerifyResponse (handshake frame types, not message ids); ClientRequest/MessageContent envelopes carrying 149/140/165/162
- tables: MessageLimiter
- depends on: battle-ticket-assignment; battle-room-lifecycle
- behavior: Same frame codec as the gate (ProtobufCodec). Gates, in order: (1) connection cap battle_max_connections (0 → hard cap 65535, dev/test only), force-close when over; (2) empty secret in prod → refuse; (3) close if no handshake within 10 s; (4) any non-handshake message before verification → close without reply; (5) ClientRequest > 1024 B → envelope tip 1010 plus an illegal-packet count; per-message-id rate limit (MessageLimiter table) → envelope tip plus count; only 149/140/165/162 are allowed (else 1005 plus count); body parse failure → 1005 plus count; illegal-packet threshold (default 50) → close. Handshake: HMAC-SHA256 hex over the payload bytes (signature check skipped for dev/test with an empty secret); payload must parse; field checks in order: empty ids → node_id mismatch → instance uuid mismatch → expired (≤ now) → bad role. Then attach to the room (role-specific roster); failure → reply success=false with an error string, shutdown and force-close after 0.1 s. A repeated handshake gets success again. A reconnect replaces the old connection, and detach only removes the connection it matches. Observers get SpectateState right after the handshake reply.
- internal: weak_ptr to connections, 2 MB high-water mark. Java: a Netty server on its own port reusing xm-net ClientFrameDecoder/Encoder.
- java: missing — xm-net ClientFrameDecoder/Encoder could be reused; no battle edge exists.
- size: M
- robot: battle_smoke, battle_smoke_cross_zone, features_smoke (all dial the direct connection)
- hazards: Unlike the gate, an empty secret in dev still requires the handshake packet. The battle secret must be ≥32 bytes and differ from the gate secret in prod. Closing must happen after the in-flight reply has been written (queue the close for after this loop iteration), or the final reply is lost.

### battle-ticket-assignment — 战斗票据签发与落点分配(含补签)
- mmorpg: cpp/nodes/battle/logic/battle_room_manager.cpp (BuildAssignment/PushAssignment/PushLobbyAnnouncement/HandleIssueBattleTicket); cpp/nodes/battle/battle_push_policy.h; node/system/node/client_endpoint.h (ClientFacing)
- client messages: 177 BattleClientPlayerNotifyBattleAssigned (S2C lobby announcement); 179 MatchServiceRequestBattleTicket (C2S via match; response RequestBattleTicketResponse); internal 178 BattleNodeIssueBattleTicket
- tables: none
- depends on: battle-room-lifecycle; match service (other area)
- behavior: BattleTicketPayload {battle_id, player_id, battle_node_id, battle_instance_id (uuid), expire_at_ms = room deadline, role}. The signature is the hex HMAC (empty when the dev secret is empty). The assignment has the client-facing host and port (client_endpoint, else endpoint; required=1 with no endpoint → refuse to sign). Lobby announcements (177 and 143 only) go over the direct connection if one is live, otherwise through the gate to the lobby session. Battle frames never fall back. Reissue: only for a current participant or observer; otherwise 1005 with no assignment; a signing failure → 1003. A ticket can be reused for reconnects until the room deadline.
- internal: Baseline: Kafka gate-cmd PushToPlayerEvent with target gate id and instance. Java needs a battle-to-gate push path; today the Java gate only links to the scene.
- java: missing — none
- size: M
- robot: battle_smoke (observer assignment), features_smoke
- hazards: The client has to receive 177 before 143 (same key ordering). Assignment and routing come from snapshot routing taken at prepare time; a session change during PREPARING means a fresh 144 has to be pushed (see scene-battle-freeze).

### battle-spectate — 观战(加入/移除/退出/首帧/回合/结束)
- mmorpg: cpp/nodes/battle/logic/battle_room_manager.cpp (HandleAddObserver/HandleRemoveObserver/HandleStopWatchBattle/PushSpectateState/NotifySpectateEndAndClose/OnDirectConnectionVerified)
- client messages: 165 BattleClientPlayerStopWatchBattle (C2S direct); 161 NotifySpectateState (S2C direct); 158 NotifySpectateTurnResult (S2C direct); 166 NotifySpectateEnd (S2C direct); 177 NotifyBattleAssigned role=OBSERVER; internal 160 BattleNodeAddObserver, 159 BattleNodeRemoveObserver; client 163 MatchServiceWatchBattle, 164 ListWatchableBattles (match area)
- tables: none
- depends on: battle-room-lifecycle; battle-direct-connect-edge; battle-ticket-assignment
- behavior: AddObserver errors: room missing → 1004 (match uses it to evict the index); observer 0 or no gate instance → 1005; the observer is a participant → 1005; 20 observers already → 1008; signing failure → 1003, and an already-registered observer is removed and closed. The add is idempotent: the same session re-pushes the assignment and state; a changed session updates routing, closes the old direct connection and re-pushes the assignment. The first frame (161 with observer_count) is sent only when the direct handshake completes. Each round, observers get 158 with all cooldowns redacted. On end: 166 with reason FINISHED(1), ABORTED(2) or REMOVED(3) plus the outcome (ONGOING when aborted), then the connection closes. StopWatchBattle is idempotent, always succeeds and sends no 166. RemoveObserver sends 166 REMOVED and closes.
- internal: Observer routing has no scene fields: observers never cause a settlement.
- java: missing — none
- size: M
- robot: battle_smoke (robot B watches)
- hazards: Participant and observer share one direct-connection slot per player_id, which is why participants are refused as observers.

### battle-settlement-outbox — battle 侧结算发件箱与活动结果持久通道
- mmorpg: cpp/libs/services/battle/settlement/settlement_outbox.h; cpp/nodes/battle/logic/battle_room_manager.cpp (DispatchSettlementDurably/EnqueuePendingSettlement/RetryPendingSettlements/ProbeAndRetryOne/DispatchActivityResultDurably/ProbeActivityResultOne); cpp/libs/services/battle/system/battle_result_activity.h
- client messages: none
- tables: none
- depends on: battle-room-lifecycle; scene-battle-settlement-apply; guild trial (other area)
- behavior: Settlement for each player: SET battle:settlement:pending:{pid} = event blob and pending:id:{pid} = battle_id (TTL 7 days, one Lua script), then send to the scene recorded in the snapshot. Every 10 s, up to 12 times: if the id key no longer equals this battle → done (that is the ACK); attempts exhausted → loud log (the record stays for the login hook); location unresolved → skip; otherwise resend to the scene node found by re-resolving player:{id}:location (instance id left empty). If Redis is down, send once with an ERROR. Activity battles (guild trial): SET battle:activity_result:{battle_id} first, then publish; retry every 10 s up to 30 times while the key EXISTS; guild deletes it. The result event carries fled and dead id lists, sorted and deduplicated.
- internal: Redis plus Kafka in the baseline. Java: Redisson plus whatever transport Java uses for battle → scene and battle → guild/match. Keys must go through RedisKeys with the xm: prefix.
- java: missing — none
- size: M
- robot: battle_smoke / features_smoke (happy path)
- hazards: The record must be stored before sending, or a fast ACK followed by a late SET leaves an orphan that pays out twice. The pending slot is single per player: the next battle's unconditional SET overwrites an unacknowledged one (mitigated scene-side by holding the lock until the save lands).

### battle-node-admission-ops — battle 节点准入闸/部署生命周期/启动安全门禁
- mmorpg: cpp/nodes/battle/battle_admission_gate.h; cpp/nodes/battle/handler/grpc/battle_node.cpp; cpp/nodes/battle/main.cpp (ValidateBattleClientEdgeConfigOrDie, Agones lifecycle, TableLoadHandler)
- client messages: none
- tables: none
- depends on: battle-room-lifecycle
- behavior: The admission gate moves NotStarted → Open → Closed (terminal). CreateBattle is refused with gRPC UNAVAILABLE 'battle_not_allocatable' before any side effect when the gate is not open, when no Agones permit is available, or when the gate closes while the request is queued; match then retries another node without calling DestroyBattle. On shutdown: close admission, then AbortAllRooms in the same loop task. Startup refuses to run in prod with an empty or short secret, a secret equal to the gate's, or max_connections=0. Table load logs an error if key tables are empty.
- internal: Agones/k8s-specific lifecycle, etcd publishing and the gRPC-only node type.
- java: missing — none
- size: M
- robot: none
- hazards: Agones integration is deployment-specific and could be not_applicable for Java. The admission open/close ordering and the 'battle_not_allocatable' string contract with match still matter if Java match retries.

### scene-battle-freeze — scene 侧备战冻结/确认/取消/锁与 ctx/reaper/重连提示
- mmorpg: cpp/libs/services/scene/battle/system/player_battle.cpp (PrepareBattle/BuildBattleSnapshot/CancelBattlePrepare/ConfirmBattle/RebuildBattleFreezeFromLock/RestoreBattleFreezeOnLogin/OnPlayerEnterScene/NotifyBattleReconnectToClient/StartReaper); cpp/nodes/scene/handler/grpc/scene_node_service.cpp; cpp/nodes/scene/handler/event/battle_event_handler.cpp; proto/common/component/battle_comp.proto (InBattleComp)
- client messages: 144 BattleClientPlayerNotifyBattleReconnect (S2C via lobby); internal 141 ScenePrepareBattle, 142 SceneNodeGrpcPrepareBattle, 145 SceneNodeGrpcCancelBattlePrepare, 155 SceneCancelBattlePrepare
- tables: Skill; Buff; Item
- depends on: death-revive; pet-battle-integration; battle-table-fingerprint; player attributes/bag/session (other areas); match service (other area)
- behavior: Prepare: player_id, battle_id or deadline 0 → 1005; not on this node → 1004; frozen for cross-zone travel → 1006; already in a battle → 1006; HP 0 → 1006; build the snapshot (missing attributes → 1004, no session → 1011). The snapshot carries name, appearance, class, gender, level, base attributes (speed 0 → 120), max HP/MP (from derived, else the current values), physical attack, magic attack and defense, the summoned pet, castable skills (no passive/toggle/channel), lasting buffs (no stun/freeze/silence/instant ones, caster mapped to the player only for self-cast, full table duration converted to rounds), items from the main bag with battle_usable≠0, routing (session, gate node and uuid, scene node and uuid, zone), team 0 and the fingerprint. It attaches InBattleComp{PREPARING, deadline, prepare_deadline} and sets battle:lock = battle_id and battle:ctx with TTL = remaining + 60 s. Cancel: only when battle_id matches and the state is PREPARING; a cancel for a FIGHTING battle is refused (metric). Offline: the ctx is read conditionally. Confirm: PREPARING → FIGHTING, deadline switches, lock TTL extended and ctx rewritten via Lua; if the session changed since prepare, push 144. Late confirms rebuild the freeze from the lock. Login and enter: apply the pending settlement first, otherwise rebuild InBattleComp from lock plus ctx (push 144 if FIGHTING). RECONNECT or REPLACE while FIGHTING → push 144. The reaper runs every 30 s: PREPARING past prepare_deadline → remove the component, keep the lock until TTL; FIGHTING past deadline → remove the component and conditionally delete the lock; it also drains the settlement-ledger ACKs.
- internal: Redis Lua scripts with conditional get/del/expire keyed by lock value; InBattleComp is not persisted. Java: Redisson Lua under RedisKeys; the scene logic thread owns the state; Redis I/O stays off the scene thread with results posted back.
- java: missing — xm-scene has no battle code. ClientRequestHandler.enterScene notes that 3023 for an in-flight battle never occurs.
- size: L
- robot: battle_smoke, battle_smoke_cross_zone, features_smoke (verify_relogin)
- hazards: Every lock delete or extend must be conditional on its value, or a late message hits the next battle. The ctx must always share the lock's lifecycle. Rebuilds must be checked again after the round trip (ConfirmRebuiltFreeze). Do not push 144 while PREPARING (the room does not exist yet). Snapshot buff durations use the full table duration (remaining time is unknown).

### scene-battle-settlement-apply — scene 侧结算应用(幂等账本/HP MP 回写/金币/道具/宝宝/任务击杀/销账)
- mmorpg: cpp/libs/services/scene/battle/system/player_battle.cpp (ApplySettlement/ApplySettlementToEntity/ApplySettlementItems/ApplyPendingSettlement/AckSettlementPending/RequestSettlementPersist/StorePendingSettlementIfLockMatch/ReleaseFreezeKeepLock/PushBattleEndToPlayer); cpp/libs/services/scene/battle/system/battle_settlement_ledger.h; cpp/libs/services/scene/battle/system/battle_settlement_application_cache.h
- client messages: 150 BattleClientPlayerNotifyBattleEnd (S2C via lobby, after apply; not pushed again if already applied); 184 ScenePetClientPlayerNotifyPetListChanged (after pet write-back)
- tables: Item
- depends on: scene-battle-freeze; battle-settlement-outbox; death-revive; pet-battle-integration; currency, bag, mission, player save (other areas)
- behavior: Online with a matching InBattleComp: apply. A mismatched battle_id → ACK and drop. No InBattleComp → check GET lock == battle_id and apply only if it matches. Offline or exiting → store pending only while the lock still matches (Lua); if another battle holds the lock → ACK; if there is no lock → leave it for login. Apply order: the persisted ledger already has it → treat as applied with no 150 → the entity is mutable (not frozen, travelling or exiting) → gold first (can fail and is retried before any other side effect) → HP and MP clamped to derived max → revive if dead → pets written back plus a 184 push → exp only logged (no exp system) → items consumed (only battle_usable ids, clamped to actual holdings, then compact) → drops into the main bag, overflow into the temporary bag, counting only what landed → one mission kill-progress event per monster → record in the ledger. After applying: request a save; unfreeze but keep the lock until the save is durable; ACK only when the ledger entry is in the last persisted snapshot. The ACK Lua conditionally deletes pending and the lock together. Then push 150.
- internal: The ledger (≤64 entries) is persisted in player data; there is also an in-process application cache (7 days, 131072 entries). Java: put the applied-battle ledger in the same MySQL write-back and transaction as the assets, under the owner_epoch fence. That gives exactly-once more simply than the baseline's snapshot comparison.
- java: missing — Java has no currency, bag, mission or HP systems yet.
- size: L
- robot: features_smoke (victory → mission progress → claim → relogin), battle_smoke
- hazards: This is the main double-reward and lost-reward trap. ACKing before the save is durable loses the reward on a crash; marking applied before the save and then crashing duplicates it. Never return a failure after gold has been credited (items fail-soft). Clamp the uint64 count when converting to uint32. Not having an exp system means exp_gain is dropped silently today.

### in-battle-gates — 战斗在途冻结闸(各系统入口拒绝)
- mmorpg: cpp/nodes/scene/handler/rpc/player/player_movement_handler.cpp; cpp/libs/services/scene/spatial/system/movement.cpp; cpp/nodes/scene/handler/rpc/player/player_scene_handler.cpp; cpp/libs/services/scene/player/system/player_attribute.cpp; cpp/libs/services/scene/player/system/player_pet.cpp; cpp/libs/modules/bag/bag_service.cpp; cpp/libs/services/scene/player/system/asset_op_system.cpp; cpp/libs/services/scene/player/system/player_lifecycle.cpp; cpp/libs/services/scene/combat/skill/system/skill.cpp; cpp/libs/services/scene/combat/buff/system/buff.cpp
- client messages: 134/132/131 movement silently dropped (MoveStop still zeroes velocity); 63 EnterScene → 3023 kEnterSceneFailed; 226 TravelToZone → 3025 kZoneTravelInBattle; 168/172/173/174/171 attribute → 25011 kAttributeInBattle; 183/185/186/182/189 pet → 26008 kPetInBattle; 84 ReleaseSkill → 7004/7002
- tables: none
- depends on: scene-battle-freeze
- behavior: While InBattleComp is present: movement reports are dropped and velocity is not integrated; scene switches, including mirror scenes, are refused with 3023; cross-zone travel gets 3025; attribute and pet writes are refused; bag sort is refused; asset ops return RETRY and are not recorded; realtime skills and buffs are blocked. On unfreeze, team-follow is checked again (PlayerTeamSystem::OnBattleFreezeCleared).
- internal: One predicate checked at each entry point.
- java: missing — Java's SceneWorld.applyMove and ClientRequestHandler.enterScene have no battle gate (enterScene comments that 3023 for in-battle never occurs).
- size: S
- robot: battle_smoke (indirect)
- hazards: Every future asset-reducing entry point needs this gate (rule D48: the gate lives only at the entry layer), or the settlement's consumption clamp turns into an exploit.

### gate-battle-uplink-reject — gate 拒绝战斗上行
- mmorpg: cpp/nodes/gate/handler/rpc/client_message_processor.cpp (BattleNodeService → SendTipToClient kServiceUnavailable)
- client messages: 149/140/165/162 sent on the lobby connection → 23 SceneClientPlayerCommonSendTipToClient {1003}
- tables: none
- depends on: none
- behavior: Battle uplink on the lobby (gate) connection gets a 23 tip 1003. It does not count as an illegal packet and does not disconnect.
- internal: none
- java: done — xm-gate ClientDispatcher.dispatch default branch (unimplemented domains) → sendTip(TIP_SERVICE_UNAVAILABLE), the same visible result.
- size: S
- robot: none
- hazards: If Java later adds a battle domain to gate routing, keep this rejection: battle traffic must use the direct connection only.

### pet-battle-integration — 宝宝参战(快照/自动出手/结算回写)
- mmorpg: cpp/libs/services/scene/player/system/player_pet.cpp (BuildBattleSnapshot/ApplyBattleSettlement/PushList); cpp/libs/services/battle/system/turn_battle_engine.cpp (InitPets, BuildSettlement pets)
- client messages: 184 ScenePetClientPlayerNotifyPetListChanged; BattleActorState with actor_type PET, owner_player_id, pet_table_id
- tables: Pet; PetRule; AttributePool; AttributeDimension
- depends on: pet-system-core; turn-battle-engine-core; scene-battle-settlement-apply
- behavior: Only the summoned pet joins (at most one), on its owner's team, with actor_id = bit63|2<<32 + n and is_auto. It always basic-attacks and never blocks round readiness. Ownership is taken from the snapshot it sits in; a mismatched owner_player_id or a duplicate pet_id refuses the whole battle. Its stats come from PetSystem-computed derived values. In the settlement, pets[] carry the real pet_id with HP, MP and is_dead; the scene clamps to the computed max, revives a dead pet to full, then pushes 184. Kills by a pet count for its owner.
- internal: Pet derived stats are not stored; they are recomputed.
- java: missing — none
- size: M
- robot: pet_smoke (summon); battle with a pet only incidentally
- hazards: Never use pet_id as actor_id: ids collide with player ids after the id-segment change.

### pet-system-core — 宝宝系统(列表/出战/收回/加点/洗点/自动加点/改名/GM 发放)
- mmorpg: cpp/libs/services/scene/player/system/player_pet.cpp/.h; cpp/libs/services/scene/player/system/pet_rules.h; cpp/nodes/scene/handler/rpc/player/player_pet_handler.cpp; proto/scene/player_pet.proto
- client messages: 181 ScenePetClientPlayerGetPetList; 183 SummonPet; 185 RecallPet; 186 AllocatePetPoints; 182 ResetPetPoints; 188 AutoAllocatePetPoints; 189 RenamePet; 187 GmGrantPet (GM-gated); 184 NotifyPetListChanged (push)
- tables: Pet; PetRule; AttributePool; AttributeDimension; AttributeAutoPlan
- depends on: currency; attribute-allocation rules (other area)
- behavior: Errors use pet_error 26000–26016: not found, row missing, slot full (PetRule.max_pets, default 1), owner level below unlock_level, already active / not active, invalid name (name_max_len default 8), in battle, not enough points, points cannot decrease, dimension cap, gold not enough (reset and rename cost gold; renaming to the same name is free and returns NothingToChange), no auto plan. Only one pet can be summoned at a time. Pet level follows the owner, capped by level_cap. The full PetListInfo is returned in each response.
- internal: PlayerPetComp is persisted. Trade primitives (RemovePetForTrade/RestorePetFromSnapshot) exist for the 聚宝斋 marketplace.
- java: missing — none
- size: L
- robot: pet_smoke
- hazards: It sits outside the assigned combat directories and may also be inventoried under the player/attribute area, so de-duplicate. summon_cooldown_seconds and kPetSummonCooldown appear unused in the baseline.

### battle-art-gen — 回合制战斗美术程序化生成工具
- mmorpg: tools/battle_art_gen/*.go; docs/design/battle-art-prompts.md; docs/design/turn-battle-presentation.md
- client messages: none
- tables: Buff
- depends on: none
- behavior: An offline Go tool that writes PNG FX, UI, digits, buff icons and character/monster sprite strips into the Unity client's Assets/Resources/Battle. It is deterministic for a given seed and reads buff ids from the Buff json.
- internal: none (client asset pipeline)
- java: not_applicable — Client-side asset tool; no server behaviour.
- size: S
- robot: none
- hazards: none for the server port

## Open questions

- Realtime combat parity: the baseline's damage, effects, cooldowns and recovery are dead in production (SkillContextCompMap and CooldownTimeListComp are never attached to players). Should Java mirror that (validation + 70/33 only) or implement working realtime combat? Working combat needs HP sync to clients (66 has no HP field) and a death design first, which means mmorpg changes first.
- Java has no base or derived attributes, currency, bag, mission or exp systems. The turn-battle snapshot and settlement depend on all of them. Sequencing: attribute-allocation, currency, bag and mission must land before scene-battle-settlement-apply.
- Transport in Java for battle → scene (Confirm/Settlement), battle → gate lobby announcements (177/143), battle → match results, and match → battle/scene (Create/Prepare/Cancel/Observer/Ticket): Dubbo, Redis pub/sub, or Kafka (planned)? Whichever it is, it must keep per-player ordering and the target-instance fencing.
- Will the Java match service (Go match equivalent: JoinQueue 157, WatchBattle 163, RequestBattleTicket 179, etc.) be inventoried by another area? It is the battle orchestrator and is not covered here.
- Navmesh: a Java Detour port (recast4j) violates the ≥20k-star selection rule. Keep MoveGuard as the deliberate alternative (already in PARITY), or write a minimal .bin reader for snap and raycast? The nav .bin files are also not synced by ContractSync today.
- Table formula evaluation: the mmorpg Java exporter template has no expression support and xm-table is sync-only. Should expression evaluation be added to the mmorpg Java generator (contract change) or as an xm-scene/xm-battle wrapper with an allowed parser (SpEL or hand-written)?
- Pet system (181–189) is implemented under scene/player in mmorpg. Confirm which area owns it to avoid double-counting.
- Should the Java battle node include Agones lifecycle integration, or is that deployment-specific and not_applicable for Java?
