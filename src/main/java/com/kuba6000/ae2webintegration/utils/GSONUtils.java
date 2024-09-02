package com.kuba6000.ae2webintegration.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSerializer;
import com.kuba6000.ae2webintegration.AE2JobTracker;
import com.kuba6000.ae2webintegration.api.JSON_Item;

import appeng.api.storage.data.IAEItemStack;
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

    public static JSON_Item convertToGSONItem(IAEItemStack src) {
        return new JSON_Item(
            GameRegistry.findUniqueIdentifierFor(src.getItem())
                .toString() + ":"
                + src.getItemDamage(),
            src.getItemStack()
                .getDisplayName(),
            src.getStackSize());
    }

    private static final JsonSerializer<AE2JobTracker.JobTrackingInfo> JOB_TRACKING_INFO_JSON_SERIALIZER = (src,
        typeOfSrc, context) -> context.serialize(new AE2JobTracker.CompactedJobTrackingInfo(src));

    public static final GsonBuilder GSON_BUILDER = new GsonBuilder().addSerializationExclusionStrategy(GSONStrategy)
        .addDeserializationExclusionStrategy(GSONStrategy)
        .registerTypeAdapter(AE2JobTracker.JobTrackingInfo.class, JOB_TRACKING_INFO_JSON_SERIALIZER)
        .serializeNulls();

}
