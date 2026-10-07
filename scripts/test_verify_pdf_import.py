"""Service-free contracts for the disposable text-PDF import verifier."""
import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import uuid

SPEC = Path(__file__).with_name('verify-pdf-import.py')


def qa_module():
    if not SPEC.is_file():
        return None
    module_spec = importlib.util.spec_from_file_location('verify_pdf_import', SPEC)
    module = importlib.util.module_from_spec(module_spec)
    module_spec.loader.exec_module(module)
    return module


class PdfImportVerifierContractTest(unittest.TestCase):
    def setUp(self):
        self.qa = qa_module()
        self.request = {'mutationId': str(uuid.uuid4()), 'title': 'PDF 导入合成验证',
                        'document': {'schemaVersion': 4, 'content': {'name': '奶龙'}, 'layout': {}}}
        self.resume = {'id': str(uuid.uuid4()), 'title': self.request['title'],
                       'document': copy.deepcopy(self.request['document']), 'revision': 1,
                       'lastMutationId': None, 'updatedAt': '2026-10-07T00:00:00Z'}

    def test_exact_create_receipt_rejects_substituted_content_or_identity(self):
        self.assertIsNotNone(self.qa, 'PDF import verifier is missing')
        receipt = {'mutationId': self.request['mutationId'], 'resume': self.resume}
        self.qa.assert_receipt(receipt, self.request)
        for bad in ({'mutationId': str(uuid.uuid4())}, {'resume': {**self.resume, 'title': 'wrong'}},
                    {'resume': {**self.resume, 'document': {'content': {'name': 'wrong'}}}},
                    {'resume': {**self.resume, 'id': 'invalid'}}):
            with self.subTest(bad=bad), self.assertRaises(AssertionError):
                self.qa.assert_receipt({**receipt, **bad}, self.request)

    def test_same_request_may_return_the_edited_current_resume(self):
        self.assertIsNotNone(self.qa, 'PDF import verifier is missing')
        edited = {**self.resume, 'revision': 2, 'title': '稍后编辑',
                  'document': {'content': {'name': '奶龙修订'}}}
        receipt = {'mutationId': self.request['mutationId'], 'resume': edited}
        self.qa.assert_receipt(receipt, self.request, expected=edited)
        with self.assertRaises(AssertionError):
            self.qa.assert_receipt({**receipt, 'resume': self.resume}, self.request, expected=edited)

    def test_restored_resume_keeps_reviewed_document_but_remaps_id(self):
        self.assertIsNotNone(self.qa, 'PDF import verifier is missing')
        restored = {**self.resume, 'id': str(uuid.uuid4())}
        self.qa.assert_restored(self.resume, restored)
        with self.assertRaises(AssertionError):
            self.qa.assert_restored(self.resume, self.resume)
        with self.assertRaises(AssertionError):
            self.qa.assert_restored(self.resume, {**restored, 'revision': 2})
        with self.assertRaises(AssertionError):
            self.qa.assert_restored(self.resume, {**restored, 'document': {}})

    def test_only_real_text_preview_with_bounded_pages_is_accepted(self):
        self.assertIsNotNone(self.qa, 'PDF import verifier is missing')
        preview = {'format': 'pdf', 'sourceText': '姓名：奶龙\n教育背景\n示例理工大学',
                   'document': {'content': {'name': '奶龙', 'sections': [{'type': 'education'}]}},
                   'statistics': {'paragraphs': 3, 'pages': 2, 'images': 0}}
        self.qa.assert_preview(preview, expected_pages=2)
        for bad in ({'format': 'docx'}, {'sourceText': ''}, {'statistics': {'paragraphs': 3, 'pages': 21, 'images': 0}},
                    {'document': {'content': {'name': '姓名', 'sections': []}}}):
            with self.subTest(bad=bad), self.assertRaises(AssertionError):
                self.qa.assert_preview({**preview, **bad}, expected_pages=2)

    def test_real_or_foreign_workspaces_are_rejected_before_request(self):
        self.assertIsNotNone(self.qa, 'PDF import verifier is missing')
        with patch.object(self.qa, 'call', side_effect=AssertionError('NETWORK MUST NOT RUN')):
            for operation in ('run', 'persistence'):
                for source, target in (('http://127.0.0.1:18765', 'http://127.0.0.1:18769'),
                                       ('https://example.invalid', 'http://127.0.0.1:18769'),
                                       ('http://127.0.0.1:18767', 'http://localhost:18767')):
                    with self.subTest(operation=operation, source=source), self.assertRaises(AssertionError) as error:
                        getattr(self.qa, operation)(source, target)
                    self.assertNotIn('NETWORK MUST NOT RUN', str(error.exception))

    def test_upgrade_probe_rejects_real_workspace_before_request(self):
        self.assertIsNotNone(self.qa, 'PDF import verifier is missing')
        with patch.object(self.qa, 'call', side_effect=AssertionError('NETWORK MUST NOT RUN')):
            for base in ('http://127.0.0.1:18765', 'https://example.invalid',
                         'http://localhost:18769/path'):
                with self.subTest(base=base), self.assertRaises(AssertionError) as error:
                    self.qa.after_upgrade(base)
                self.assertNotIn('NETWORK MUST NOT RUN', str(error.exception))


if __name__ == '__main__':
    unittest.main()
