-- An acknowledgement covers the state the director saw: the row, the reason and what the row looked like (the stored
-- hash and the recomputed one). A later, different edit of the same row is a new break. Rows acknowledged before this
-- column existed hold '' and cover nothing (ADR-0079).
ALTER TABLE journal_chain_ack ADD COLUMN observed TEXT NOT NULL DEFAULT '';
