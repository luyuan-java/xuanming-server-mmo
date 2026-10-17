package com.game.api.asset;

import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetItem;
import com.game.proto.mail.MailAttachments;
import java.util.HashSet;
import java.util.function.IntPredicate;

/** 邮件与榜奖共用授予校验；装备实例托管属于交易，不能经邮件按模板重铸。 */
public final class MailGrants {
    private MailGrants() {}
    public static AssetBundle bundle(MailAttachments input, IntPredicate itemExists) {
        if (!input.getUnknownFields().asMap().isEmpty() || input.getCurrenciesCount() > 4 || input.getItemsCount() > 16)
            throw new IllegalArgumentException("邮件附件无效");
        var out = AssetBundle.newBuilder();
        var currencies = new HashSet<Integer>();
        var items = new HashSet<Integer>();
        for (var value : input.getCurrenciesList()) {
            if (!value.getUnknownFields().asMap().isEmpty() || Integer.compareUnsigned(value.getCurrencyType(), 2) > 0
                    || value.getAmount() <= 0 || !currencies.add(value.getCurrencyType()))
                throw new IllegalArgumentException("邮件货币无效");
            out.addCurrencies(AssetCurrency.newBuilder().setCurrencyType(value.getCurrencyType()).setAmount(value.getAmount()));
        }
        long totalItems = 0;
        for (var value : input.getItemsList()) {
            if (!value.getUnknownFields().asMap().isEmpty() || value.getConfigId() == 0 || value.getCount() == 0
                    || !items.add(value.getConfigId()) || itemExists == null || !itemExists.test(value.getConfigId()))
                throw new IllegalArgumentException("邮件道具无效");
            totalItems += Integer.toUnsignedLong(value.getCount());
            if (totalItems > 4096)
                throw new IllegalArgumentException("邮件道具总数超过scene单包授予上限");
            out.addItems(AssetItem.newBuilder().setConfigId(value.getConfigId()).setCount(value.getCount()));
        }
        return out.build();
    }
}
