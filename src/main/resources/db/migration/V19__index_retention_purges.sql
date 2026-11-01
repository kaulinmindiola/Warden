-- Indexes for the daily retention purges (REQ-FUNC-063, SDD V06). Published outbox events and
-- single-use tokens accumulate between purges; each purge reads only the rows past its cutoff.

CREATE INDEX outbox_event_published_idx ON outbox_event (published_at) WHERE published_at IS NOT NULL;

CREATE INDEX one_time_token_expires_idx ON one_time_token (expires_at);