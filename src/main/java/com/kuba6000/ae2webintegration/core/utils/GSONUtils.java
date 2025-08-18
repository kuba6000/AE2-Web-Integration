package com.kuba6000.ae2webintegration.core.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import com.kuba6000.ae2webintegration.core.interfaces.IAEKey;

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

    private static final JsonSerializer<IAEGenericStack> IItemStackSerializer = (src, typeOfSrc, context) -> {
        JsonObject json = new JsonObject();
        IAEKey key = src.web$what();
        json.addProperty("itemid", key.web$getItemID());
        json.addProperty("itemname", key.web$getDisplayName());
        json.addProperty("hashcode", key.hashCode());
        json.addProperty("quantity", src.web$amount());
        return json;
    };

    public static final GsonBuilder GSON_BUILDER = new GsonBuilder().addSerializationExclusionStrategy(GSONStrategy)
        .addDeserializationExclusionStrategy(GSONStrategy)
        .registerTypeHierarchyAdapter(IAEGenericStack.class, IItemStackSerializer)
        .serializeNulls();

}
