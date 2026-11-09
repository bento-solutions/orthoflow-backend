-- Indexes for the reports added with the stock and agenda work (audit P2).
-- The leakage and forecast reports filter stock movements by clinic and date
-- and join them to the treatment that caused them; the agenda reads one
-- clinic's appointments in a window. Only stock_item_id and single columns
-- were indexed, so each of these read the clinic's whole history.
CREATE INDEX IF NOT EXISTS idx_stock_movements_practice_time ON stock_movements (practice_id, created_at);
CREATE INDEX IF NOT EXISTS idx_stock_movements_source ON stock_movements (source_type, source_id);
CREATE INDEX IF NOT EXISTS idx_appointments_practice_time ON appointments (practice_id, date_time);
