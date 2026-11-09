package pl.kuba6000.ae2webintegration.core.http.contract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A JSON input member may be omitted; an explicitly supplied null remains invalid. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface OptionalInput {}
