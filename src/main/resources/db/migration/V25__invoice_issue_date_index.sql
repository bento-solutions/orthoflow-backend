-- AUDIT-2026-09 M1: getBillingSummary() now filters invoices (and their
-- payments) by issue_date for the current-month figures instead of loading
-- every invoice into memory. Index the column it filters on.
CREATE INDEX IF NOT EXISTS idx_invoices_issue_date ON invoices (issue_date);
