package com.game.team.store;

import com.game.pbmysql.PbMysql;
import com.game.pbmysql.TableOption;
import com.game.team.proto.*;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Team唯一持久化契约；永久记录、墓碑与原件不配置TTL。 */
public final class TeamTables {
    private TeamTables() {}
    public static PbMysql registry() {
        var db=new PbMysql();
        db.register(TeamPolicyRow.getDefaultInstance(),TableOption.withTableName("team_policy"),TableOption.withPrimaryKey("id"));
        db.register(TeamAuthorityRow.getDefaultInstance(),TableOption.withTableName("team_authority"),TableOption.withPrimaryKey("team_id"));
        db.register(TeamMemberAuthorityRow.getDefaultInstance(),TableOption.withTableName("team_member_authority"),TableOption.withPrimaryKey("player_id"),TableOption.withIndexes("team_id,player_id"));
        db.register(TeamInviteAuthorityRow.getDefaultInstance(),TableOption.withTableName("team_invite_authority"),TableOption.withPrimaryKey("invitee_id","team_id"),TableOption.withIndexes("team_id,invitee_id","invitee_id,expires_ms,team_id","initiator_player_id,expires_ms,team_id"));
        db.register(TeamApplicationAuthorityRow.getDefaultInstance(),TableOption.withTableName("team_application_authority"),TableOption.withPrimaryKey("player_id","team_id"),TableOption.withIndexes("team_id,player_id","player_id,expires_ms,team_id","recipient_player_id,expires_ms,team_id"));
        db.register(TeamLaunchRoleRow.getDefaultInstance(),TableOption.withTableName("team_launch_role"),TableOption.withPrimaryKey("player_id","request_id"),TableOption.withIndexes("request_id,player_id","player_id,state,request_id"));
        db.register(TeamActionRow.getDefaultInstance(),TableOption.withTableName("team_action"),TableOption.withPrimaryKey("player_id","request_id"));
        db.register(TeamLaunchRow.getDefaultInstance(),TableOption.withTableName("team_launch"),TableOption.withPrimaryKey("request_id"),TableOption.withIndexes("state,updated_ms,request_id","team_id,request_id","active,updated_ms,request_id"));
        return db;
    }
    public static void sync(DataSource source)throws SQLException {
        try(var connection=source.getConnection()){registry().syncAll(connection);}
    }
    public static byte[] policy(boolean allowCrossZone){return com.game.common.team.TeamPolicyAuthority.canonicalContract(allowCrossZone);}
    public static void ensurePolicy(DataSource source,boolean allowCrossZone)throws SQLException{
        try(var connection=source.getConnection()){
            connection.setAutoCommit(false);
            try{
                com.game.common.team.TeamPolicyAuthority.ensure(connection,allowCrossZone,3);
                connection.commit();
            }catch(SQLException|RuntimeException failure){try{connection.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;}
        }
    }
}
