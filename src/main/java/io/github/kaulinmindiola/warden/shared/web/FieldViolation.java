package io.github.kaulinmindiola.warden.shared.web;

/** One entry of the {@code errors} member of a {@code validation-error} problem. */
public record FieldViolation(String field, String reason) {}
