"""Focused contracts for restoring a complete, nonempty source workspace."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SPEC = importlib.util.spec_from_file_location('verify_backup', Path(__file__).with_name('verify-backup.py'))
qa = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(qa)


def archive(workspace, files=None):
    files = files or {'attachments/old/image.png': b'original image'}
    entries = {'workspace.json': json.dumps(workspace).encode(), **files}
    manifest = {'format': 'resume-workbench-backup', 'formatVersion': 1,
                'files': [{'path': path, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                          for path, data in entries.items()]}
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w') as output:
        for path, data in entries.items():
            output.writestr(path, data)
        output.writestr('manifest.json', json.dumps(manifest))
    return stream.getvalue()


class BackupVerifierContractTest(unittest.TestCase):
    def setUp(self):
        self.baseline = {
            'resumes': [{'id': 'old-a', 'title': 'canary', 'revision': 1},
                        {'id': 'old-b', 'title': 'canary', 'revision': 2}],
            'versions': [{'id': 'version-a', 'resumeId': 'old-a', 'document': {'content': 'history'}}],
            'attachments': [{'id': 'old', 'sha256': 'original'}],
            'exports': [{'id': 'export-a', 'sha256': 'pdf'}],
        }

    def test_baseline_rows_and_files_survive_new_record(self):
        fingerprint = qa.baseline_fingerprints(archive(self.baseline))
        later = {key: list(value) for key, value in self.baseline.items()}
        later['resumes'].append({'id': 'new', 'title': 'Stage C', 'revision': 3})
        qa.assert_baseline_preserved(fingerprint, archive(later))
        changed = {key: list(value) for key, value in later.items()}
        changed['versions'][0] = {'id': 'version-a', 'resumeId': 'old-a', 'document': {'content': 'changed'}}
        with self.assertRaisesRegex(AssertionError, 'version'):
            qa.assert_baseline_preserved(fingerprint, archive(changed))
        with self.assertRaisesRegex(AssertionError, 'file'):
            qa.assert_baseline_preserved(fingerprint, archive(later, {'attachments/old/image.png': b'changed'}))

    def test_baseline_file_fingerprint_checks_actual_archive_bytes(self):
        original = archive(self.baseline)
        changed = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(original)) as source, zipfile.ZipFile(changed, 'w') as output:
            for name in source.namelist():
                output.writestr(name, b'changed' if name == 'attachments/old/image.png' else source.read(name))
        with self.assertRaisesRegex(AssertionError, 'Archive file'):
            qa.baseline_fingerprints(changed.getvalue())

    def test_complete_restore_counts_and_unique_stage_c_mapping(self):
        later = {key: list(value) for key, value in self.baseline.items()}
        later['resumes'].append({'id': 'new', 'title': 'Stage C unique', 'revision': 3})
        counts = {'resumes': 3, 'versions': 1, 'attachments': 1, 'exports': 1}
        self.assertEqual(qa.backup_counts(later), counts)
        qa.assert_restore_counts(counts, {**counts, 'resumeIds': ['copy-a', 'copy-b', 'copy-new']})
        with self.assertRaises(AssertionError):
            qa.assert_restore_counts(counts, {**counts, 'attachments': 0,
                                               'resumeIds': ['copy-a', 'copy-b', 'copy-new']})
        imported = [{'id': 'copy-a', 'title': 'canary'}, {'id': 'copy-b', 'title': 'canary'},
                    {'id': 'copy-new', 'title': 'Stage C unique'}]
        self.assertEqual(qa.stage_c_record(imported, 'Stage C unique')['id'], 'copy-new')
        with self.assertRaises(AssertionError):
            qa.stage_c_record(imported + [{'id': 'copy-new-2', 'title': 'Stage C unique'}], 'Stage C unique')

    def test_real_workspace_port_is_rejected_before_any_request(self):
        self.assertEqual(qa.isolated_port('http://127.0.0.1:18767'), 18767)
        with self.assertRaisesRegex(AssertionError, '18765'):
            qa.isolated_port('http://127.0.0.1:18765')

    def test_persistence_keeps_baseline_and_stage_c_with_later_records(self):
        expected = {'baseline-a', 'baseline-b'}
        qa.assert_source_membership(expected, 'stage-c', expected | {'stage-c', 'webp-one', 'webp-two'})
        with self.assertRaisesRegex(AssertionError, 'missing'):
            qa.assert_source_membership(expected, 'stage-c', {'baseline-a', 'stage-c', 'webp-one'})
        with self.assertRaisesRegex(AssertionError, 'missing'):
            qa.assert_source_membership(expected, 'stage-c', expected | {'webp-one'})

    def test_persistence_rejects_foreign_or_same_report_endpoint_before_network(self):
        cases = [
            ('http://127.0.0.1:18765', 'http://127.0.0.1:18769', '18765'),
            ('http://127.0.0.1:18767', 'http://127.0.0.1:18765', '18765'),
            ('http://localhost:18767', 'http://127.0.0.1:18767', 'different'),
            ('http://127.0.0.1:18771', 'http://127.0.0.1:18769', 'report'),
        ]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            for source, target, message in cases:
                (output / 'stage-c-backup-verification.json').write_text(
                    json.dumps({'source': source, 'target': target}), encoding='utf-8')
                with self.subTest(source=source, target=target), patch.object(qa, 'OUT', output), \
                     patch.object(qa, 'archive', side_effect=AssertionError('Network reached')):
                    with self.assertRaisesRegex(AssertionError, message):
                        qa.persistence()

    def test_canonical_restore_preserves_history_and_pdf_owners_with_duplicate_titles(self):
        def workspace(prefix):
            resumes = [
                {'id': prefix + 'a', 'title': 'same title', 'revision': 2, 'createdAt': '2026-01-01T00:00:00Z',
                 'document': {'layout': {'photo': {'id': None}, 'logo': {'id': None}}}},
                {'id': prefix + 'b', 'title': 'same title', 'revision': 2, 'createdAt': '2026-01-02T00:00:00Z',
                 'document': {'layout': {'photo': {'id': None}, 'logo': {'id': None}}}},
            ]
            versions = [
                {'id': prefix + 'v1', 'resumeId': prefix + 'a', 'label': 'history A',
                 'document': {'layout': {'photo': {'id': None}, 'logo': {'id': None}}}},
                {'id': prefix + 'v2', 'resumeId': prefix + 'b', 'label': 'history B',
                 'document': {'layout': {'photo': {'id': None}, 'logo': {'id': None}}}},
            ]
            exports = [
                {'id': prefix + 'e1', 'resumeId': prefix + 'a', 'versionId': prefix + 'v1', 'sha256': 'pdf-a'},
                {'id': prefix + 'e2', 'resumeId': prefix + 'b', 'versionId': prefix + 'v2', 'sha256': 'pdf-b'},
            ]
            return {'schemaVersion': 1, 'resumes': resumes, 'versions': versions,
                    'attachments': [], 'exports': exports}

        source = workspace('source-')
        target = workspace('target-')
        expected = qa.canonical_zip(archive(source))
        self.assertEqual(expected, qa.canonical_zip(archive(target)))
        target['versions'][0]['resumeId'] = 'target-b'
        self.assertNotEqual(expected, qa.canonical_zip(archive(target)), 'Swapped history owner must differ')
        target = workspace('target-')
        target['exports'][0]['versionId'] = 'target-v2'
        self.assertNotEqual(expected, qa.canonical_zip(archive(target)), 'Swapped PDF version must differ')
        target = workspace('target-')
        target['exports'][0]['resumeId'] = 'target-b'
        self.assertNotEqual(expected, qa.canonical_zip(archive(target)), 'Swapped PDF resume must differ')

    def test_saved_reports_normalize_new_ids_but_preserve_their_owner_and_metadata(self):
        def bundle(prefix, owner='a', label='saved report'):
            workspace = {'schemaVersion': 5, 'resumes': [
                {'id': prefix + item, 'title': item, 'revision': 1,
                 'document': {'layout': {'photo': {'id': None}, 'logo': {'id': None}}}}
                for item in ['a', 'b']], 'versions': [], 'attachments': [], 'exports': []}
            payload = json.dumps({'schemaVersion': 1, 'sourceRevision': 1,
                                  'jobDescription': 'Java role', 'sources': []}).encode()
            workspace['jobReports'] = [{'id': prefix + 'report', 'resumeId': prefix + owner,
                'label': label, 'sourceRevision': 1, 'createdAt': '2026-10-01T00:00:00Z',
                'sha256': hashlib.sha256(payload).hexdigest()}]
            return archive(workspace, {'job-reports/' + prefix + 'report.json': payload})

        source, target = bundle('source-'), bundle('target-')
        self.assertEqual(qa.canonical_zip(source), qa.canonical_zip(target))
        self.assertNotEqual(qa.canonical_zip(source), qa.canonical_zip(bundle('target-', owner='b')))
        with self.assertRaisesRegex(AssertionError, 'jobReports'):
            qa.assert_baseline_preserved(qa.baseline_fingerprints(source), bundle('source-', label='changed'))


if __name__ == '__main__':
    unittest.main()
