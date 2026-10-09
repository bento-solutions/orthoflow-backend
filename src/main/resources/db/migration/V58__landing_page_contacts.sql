-- WhatsApp through the bento CRM's own Baileys session (decision D3, revised
-- 2026-10-09): the clinic shares a number whose other conversations are none of
-- its business. What OrthoFlow keeps from that number is limited to the people who
-- reached the clinic through its landing page.
--
-- * patients.acquisition_channel: where a patient first came from, when the
--   application knows it (null: entered by staff). LANDING_PAGE is the flag the
--   patient list filters on.
-- * booking_requests.source: which page a request was made on. The landing page
--   books through the same public booking API, naming itself.
-- * landing_page_contacts: numbers that reached the clinic through the landing page
--   before (or without) becoming a patient: a booking request made there, or a
--   WhatsApp message opened from its click-to-chat link. phone_key is the last nine
--   digits, as everywhere numbers are matched (phone_digits()).
-- * message_events.from_landing_page: whether a stored inbound message came from
--   such a contact, so the inbox can show only those.

ALTER TABLE patients ADD COLUMN acquisition_channel VARCHAR(20)
    CHECK (acquisition_channel IN ('LANDING_PAGE', 'BOOKING_PAGE'));
CREATE INDEX idx_patients_acquisition ON patients (practice_id, acquisition_channel) WHERE acquisition_channel IS NOT NULL;

ALTER TABLE booking_requests ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'BOOKING_PAGE'
    CHECK (source IN ('BOOKING_PAGE', 'LANDING_PAGE'));

CREATE TABLE landing_page_contacts (
    practice_id   UUID        NOT NULL REFERENCES practices(id),
    phone_key     VARCHAR(9)  NOT NULL,
    source        VARCHAR(20) NOT NULL CHECK (source IN ('BOOKING', 'WHATSAPP_LINK')),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (practice_id, phone_key)
);

ALTER TABLE message_events ADD COLUMN from_landing_page BOOLEAN NOT NULL DEFAULT FALSE;
