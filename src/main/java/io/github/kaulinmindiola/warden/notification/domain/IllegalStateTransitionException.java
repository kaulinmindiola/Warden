package io.github.kaulinmindiola.warden.notification.domain;

/** A delivery was asked to move to a state that REQ-FUNC-058 does not allow from its current one. */
public class IllegalStateTransitionException extends IllegalStateException {

    private final DeliveryStatus from;
    private final DeliveryStatus to;

    public IllegalStateTransitionException(DeliveryStatus from, DeliveryStatus to) {
        super("A delivery cannot move from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public DeliveryStatus from() {
        return from;
    }

    public DeliveryStatus to() {
        return to;
    }
}
