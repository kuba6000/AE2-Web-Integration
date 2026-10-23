package pl.kuba6000.ae2webintegration.core.http.contract;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines a JSON projection for a non-generic record with one component of the represented type.
 * The record and its enclosing types must be accessible to a same-package generated adapter.
 * Shared Gson encodes the component directly and constructs the record when decoding. Fields carrying
 * the specified runtime-retained annotation are excluded recursively in both directions. Ordinary
 * serialization of the represented type is unaffected. HTTP input validation uses the same projected
 * declared fields and additionally rejects unknown, missing required, or malformed members.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface InputShape {

    Class<?> value();

    Class<? extends Annotation> excludeFieldsAnnotatedWith();
}
