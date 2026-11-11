-- What became of each sentence of a consultation (acted, proposed, heard, not
-- understood, …) and how long the verdict took after the end of speech: the
-- consultation log shows it, and a bad session can be read back sentence by
-- sentence. Both nullable: lines recorded before this have neither.
ALTER TABLE consultation_transcript_lines ADD COLUMN verdict VARCHAR(24);
ALTER TABLE consultation_transcript_lines ADD COLUMN latency_ms INTEGER;
