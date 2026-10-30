/**
 * Classes that break the architecture rules on purpose, so that {@code ArchitectureTest} can show
 * that each rule fails on a violation. They mirror the module layout under their own base package,
 * are never loaded by the application and are excluded from the rules applied to production code.
 */
package io.github.kaulinmindiola.warden.architecture.fixture;
