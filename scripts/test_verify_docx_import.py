"""Import-verifier contracts that run without a service or browser."""
import copy
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch
import uuid
import zipfile
import xml.etree.ElementTree as ET


def module(filename):
    path = Path(__file__).with_name(filename)
    if not path.exists():
        return None
    spec = importlib.util.spec_from_file_location(filename.replace('-', '_'), path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class DocxVerifierContractTest(unittest.TestCase):
    def setUp(self):
        self.qa = module('verify-docx-import.py')
        self.generator = module('generate-docx-fixtures.py')
        self.request = {'mutationId': str(uuid.uuid4()), 'title': '导入验证',
                        'document': {'schemaVersion': 4, 'content': {'name': '奶龙'}, 'layout': {}}}
        self.resume = {'id': str(uuid.uuid4()), 'title': self.request['title'],
                       'document': copy.deepcopy(self.request['document']), 'revision': 1,
                       'lastMutationId': None, 'updatedAt': '2026-10-07T00:00:00Z'}

    def test_real_word_fixture_is_deterministic_and_contains_text_tables_headers(self):
        self.assertIsNotNone(self.generator, 'DOCX fixture generator is missing')
        for table in (False, True):
            content = self.generator.fixture_bytes(table=table)
            self.assertEqual(content, self.generator.fixture_bytes(table=table))
            with zipfile.ZipFile(io.BytesIO(content)) as archive:
                self.assertIsNone(archive.testzip())
                root = ET.fromstring(archive.read('word/document.xml'))
                text = ''.join(root.itertext())
                self.assertIn('奶龙', text)
                self.assertIn('教育背景', text)
                self.assertIn('nailong@example.invalid', text)
                self.assertIn('application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml',
                              archive.read('[Content_Types].xml').decode())
                if table:
                    self.assertIn('Word 表格', text)
                    self.assertIn('word/header1.xml', archive.namelist())

    def test_receipt_must_match_exact_initial_request(self):
        self.assertIsNotNone(self.qa, 'DOCX import verifier is missing')
        receipt = {'mutationId': self.request['mutationId'], 'resume': self.resume}
        self.qa.assert_receipt(receipt, self.request)
        for changed in ({'mutationId': str(uuid.uuid4())}, {'resume': {**self.resume, 'title': 'wrong'}},
                        {'resume': {**self.resume, 'document': {'content': {'name': 'wrong'}}}},
                        {'resume': {**self.resume, 'id': 'invalid'}}):
            with self.subTest(changed=changed), self.assertRaises(AssertionError):
                self.qa.assert_receipt({**receipt, **changed}, self.request)

    def test_retry_acknowledges_same_edited_resume_without_overwriting_it(self):
        self.assertIsNotNone(self.qa, 'DOCX import verifier is missing')
        edited = {**self.resume, 'revision': 2, 'document': {'content': {'name': '编辑后的奶龙'}}}
        receipt = {'mutationId': self.request['mutationId'], 'resume': edited}
        self.qa.assert_receipt(receipt, self.request, expected=edited)
        with self.assertRaises(AssertionError):
            self.qa.assert_receipt({**receipt, 'resume': self.resume}, self.request, expected=edited)
        with self.assertRaises(AssertionError):
            self.qa.assert_receipt({**receipt, 'resume': {**edited, 'id': str(uuid.uuid4())}},
                                   self.request, expected=edited)

    def test_restore_keeps_document_revision_and_title_but_maps_identity(self):
        self.assertIsNotNone(self.qa, 'DOCX import verifier is missing')
        restored = {**self.resume, 'id': str(uuid.uuid4()), 'lastMutationId': None}
        self.qa.assert_restored(self.resume, restored)
        for field in ('title', 'document', 'revision'):
            with self.subTest(field=field), self.assertRaises(AssertionError):
                self.qa.assert_restored(self.resume, {**restored, field: 'changed'})
        with self.assertRaises(AssertionError):
            self.qa.assert_restored(self.resume, self.resume)

    def test_nonisolated_endpoints_are_rejected_before_network(self):
        self.assertIsNotNone(self.qa, 'DOCX import verifier is missing')
        for operation in ('run', 'persistence'):
            with patch.object(self.qa, 'call', side_effect=AssertionError('NETWORK MUST NOT RUN')):
                for source, target in [('http://127.0.0.1:18765', 'http://127.0.0.1:18769'),
                                       ('https://example.invalid', 'http://127.0.0.1:18769'),
                                       ('http://127.0.0.1:18767', 'http://localhost:18767')]:
                    with self.subTest(operation=operation, source=source), self.assertRaises(AssertionError) as error:
                        getattr(self.qa, operation)(source, target)
                    self.assertNotIn('NETWORK MUST NOT RUN', str(error.exception))

    def test_upgrade_probe_rejects_real_or_remote_workspace_before_network(self):
        self.assertIsNotNone(self.qa, 'DOCX import verifier is missing')
        with patch.object(self.qa, 'call', side_effect=AssertionError('NETWORK MUST NOT RUN')):
            for base in ('http://127.0.0.1:18765', 'https://example.invalid', 'http://localhost:18769/path'):
                with self.subTest(base=base), self.assertRaises(AssertionError) as error:
                    self.qa.after_upgrade(base)
                self.assertNotIn('NETWORK MUST NOT RUN', str(error.exception))


if __name__ == '__main__':
    unittest.main()
