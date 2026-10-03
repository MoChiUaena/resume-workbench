CREATE TABLE job_reports (
    id UUID PRIMARY KEY,
    resume_id UUID NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
    preview_id UUID NOT NULL,
    label VARCHAR(120) NOT NULL,
    source_revision BIGINT NOT NULL CHECK (source_revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    snapshot JSONB,
    deleted_at TIMESTAMPTZ,
    CHECK ((deleted_at IS NULL AND snapshot IS NOT NULL AND jsonb_typeof(snapshot) = 'object')
        OR (deleted_at IS NOT NULL AND snapshot IS NULL))
);
CREATE INDEX job_reports_by_resume ON job_reports(resume_id,created_at DESC,id) WHERE deleted_at IS NULL;
