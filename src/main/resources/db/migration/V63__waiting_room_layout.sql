-- The waiting room as a room: where the seats physically are, and who is sitting in them.
--
-- Two concerns, deliberately kept in separate tables, because they change on
-- completely different clocks:
--
-- * waiting_room_furniture is the LAYOUT. A clinic draws it once (a bench here, a
--   sofa along that wall) and then leaves it alone for months. It is a property of
--   the room, not of any day's patients, so nothing about occupancy lives here.
--
-- * waiting_room_seat_assignments is OCCUPANCY. One row per patient-in-a-seat, many
--   per seat per day. Keeping it out of the furniture row is what lets a sofa be
--   dragged two metres left without anybody losing their seat.
--
-- A seat has no row of its own. It is identified by (furniture_id, seat_index), which
-- is stable under every edit that does not change capacity: moving, rotating and
-- reordering all leave it alone. Only shrinking a module can invalidate an index, and
-- the service refuses to do that while one of the dropped seats is occupied.
--
-- Treatment chairs (appointments.chair_id) are a different resource entirely. A seat
-- here is somewhere to sit before the appointment; a chair is where it happens.

CREATE TABLE waiting_room_furniture (
    id              UUID        PRIMARY KEY,
    practice_id     UUID        NOT NULL REFERENCES practices(id),
    waiting_room_id UUID        NOT NULL REFERENCES waiting_rooms(id) ON DELETE CASCADE,
    kind            VARCHAR(10) NOT NULL CHECK (kind IN ('BENCH', 'SOFA')),
    -- One to five, matching the seating the designer can draw.
    seat_count      INTEGER     NOT NULL CHECK (seat_count BETWEEN 1 AND 5),
    -- Top-left corner in room units (the canvas grid), not pixels: the view zooms,
    -- the stored layout does not.
    pos_x           INTEGER     NOT NULL,
    pos_y           INTEGER     NOT NULL,
    rotation        INTEGER     NOT NULL DEFAULT 0 CHECK (rotation IN (0, 90, 180, 270)),
    display_order   INTEGER     NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_wr_furniture_room ON waiting_room_furniture (practice_id, waiting_room_id, display_order);

CREATE TABLE waiting_room_seat_assignments (
    id             UUID        PRIMARY KEY,
    practice_id    UUID        NOT NULL REFERENCES practices(id),
    -- Dropping a module takes its seating history with it. The service blocks the
    -- delete while anybody is still sitting there, so this only ever cascades over
    -- rows that were already released.
    furniture_id   UUID        NOT NULL REFERENCES waiting_room_furniture(id) ON DELETE CASCADE,
    seat_index     INTEGER     NOT NULL CHECK (seat_index >= 0),
    appointment_id UUID        NOT NULL REFERENCES appointments(id) ON DELETE CASCADE,
    assigned_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- NULL means "sitting there now". Set when the patient is called in, leaves, moves
    -- seat, or has their arrival undone. Released rows are kept: they are how the
    -- waiting-time figures are reconstructed.
    released_at    TIMESTAMPTZ
);

-- Two people cannot be in one seat, and one person cannot be in two. Both are partial:
-- they constrain the live rows only, so a seat can be reused all day.
CREATE UNIQUE INDEX uq_wr_seat_live ON waiting_room_seat_assignments (furniture_id, seat_index)
    WHERE released_at IS NULL;
CREATE UNIQUE INDEX uq_wr_appointment_live ON waiting_room_seat_assignments (appointment_id)
    WHERE released_at IS NULL;

CREATE INDEX idx_wr_assign_live ON waiting_room_seat_assignments (practice_id, released_at)
    WHERE released_at IS NULL;
