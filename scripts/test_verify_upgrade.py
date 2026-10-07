"""Upgrade acceptance contracts, with controlled HTTP boundaries and no app access."""
import copy
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
import zipfile


SPEC = importlib.util.spec_from_file_location('verify_upgrade', Path(__file__).with_name('verify-upgrade.py'))
qa = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(qa)

BASE = 'http://127.0.0.1:18769'
MODELS = {'revision': 2, 'enabled': True, 'defaultId': 'profile-old', 'readable': True,
    'presets': [{'id': 'compatible', 'name': '其他兼容服务', 'baseUrl': 'https://api.example.com/v1', 'model': ''}],
    'profiles': [{
    'id': 'profile-old', 'name': 'Upgrade fixture', 'provider': 'compatible',
    'baseUrl': 'http://127.0.0.1:18770/v1', 'model': 'qa-model', 'hasApiKey': True}]}


class UpgradeTransport:
    """Only accepts the routes exercised by the actual after verifier."""
    def __init__(self):
        self.old = {'id': 'old', 'title': 'Upgrade fixture', 'revision': 2, 'updatedAt': '2026-09-01T00:00:00Z',
            'document': {'schemaVersion': 4, 'content': {'name': 'synthetic'}, 'layout': {
                'template': 'banner', 'font': 'serif', 'presentation': {'accentColor': '#7c3aed', 'marginTopMm': 20},
                'photo': {'id': 'photo'}, 'logo': {'id': 'logo'}}}}
        self.imported = copy.deepcopy(self.old)
        self.imported['id'] = 'restored'
        self.models = copy.deepcopy(MODELS)
        self.connected = True
        self.history_available = True
        self.manual_archive = b'old archive'
        self.pdf = b'%PDF-1.7 synthetic PDF bytes'
        self.state = {'base': BASE, 'sourceSchema': 4, 'sourceVersion': '0.7.0',
            'futureBackupRejected': True, 'automaticState': None, 'backupKind': 'manual', 'backupId': 'backup-old',
            'resume': self.old, 'assets': {'photo': {'id': 'photo'}, 'logo': {'id': 'logo'}},
            'export': {'id': 'pdf-old', 'sha256': hashlib.sha256(self.pdf).hexdigest()},
            'versions': [{'id': 'version-old', 'resumeId': 'old'}], 'modelState': copy.deepcopy(MODELS)}

    def call(self, base, route, body=None, method=None, raw=False):
        assert base == BASE, 'Verifier attempted a foreign endpoint'
        if route == '/api/models':
            return copy.deepcopy(self.models)
        if route == '/api/models/profiles/profile-old/test':
            assert body == {'expectedRevision': 2}
            return {'connected': self.connected, 'elapsedMs': 1}
        if route == '/api/backups':
            return {'items': [{'backup': {'id': 'backup-old'}, 'kind': 'manual'}]}
        if route == '/api/backups/automatic':
            return {'state': {'enabled': False}}
        if route == '/api/backups/backup-old/download':
            assert raw
            return self.manual_archive
        if route == '/api/resumes/old':
            return copy.deepcopy(self.old)
        if route == '/api/resumes/restored':
            return copy.deepcopy(self.imported)
        if route == '/api/resumes/old/versions':
            return copy.deepcopy(self.state['versions'])
        if route in ['/api/assets/photo', '/api/assets/logo']:
            return {'id': route.rsplit('/', 1)[1]}
        if route in ['/api/resumes/old/job-reports', '/api/resumes/restored/job-reports']:
            if not self.history_available:
                raise HTTPError(BASE + route, 404, 'missing history', {}, None)
            return {'items': [], 'total': 0}
        if route == '/api/resumes/restored/export/preview':
            return {'digest': 'preview-sha'}
        if route == '/api/resumes/restored/export':
            return {'id': 'pdf-new'}
        if route in ['/api/exports/pdf-old/pdf', '/api/exports/pdf-new/pdf']:
            assert raw
            return self.pdf
        raise AssertionError('Unexpected route: ' + route)

    def save(self, base, resume):
        assert base == BASE
        self.imported = copy.deepcopy(resume)
        self.imported['revision'] += 1
        return copy.deepcopy(self.imported)

    def upload(self, base, route, filename, content):
        assert (base, route, filename, content) == (BASE, '/api/backups/restore', 'old.zip', b'old archive')
        return {'resumeIds': ['restored']}


class UpgradeVerifierContractTest(unittest.TestCase):
    def run_after(self, transport):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'output').mkdir()
            state = root / 'output/upgrade-before-schema-4-0.7.0.json'
            state.write_text(json.dumps(transport.state), encoding='utf-8')
            (root / 'output/upgrade-schema-4-0.7.0.zip').write_bytes(b'old archive')
            with patch.object(qa, 'ROOT', root), patch.object(qa, 'STATE', state), \
                 patch.object(qa, 'SUFFIX', 'schema-4-0.7.0'), \
                 patch.object(qa.qa, 'call', transport.call), patch.object(qa.qa, 'save', transport.save), \
                 patch.object(qa.qa, 'upload', transport.upload):
                with contextlib.redirect_stdout(io.StringIO()):
                    qa.after(BASE)
            return json.loads((root / 'output/upgrade-verification-schema-4-0.7.0.json').read_text(encoding='utf-8'))

    def test_changed_instance_model_config_fails_upgrade_acceptance(self):
        transport = UpgradeTransport()
        transport.models['enabled'] = False
        with self.assertRaisesRegex(AssertionError, 'model'):
            self.run_after(transport)

    def test_undecryptable_stored_key_fails_upgrade_acceptance(self):
        transport = UpgradeTransport()
        transport.connected = False
        with self.assertRaisesRegex(AssertionError, 'key|credential'):
            self.run_after(transport)

    def test_old_schema_restore_must_expose_new_report_history(self):
        transport = UpgradeTransport()
        transport.history_available = False
        with self.assertRaises(HTTPError):
            self.run_after(transport)

    def test_preserved_model_key_and_old_schema_history_are_reported(self):
        report = self.run_after(UpgradeTransport())
        self.assertTrue(report.get('instanceModelSettingsPreserved'))
        self.assertTrue(report.get('storedModelKeyDecryptable'))
        self.assertTrue(report.get('oldSchemaRestoreExposesReportHistory'))
        self.assertEqual(report.get('sourceVersion'), '0.7.0')

    def test_foreign_model_endpoint_is_rejected_before_connection_test(self):
        transport = UpgradeTransport()
        transport.models['profiles'][0]['baseUrl'] = 'https://example.invalid/v1'
        transport.state['modelState'] = copy.deepcopy(transport.models)
        with self.assertRaisesRegex(AssertionError, 'fixture'):
            self.run_after(transport)

    def test_pre_upgrade_manual_archive_bytes_cannot_change(self):
        transport = UpgradeTransport()
        transport.state['backupSha256'] = hashlib.sha256(b'old archive').hexdigest()
        transport.manual_archive = b'changed archive'
        with self.assertRaisesRegex(AssertionError, 'archive bytes changed'):
            self.run_after(transport)

    def test_before_records_synthetic_models_and_schema_four_archive(self):
        self.check_model_source('0.7.0', 4)

    def test_before_records_published_eight_models_and_schema_five_archive(self):
        self.check_model_source('0.8.0', 5)

    def check_model_source(self, source_version, backup_schema):
        transport = UpgradeTransport()
        profile = copy.deepcopy(MODELS)
        initial = {'revision': 0, 'enabled': False, 'defaultId': None, 'profiles': [],
                   'readable': True, 'presets': copy.deepcopy(MODELS['presets'])}
        content = io.BytesIO()
        workspace = json.dumps({'schemaVersion': backup_schema}).encode()
        manifest = {'files': [{'path': 'workspace.json', 'bytes': len(workspace),
                               'sha256': hashlib.sha256(workspace).hexdigest()}]}
        with zipfile.ZipFile(content, 'w') as bundle:
            bundle.writestr('workspace.json', workspace)
            bundle.writestr('manifest.json', json.dumps(manifest))
        requests = []

        def call(base, route, body=None, method=None, raw=False):
            assert base == BASE
            if route == '/api/resumes':
                return [] if body is None else copy.deepcopy(transport.old)
            if route == '/api/resumes/old/versions':
                return copy.deepcopy(transport.state['versions'])
            if route == '/api/resumes/old/export':
                return copy.deepcopy(transport.state['export'])
            if route == '/api/models':
                return copy.deepcopy(initial)
            if route == '/api/models/profiles':
                requests.append(copy.deepcopy(body))
                return {**copy.deepcopy(profile), 'enabled': False, 'revision': 1}
            if route == '/api/models/enabled':
                assert method == 'PUT' and body == {'expectedRevision': 1, 'enabled': True}
                return copy.deepcopy(profile)
            return transport.call(base, route, body, method, raw)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = root / 'output/upgrade-before.json'
            with patch.object(qa, 'ROOT', root), patch.object(qa, 'STATE', state), \
                 patch.object(qa.qa, 'call', call), patch.object(qa.qa, 'save', lambda base, resume: resume), \
                 patch.object(qa.qa, 'archive', return_value=({'id': 'backup-old'}, content.getvalue())):
                try:
                    with contextlib.redirect_stdout(io.StringIO()):
                        qa.before(BASE, 4, source_version=source_version, model_enabled=True)
                except TypeError as error:
                    self.fail('Missing 0.7 model preparation contract: ' + str(error))
            recorded = json.loads(state.read_text(encoding='utf-8'))
        self.assertEqual(recorded['modelState'], MODELS)
        self.assertEqual(recorded['sourceVersion'], source_version)
        self.assertEqual(recorded['backupSchema'], backup_schema)
        self.assertEqual(requests, [{'expectedRevision': 0, 'name': '升级前模型持久化 fixture',
            'provider': 'compatible', 'baseUrl': 'http://127.0.0.1:18770/v1', 'model': 'qa-model',
            'apiKey': 'upgrade-fixture-only', 'clearKey': False}])
        self.assertNotIn('upgrade-fixture-only', json.dumps(recorded))


if __name__ == '__main__':
    unittest.main()
