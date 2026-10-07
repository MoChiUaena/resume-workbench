-- Instance-local receipts: deliberately excluded from portable workspace backups.
CREATE TABLE docx_imports (
    mutation_id UUID PRIMARY KEY,
    request_sha256 CHAR(64) NOT NULL,
    resume_id UUID REFERENCES resumes(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX docx_imports_by_resume ON docx_imports(resume_id);
