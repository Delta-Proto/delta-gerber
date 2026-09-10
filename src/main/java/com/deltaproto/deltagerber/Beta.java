package com.deltaproto.deltagerber;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks API that ships before it has been validated against an independent reference.
 *
 * <p>A beta figure is a real measurement with tests behind it, but nobody outside this library
 * has yet confirmed its numbers on real boards. Its values may change as the rules are tuned, and
 * it should not be the sole basis of a quote or a rejection. The annotation is removed once an
 * external DFM tool agrees with it across the corpus.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Beta {
    /** What is still missing before the mark comes off. */
    String value() default "";
}
