-- Add total_sentences column to books table for reading progress tracking
ALTER TABLE books ADD COLUMN IF NOT EXISTS total_sentences int DEFAULT 0;
