package com.kuba6000.ae2webintegration.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.HashMap;

import net.minecraft.item.ItemStack;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSerializer;
import com.kuba6000.ae2webintegration.AE2Controller;
import com.kuba6000.ae2webintegration.AE2JobTracker;

import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import cpw.mods.fml.common.registry.GameRegistry;

public class GSONUtils {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface SkipGSON {}

    private static final ExclusionStrategy GSONStrategy = new ExclusionStrategy() {

        @Override
        public boolean shouldSkipField(FieldAttributes f) {
            return f.getAnnotation(SkipGSON.class) != null;
        }

        @Override
        public boolean shouldSkipClass(Class<?> clazz) {
            return false;
        }
    };

    public static AE2Controller.GSONItem convertToGSONItem(IAEItemStack src) {
        return new AE2Controller.GSONItem(
            GameRegistry.findUniqueIdentifierFor(src.getItem())
                .toString() + ":"
                + src.getItemDamage(),
            src.getItemStack()
                .getDisplayName(),
            src.getStackSize());
    }

    private static final JsonSerializer<IAEItemStack> AE2_ITEM_STACK_JSON_SERIALIZER = (src, typeOfSrc,
        context) -> context.serialize(convertToGSONItem(src));

    private static final JsonSerializer<AE2Controller.AE2Data.ClusterData> CLUSTER_DATA_JSON_SERIALIZER = (src,
        typeOfSrc, context) -> {
        AE2Controller.AE2Data.ClusterCompactedData data = new AE2Controller.AE2Data.ClusterCompactedData();
        if (src.finalOutput == null) return context.serialize(data);
        data.finalOutput = convertToGSONItem(src.finalOutput);
        AE2JobTracker.JobTrackingInfo info = src.trackingInfo;
        data.hasTrackingInfo = info != null;
        if (data.hasTrackingInfo) data.timeStarted = info.timeStarted;
        if (src.active != null) {
            HashMap<AE2Controller.CompactedItem, AE2Controller.CompactedItem> prep = new HashMap<>();
            for (IAEItemStack iaeItemStack : src.active) {
                AE2Controller.CompactedItem compactedItem = AE2Controller.CompactedItem.create(iaeItemStack);
                prep.computeIfAbsent(compactedItem, k -> compactedItem).active += iaeItemStack.getStackSize();
            }
            for (IAEItemStack iaeItemStack : src.pending) {
                AE2Controller.CompactedItem compactedItem = AE2Controller.CompactedItem.create(iaeItemStack);
                prep.computeIfAbsent(compactedItem, k -> compactedItem).pending += iaeItemStack.getStackSize();
            }
            for (IAEItemStack iaeItemStack : src.storage) {
                AE2Controller.CompactedItem compactedItem = AE2Controller.CompactedItem.create(iaeItemStack);
                prep.computeIfAbsent(compactedItem, k -> compactedItem).stored += iaeItemStack.getStackSize();
            }

            if (data.hasTrackingInfo) {
                for (IAEItemStack iaeItemStack : info.timeSpentOn.keySet()) {
                    AE2Controller.CompactedItem compactedItem = AE2Controller.CompactedItem.create(iaeItemStack);
                    AE2Controller.CompactedItem finalCompactedItem = compactedItem;
                    compactedItem = prep.computeIfAbsent(compactedItem, k -> finalCompactedItem);
                    compactedItem.timeSpentCrafting += info.getTimeSpentOn(iaeItemStack);
                    compactedItem.craftedTotal += info.craftedTotal.getOrDefault(iaeItemStack, 0L);
                    compactedItem.shareInCraftingTime += info.getShareInCraftingTime(iaeItemStack);
                    compactedItem.craftsPerSec = (double) compactedItem.craftedTotal
                        / (compactedItem.timeSpentCrafting / 1e9d);
                }
            }

            data.items = new ArrayList<>(prep.size());
            data.items.addAll(prep.values());
            data.items.sort((i1, i2) -> {
                if (i1.active > 0 && i2.active > 0) return Long.compare(i2.active, i1.active);
                else if (i1.active > 0 && i2.active == 0) return -1;
                else if (i1.active == 0 && i2.active > 0) return 1;
                if (i1.pending > 0 && i2.pending > 0) return Long.compare(i2.pending, i1.pending);
                else if (i1.pending > 0 && i2.pending == 0) return -1;
                else if (i1.pending == 0 && i2.pending > 0) return 1;
                return Long.compare(i2.stored, i1.stored);
            });
        }
        return context.serialize(data);
    };

    private static final JsonSerializer<IItemList<?>> AE2_ITEM_LIST_JSON_SERIALIZER = (src, typeOfSrc, context) -> {
        ArrayList<AE2Controller.GSONItem> list = new ArrayList<>(src.size());
        for (IAEStack iaeStack : src) {
            if (iaeStack instanceof IAEItemStack iaeItemStack) {
                ItemStack stack = iaeItemStack.getItemStack();
                list.add(
                    new AE2Controller.GSONItem(
                        GameRegistry.findUniqueIdentifierFor(stack.getItem())
                            .toString() + ":"
                            + stack.getItemDamage(),
                        stack.getDisplayName(),
                        iaeItemStack.getStackSize()));
            }
        }
        return context.serialize(list);
    };

    private static final JsonSerializer<AE2JobTracker.JobTrackingInfo> JOB_TRACKING_INFO_JSON_SERIALIZER = (src,
        typeOfSrc, context) -> context.serialize(new AE2JobTracker.CompactedJobTrackingInfo(src));

    public static final GsonBuilder GSON_BUILDER = new GsonBuilder().addSerializationExclusionStrategy(GSONStrategy)
        .addDeserializationExclusionStrategy(GSONStrategy)
        .registerTypeAdapter(IItemList.class, AE2_ITEM_LIST_JSON_SERIALIZER)
        .registerTypeAdapter(IAEItemStack.class, AE2_ITEM_STACK_JSON_SERIALIZER)
        .registerTypeAdapter(AE2Controller.AE2Data.ClusterData.class, CLUSTER_DATA_JSON_SERIALIZER)
        .registerTypeAdapter(AE2JobTracker.JobTrackingInfo.class, JOB_TRACKING_INFO_JSON_SERIALIZER)
        .serializeNulls();

}
