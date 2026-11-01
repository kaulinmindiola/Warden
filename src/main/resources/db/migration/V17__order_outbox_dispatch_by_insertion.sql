-- Dispatch order of the outbox (REQ-FUNC-062, SDD V13). Events were handed over by occurred_at and
-- id, but two events of the same millisecond have no defined order (UUIDv7 is random within a
-- millisecond), so a cancellation could be handed over before the invitation it cancels. A
-- sequence assigned at insertion orders the events of one transaction, and of transactions that
-- follow each other, as they were written. Existing rows receive values in physical order.

ALTER TABLE outbox_event ADD COLUMN dispatch_order bigint GENERATED ALWAYS AS IDENTITY;

DROP INDEX outbox_event_pending_idx;

-- The dispatcher polls only pending events, in insertion order.
CREATE INDEX outbox_event_pending_idx ON outbox_event (dispatch_order) WHERE published_at IS NULL;