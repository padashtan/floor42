-- Floor42 1.8.0 checks that the journal continues the one the last run saw (ADR-0044, amendment 3). A journal opened by
-- a Floor42 before 1.8.0 has nothing recorded yet: the first check, which has nothing to compare with, is expected
-- once and shown as a notice instead of an alarm. `checked` goes to 1 once a check ended without a break, or the
-- director acknowledged the notice. It is a hint for the presentation only, never a proof: whoever can rewrite the
-- journal can set it too (and a missing table or a checked journal gives the red alarm, the safe side).
CREATE TABLE continuity_epoch (
    id      INTEGER PRIMARY KEY CHECK (id = 1),
    checked INTEGER NOT NULL DEFAULT 0
);
INSERT INTO continuity_epoch (id, checked) VALUES (1, 0);
