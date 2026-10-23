package pl.kuba6000.ae2webintegration.core.http;

import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;

/** Resolves generated endpoint bindings while leaving internal requests unbound. */
public final class RequestInputs {

    private RequestInputs() {}

    public static @Nullable HttpBinding find(Class<?> type) {
        for (HttpBindingCatalog catalog : HttpBindingCatalogs.ALL) {
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
