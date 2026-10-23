package pl.kuba6000.ae2webintegration.core.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Restricts this configuration field in the server-settings API only. Unannotated fields are editable. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface WebSetting {

    Access value();

    enum Access {
        FILE_ONLY,
        SECRET
    }
}
