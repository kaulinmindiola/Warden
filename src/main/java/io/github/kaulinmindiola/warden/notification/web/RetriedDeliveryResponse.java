package io.github.kaulinmindiola.warden.notification.web;

import io.github.kaulinmindiola.warden.notification.domain.DeliveryStatus;
import java.util.UUID;

/** The answer to a retry: the delivery is PENDING again and the worker will send it (REQ-FUNC-060). */
public record RetriedDeliveryResponse(UUID id, DeliveryStatus status) {}
