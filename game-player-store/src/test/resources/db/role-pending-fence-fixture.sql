-- 仅测试消费方的最小SQL投影；生产结构仍由各域正规proto/schema管理，不得用此脚本部署。
-- player/player_trade_*须先执行正式game-player-schema.sql；route须用唯一正式DDL+显式Initializer。
CREATE TABLE team_member_authority(player_id BIGINT PRIMARY KEY,team_id BIGINT);
CREATE TABLE team_launch_role(player_id BIGINT,request_id BIGINT,state INT,PRIMARY KEY(player_id,request_id));
CREATE TABLE team_invite_authority(invitee_id BIGINT,team_id BIGINT,expires_ms BIGINT,initiator_player_id BIGINT,PRIMARY KEY(invitee_id,team_id));
CREATE TABLE team_application_authority(player_id BIGINT,team_id BIGINT,expires_ms BIGINT,recipient_player_id BIGINT,PRIMARY KEY(player_id,team_id));
CREATE TABLE guild_trial_launch_role(player_id BIGINT,request_id BIGINT,state INT,PRIMARY KEY(player_id,request_id));
CREATE TABLE guild_activity_slot(player_id BIGINT PRIMARY KEY,lobby_id BIGINT,expires_ms BIGINT);
CREATE TABLE guild_trial_lobby(lobby_id BIGINT PRIMARY KEY,state INT);
CREATE TABLE guild_activity_reward_owed(reward_id BIGINT PRIMARY KEY,player_id BIGINT,status INT);
CREATE TABLE guild_asset_op(op_id BIGINT PRIMARY KEY,player_id BIGINT,status INT);
CREATE TABLE mail_asset_op(op_id BIGINT PRIMARY KEY,player_id BIGINT,status INT);
CREATE TABLE player_inbox_item(shard INT,player_id BIGINT,state INT);
CREATE TABLE match_global_ticket(player_id BIGINT PRIMARY KEY,state INT);
CREATE TABLE match_global_watching(player_id BIGINT PRIMARY KEY,state INT);
CREATE TABLE match_global_challenge(challenger_id BIGINT,target_id BIGINT,state INT,expires_ms BIGINT,PRIMARY KEY(challenger_id,target_id));
CREATE TABLE trade_commerce(commerce_id BIGINT PRIMARY KEY,seller_player_id BIGINT,buyer_player_id BIGINT,status INT);
CREATE TABLE trade_asset_op(op_id BIGINT PRIMARY KEY,player_id BIGINT,status INT,receipt_ack_pending INT,business_consumed INT);
CREATE TABLE trade_listing(listing_id BIGINT PRIMARY KEY,seller_player_id BIGINT,status INT);
