package com.game.mail;

/** 只放开真实工作器单步调度；不写账本或邮件终态。 */
public final class MailAssetArrivalFixture {
    private MailAssetArrivalFixture() { }
    public static void process(MailDeliveryWorker worker, long operation) throws Exception {
        worker.process(operation);
    }
}
