package com.nnpg.alice.premium;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Replacement for @EventHandler on premium modules loaded by custom classloader.
 * Methods with this annotation are subscribed via reflection instead of LambdaMetafactory.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AliceHandler {
    int priority() default 0;
}
