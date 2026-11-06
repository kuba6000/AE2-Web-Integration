package pl.kuba6000.ae2webintegration.core.binding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

/** Shares one discovery of generated HTTP bindings and JSON projection adapters. */
public final class BindingCatalogs {

    public static final List<BindingCatalog> ALL = load();

    private BindingCatalogs() {}

    private static List<BindingCatalog> load() {
        List<BindingCatalog> catalogs = new ArrayList<>();
        ServiceLoader.load(BindingCatalog.class, BindingCatalog.class.getClassLoader())
            .forEach(catalogs::add);
        return Collections.unmodifiableList(catalogs);
    }
}
