CREATE TABLE attachments (
    id UUID PRIMARY KEY,
    metadata JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE resumes (
    id UUID PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    document JSONB NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    last_mutation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE resume_versions (
    id UUID PRIMARY KEY,
    resume_id UUID NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
    title VARCHAR(120) NOT NULL,
    label VARCHAR(120) NOT NULL,
    document JSONB NOT NULL,
    source_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX versions_by_resume ON resume_versions(resume_id, created_at DESC);
CREATE TABLE resume_assets (
    resume_id UUID NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
    slot VARCHAR(10) NOT NULL CHECK (slot IN ('photo', 'logo')),
    asset_id UUID NOT NULL REFERENCES attachments(id),
    PRIMARY KEY (resume_id, slot)
);
CREATE TABLE version_assets (
    version_id UUID NOT NULL REFERENCES resume_versions(id) ON DELETE CASCADE,
    slot VARCHAR(10) NOT NULL CHECK (slot IN ('photo', 'logo')),
    asset_id UUID NOT NULL REFERENCES attachments(id),
    PRIMARY KEY (version_id, slot)
);
