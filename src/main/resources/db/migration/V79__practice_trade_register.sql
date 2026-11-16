-- The trade register number (Registre de Commerce) printed in every document's legal footer,
-- next to the ICE, IF, Patente and CNSS numbers the profile already holds.
ALTER TABLE practices ADD COLUMN rc VARCHAR(30);
