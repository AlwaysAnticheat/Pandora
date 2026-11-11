package com.pandora.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Reconstructed directly from the injected DEX. */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface Copyright {
    String owner();

    String qqGroup() default "";

    String warning() default "DPatch All rights reserved";
}
