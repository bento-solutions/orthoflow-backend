-- A compound surface — "mesial-occlusal", "mesial-occlusal-distal" — is one
-- finding on several faces of the tooth. It used to be cut down to one of them
-- on the way in; it is now stored joined, and three of the longest names need
-- more than the 24 characters the column was given for a single surface.
ALTER TABLE tooth_findings ALTER COLUMN surface TYPE VARCHAR(48);
