package pl.kuba6000.ae2webintegration.core.http;

import org.jetbrains.annotations.Nullable;

import com.google.gson.TypeAdapter;

/** Generated HTTP bindings and JSON projection adapters from one compilation, discovered once. */
public interface HttpBindingCatalog {

    @Nullable
    HttpBinding get(Class<?> type);

    @Nullable
    TypeAdapter<?> adapter(Class<?> type);
}
