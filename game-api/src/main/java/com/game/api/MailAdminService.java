package com.game.api;

import com.game.proto.mail.*;
import java.util.concurrent.CompletableFuture;

/** 内部发件，group mail；角色固定admin1/guild2/rank3/trade4/battle5。业务ID及载荷稳定重投。 */
public interface MailAdminService {
    String PERSONAL_METHOD="/mailpb.MailAdmin/SendPersonalMail";
    String SYSTEM_METHOD="/mailpb.MailAdmin/SendSystemMail";
    String GUILD_METHOD="/mailpb.MailAdmin/SendGuildMail";
    String IMPORT_METHOD="/mailpb.MailAdmin/ImportMailRecipients";
    String SEAL_METHOD="/mailpb.MailAdmin/SealMailCampaign";
    CompletableFuture<SendMailResponse> sendPersonalMail(SendMailRequest request);
    CompletableFuture<SendMailResponse> sendSystemMail(SendMailRequest request);
    CompletableFuture<SendMailResponse> sendGuildMail(SendMailRequest request);
    CompletableFuture<ImportMailRecipientsResponse> importMailRecipients(ImportMailRecipientsRequest request);
    CompletableFuture<SendMailResponse> sealMailCampaign(SealMailCampaignRequest request);
}
