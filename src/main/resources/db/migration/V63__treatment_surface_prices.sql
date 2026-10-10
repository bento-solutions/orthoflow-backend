-- Price by surface. A restoration is charged by how many faces of the tooth it
-- covers (one face, two faces, three...), not by which face: the dentist's own
-- tariff reads "composite 1 face / 2 faces / 3 faces". treatments.base_price stays the
-- price when nothing says where on the tooth, and for a treatment with no rule
-- for the faces involved.
--
-- The faces are the five of standard charting (mesial, distal, buccal, lingual,
-- occlusal/incisal) plus cervical; "proximal" is not a face, it is mesial + distal
-- (SurfacePricing), so one lesion can never be priced three ways at once.
-- No prices are shipped: the clinic enters its own tariff.

CREATE TABLE treatment_surface_prices (
    id            UUID          PRIMARY KEY,
    practice_id   UUID          NOT NULL REFERENCES practices(id),
    treatment_id  UUID          NOT NULL REFERENCES treatments(id) ON DELETE CASCADE,
    surface_count INTEGER       NOT NULL CHECK (surface_count BETWEEN 1 AND 5),
    price         NUMERIC(12,2) NOT NULL CHECK (price >= 0),
    UNIQUE (treatment_id, surface_count)
);
CREATE INDEX idx_treatment_surface_prices_treatment ON treatment_surface_prices (treatment_id);
