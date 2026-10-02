"""Focused contracts for restoring a complete, nonempty source workspace."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import unittest
import zipfile


SPEC = importlib.util.spec_from_file_location('verify_backup', Path(__file__).with_name('verify-backup.py'))
qa = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(qa)


def archive(workspace, files=None):
    files = files or {'attachments/old/image.png': b'original image'}
    entries = {'workspace.json': json.dumps(workspace).encode(), **files}
    manifest = {'files': [{'path': path, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
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


if __name__ == '__main__':
    unittest.main()
