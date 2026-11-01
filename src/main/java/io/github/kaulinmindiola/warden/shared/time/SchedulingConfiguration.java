package io.github.kaulinmindiola.warden.shared.time;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the in-process workers ({@code @Scheduled} methods) of every module (SDD V02). They
 * run in every instance; exclusion, where needed, happens in the database (SDD V16).
 *
 * <p>{@code warden.scheduling.enabled=false} switches them off. The test classpath does so, and
 * tests call the workers directly, so no tick runs at an unpredictable moment (plan 12.2). The
 * switch acts on the beans that hold the {@code @Scheduled} methods, all marked {@link
 * ConditionalOnSchedulingEnabled}, and not only on this class: scheduling can be enabled by a
 * library even when this class is skipped.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnSchedulingEnabled
@EnableScheduling
public class SchedulingConfiguration {}
