package pl.kuba6000.ae2webintegration.core.binding;

import org.jetbrains.annotations.Nullable;

import com.google.gson.TypeAdapter;

import pl.kuba6000.ae2webintegration.core.http.HttpBinding;

/** Generated HTTP bindings and JSON projection adapters from one compilation, discovered once. */
public interface BindingCatalog {

    @Nullable
    HttpBinding get(Class<?> type);

    @Nullable
    TypeAdapter<?> adapter(Class<?> type);
}
