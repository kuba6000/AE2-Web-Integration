package pl.kuba6000.ae2webintegration.core.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;

/** Resolves generated endpoint bindings while leaving internal requests unbound. */
public final class RequestInputs {

    private static final List<HttpBindingCatalog> CATALOGS = loadCatalogs();

    private RequestInputs() {}

    private static List<HttpBindingCatalog> loadCatalogs() {
        List<HttpBindingCatalog> catalogs = new ArrayList<>();
        ServiceLoader.load(HttpBindingCatalog.class, HttpBindingCatalog.class.getClassLoader())
            .forEach(catalogs::add);
        return Collections.unmodifiableList(catalogs);
    }

    public static @Nullable HttpBinding find(Class<?> type) {
        for (HttpBindingCatalog catalog : CATALOGS) {
            HttpBinding binding = catalog.get(type);
            if (binding != null) return binding;
        }
        if (type.isAnnotationPresent(Endpoint.class))
            throw new IllegalStateException("Missing generated HTTP binding: " + type.getName());
        return null;
    }

    public static void bind(Object request, RequestContext context) {
        HttpBinding binding = find(request.getClass());
        if (binding != null) binding.inputs()
            .accept(request, new InputValues(context, binding.path()));
    }
}
