package com.game.mail;

import com.game.api.*;
import com.game.api.proto.*;
import com.game.common.deadline.Deadline;
import com.game.discovery.serviceauth.DomainClientDispatch;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.proto.TipInfoMessage;
import com.game.proto.mail.*;
import com.game.table.CommonErrorTip;
import com.google.protobuf.Message;
import java.util.*;
import java.util.concurrent.*;
import org.apache.dubbo.config.annotation.DubboService;

/** 内部角色签名与玩家入口各自验证，阻塞JDBC只在有界工作池执行。 */
@DubboService(group = DubboGroups.MAIL, interfaceClass = MailAdminService.class)
public final class MailProvider implements MailAdminService, AutoCloseable {
    private final MailStore store;
    private final ServiceCallSignatures signatures;
    private final MailNotifications notifications;
    private final ThreadPoolExecutor admin;
    public MailProvider(MailStore store, ServiceCallSignatures signatures, MailNotifications notifications) {
        this.store = store;
        this.signatures = signatures;
        this.notifications = notifications;
        admin = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256),
                Thread.ofPlatform().name("mail-admin-", 0).daemon(true).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
    static int error(Throwable failure) {
        if (failure instanceof MailStore.Reject reject)
            return reject.tip;
        if (failure instanceof IllegalArgumentException)
            return CommonErrorTip.common_error.kInvalidParameter_VALUE;
        return CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    }
    @FunctionalInterface
    private interface Work<T> {
        T run(String caller, Deadline deadline) throws Exception;
    }
    @SuppressWarnings("unchecked")
    private <T extends Message> CompletableFuture<T> invoke(String method, Message request, T prototype, Work<T> work) {
        Deadline budget = Deadline.after(3500);
        var response = new CompletableFuture<T>();
        try {
            String caller = signatures.verify(method, request);
            admin.execute(() -> {
                try {
                    response.complete(work.run(caller, budget));
                } catch (Exception failure) {
                    var b = prototype.toBuilder();
                    var f = b.getDescriptorForType().findFieldByName("error_message");
                    b.setField(f, TipInfoMessage.newBuilder().setId(error(failure)).build());
                    response.complete((T) b.build());
                }
            });
        } catch (Exception failure) {
            response.completeExceptionally(failure);
        }
        return response;
    }
    public CompletableFuture<SendMailResponse> sendPersonalMail(SendMailRequest r) {
        return invoke(PERSONAL_METHOD, r, SendMailResponse.getDefaultInstance(), (c, d) -> {
            var response = store.send(c, 1, r, d);
            if (!response.getDuplicated())
                notifications.personal(r.getRecipientPlayerId(), response.getMailId());
            return response;
        });
    }
    public CompletableFuture<SendMailResponse> sendSystemMail(SendMailRequest r) {
        return invoke(SYSTEM_METHOD, r, SendMailResponse.getDefaultInstance(), (c, d) -> store.send(c, 2, r, d));
    }
    public CompletableFuture<SendMailResponse> sendGuildMail(SendMailRequest r) {
        return invoke(GUILD_METHOD, r, SendMailResponse.getDefaultInstance(), (c, d) -> store.send(c, 3, r, d));
    }
    public CompletableFuture<ImportMailRecipientsResponse> importMailRecipients(ImportMailRecipientsRequest r) {
        return invoke(IMPORT_METHOD, r, ImportMailRecipientsResponse.getDefaultInstance(), (c, d) -> store.importRecipients(c, r, d));
    }
    public CompletableFuture<SendMailResponse> sealMailCampaign(SealMailCampaignRequest r) {
        return invoke(SEAL_METHOD, r, SendMailResponse.getDefaultInstance(), (c, d) -> store.seal(c, r, d));
    }
    static Message client(MailStore store, String method, long player, Message request, Deadline budget) throws Exception {
        return switch (method) {
            case "ListMails" -> store.list(player, (ListMailsRequest) request, budget);
            case "ReadMail" -> store.read(player, (ReadMailRequest) request, budget);
            case "DeleteMails" -> store.delete(player, ((DeleteMailsRequest) request).getMailIdsList(), false, budget);
            case "ClearReadMails" -> {
                var v = store.delete(player, List.of(), true, budget);
                yield ClearReadMailsResponse.newBuilder()
                        .addAllDeletedMailIds(v.getDeletedMailIdsList())
                        .addAllProtectedMailIds(v.getProtectedMailIdsList())
                        .build();
            }
            case "ClaimMailAttachments" -> {
                var r = (ClaimMailAttachmentsRequest) request;
                if (r.getMailIdsCount() == 0 || r.getMailIdsCount() > 100)
                    throw new IllegalArgumentException();
                var out = ClaimMailAttachmentsResponse.newBuilder();
                for (long id : new LinkedHashSet<>(r.getMailIdsList())) try {
                        out.addResults(store.claim(player, id, budget));
                    } catch (Exception failure) {
                        out.addResults(MailActionResult.newBuilder().setMailId(id).setErrorMessage(
                                TipInfoMessage.newBuilder().setId(error(failure))));
                    }
                yield out.build();
            }
            default -> throw new IllegalArgumentException();
        };
    }
    @Override
    public void close() {
        admin.shutdown();
        try {
            if (!admin.awaitTermination(10, TimeUnit.SECONDS))
                admin.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            admin.shutdownNow();
        }
    }
}
