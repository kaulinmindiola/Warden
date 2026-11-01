package io.github.kaulinmindiola.warden.shared.time;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Registers the annotated bean only while background workers are switched on ({@code
 * warden.scheduling.enabled}, true unless set). Every bean that declares a {@code @Scheduled}
 * method carries it, so that the switch removes the ticks themselves: turning off {@code
 * @EnableScheduling} alone is not enough, because a library on the classpath can enable
 * scheduling on its own.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = "warden.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public @interface ConditionalOnSchedulingEnabled {}
