-- Keep every V3 DOCX receipt while allowing both reviewed source formats to retry.
ALTER TABLE docx_imports RENAME TO document_imports;
ALTER INDEX docx_imports_pkey RENAME TO document_imports_pkey;
ALTER INDEX docx_imports_by_resume RENAME TO document_imports_by_resume;
