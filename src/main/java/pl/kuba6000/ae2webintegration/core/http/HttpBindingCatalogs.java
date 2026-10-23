package pl.kuba6000.ae2webintegration.core.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

/** Shares one discovery of generated HTTP bindings and JSON projection adapters. */
public final class HttpBindingCatalogs {

    public static final List<HttpBindingCatalog> ALL = load();

    private HttpBindingCatalogs() {}

    private static List<HttpBindingCatalog> load() {
        List<HttpBindingCatalog> catalogs = new ArrayList<>();
        ServiceLoader.load(HttpBindingCatalog.class, HttpBindingCatalog.class.getClassLoader())
            .forEach(catalogs::add);
        return Collections.unmodifiableList(catalogs);
    }
}
