package com.game.table;

/**
 * 配置表的具名行 id（Excel constants_name 列）。由 tools/ContractSync.java 从 mmorpg 导表器产物生成，不要手改。
 */
public final class TableConstants {

    private TableConstants() {
    }

    /** 表 {@code ActorActionCombatState}。 */
    public static final class ActorActionCombatState {

        private ActorActionCombatState() {
        }

        public static final int ACTOR_ACTION_USE_SKILL = 0;
        public static final int ACTOR_ACTION_JOIN_FOLLOW = 1;
        public static final int ACTOR_ACTION_MOUNT_ACTOR = 2;
        public static final int ACTOR_ACTION_UNMOUNT_ACTOR = 3;
    }

    /** 表 {@code ActorActionState}。 */
    public static final class ActorActionState {

        private ActorActionState() {
        }

        public static final int ACTOR_ACTION_USE_SKILL = 0;
        public static final int ACTOR_ACTION_JOIN_FOLLOW = 1;
        public static final int ACTOR_ACTION_MOUNT_ACTOR = 2;
        public static final int ACTOR_ACTION_UNMOUNT_ACTOR = 3;
    }

    /** 表 {@code GlobalVariable}。 */
    public static final class GlobalVariable {

        private GlobalVariable() {
        }

        public static final int ABNORMAL_LOGOUT = 1;
    }
}
