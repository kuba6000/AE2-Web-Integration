package com.kuba6000.ae2webintegration.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSerializer;
import com.kuba6000.ae2webintegration.api.JSON_Item;

import appeng.api.storage.data.IAEItemStack;

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

    private static final JsonSerializer<IAEItemStack> AE2_ITEM_STACK_JSON_SERIALIZER = (src, typeOfSrc,
        context) -> context.serialize(JSON_Item.create(src));

    public static final GsonBuilder GSON_BUILDER = new GsonBuilder().addSerializationExclusionStrategy(GSONStrategy)
        .addDeserializationExclusionStrategy(GSONStrategy)
        .registerTypeAdapter(IAEItemStack.class, AE2_ITEM_STACK_JSON_SERIALIZER)
        .serializeNulls();

}
