package pl.kuba6000.ae2webintegration.core.http.contract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The route shared by runtime dispatch and the build-time OpenAPI doclet. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Endpoint {

    HttpMethod method();

    String path();

    boolean authenticated() default true;
}
