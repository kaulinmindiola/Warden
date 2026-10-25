package io.github.kaulinmindiola.warden.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Links a test to the SRS requirements it proves, for example {@code @Verifies("REQ-FUNC-013")}.
 *
 * <p>The traceability matrix references the test classes annotated with it. Retained at runtime
 * so the matrix can later be generated from the annotations.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Verifies {

    /** Requirement identifiers as written in the SRS. */
    String[] value();
}
