package pl.kuba6000.ae2webintegration.core.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;

import pl.kuba6000.ae2webintegration.core.interfaces.IStack;

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

    private static final JsonSerializer<IStack> IItemStackSerializer = (src, typeOfSrc, context) -> {
        JsonObject json = new JsonObject();
        json.addProperty("itemid", src.web$getItemID());
        json.addProperty("itemname", src.web$getDisplayName());
        json.addProperty("hashcode", src.hashCode());
        json.addProperty("quantity", src.web$getStackSize());
        return json;
    };

    public static final GsonBuilder GSON_BUILDER = new GsonBuilder().addSerializationExclusionStrategy(GSONStrategy)
        .addDeserializationExclusionStrategy(GSONStrategy)
        .registerTypeHierarchyAdapter(IStack.class, IItemStackSerializer)
        .serializeNulls();

}
