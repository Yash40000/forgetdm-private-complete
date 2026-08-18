ALTER TABLE classifications ADD COLUMN content_format VARCHAR(40);
ALTER TABLE classifications ADD COLUMN logical_paths VARCHAR(4000);
ALTER TABLE classifications ADD COLUMN path_review_required BOOLEAN DEFAULT FALSE NOT NULL;
