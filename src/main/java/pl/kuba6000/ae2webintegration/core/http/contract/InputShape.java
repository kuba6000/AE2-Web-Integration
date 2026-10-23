package pl.kuba6000.ae2webintegration.core.http.contract;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes the JSON object validated for a wrapper input. Validation uses the declared fields of the
 * represented class or record, excluding fields carrying the specified annotation throughout its nested
 * object graph. Ordinary inputs of the represented type remain unaffected. This does not change Gson
 * decoding: the wrapper still needs its corresponding JSON representation or adapter.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface InputShape {

    Class<?> value();

    Class<? extends Annotation> excludeFieldsAnnotatedWith();
}
