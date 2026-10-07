"""Synthetic PDF fixtures must stay reproducible and exercise real text extraction."""
import importlib.util
import io
from pathlib import Path
import unittest

from pypdf import PdfReader


ROOT = Path(__file__).resolve().parents[1]
GENERATOR = Path(__file__).with_name('generate-pdf-fixtures.py')


class PdfFixtureContractTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(GENERATOR.is_file(), 'PDF fixture generator is missing')
        spec = importlib.util.spec_from_file_location('generate_pdf_fixtures', GENERATOR)
        self.generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.generator)

    def test_text_pdf_is_reproducible_and_contains_selectable_chinese(self):
        first = self.generator.fixture_bytes('text')
        self.assertEqual(first, self.generator.fixture_bytes('text'))
        self.assertLess(len(first), 5 * 1024 * 1024)
        reader = PdfReader(io.BytesIO(first))
        self.assertEqual(len(reader.pages), 1)
        text = reader.pages[0].extract_text()
        for value in ('奶龙', '教育背景', '示例理工大学', '项目经历', 'Java'):
            self.assertIn(value, text)

    def test_two_pages_keep_separate_visible_content(self):
        reader = PdfReader(io.BytesIO(self.generator.fixture_bytes('two-pages')))
        self.assertEqual(len(reader.pages), 2)
        self.assertIn('教育背景', reader.pages[0].extract_text())
        self.assertIn('项目经历', reader.pages[1].extract_text())

    def test_image_only_pdf_is_not_selectable_text(self):
        reader = PdfReader(io.BytesIO(self.generator.fixture_bytes('scanned')))
        self.assertEqual(len(reader.pages), 1)
        self.assertFalse(reader.pages[0].extract_text().strip())
        self.assertTrue('/XObject' in reader.pages[0]['/Resources'])

    def test_encrypted_pdf_requires_password_and_is_reproducible(self):
        first = self.generator.fixture_bytes('encrypted')
        self.assertEqual(first, self.generator.fixture_bytes('encrypted'))
        reader = PdfReader(io.BytesIO(first))
        self.assertTrue(reader.is_encrypted)
        self.assertEqual(reader.decrypt('test-only'), 1)
        self.assertIn('奶龙', reader.pages[0].extract_text())

    def test_checked_in_files_match_generated_selectable_content(self):
        for kind in ('text', 'two-pages', 'scanned', 'encrypted'):
            with self.subTest(kind=kind):
                source = ROOT / 'fixtures/pdf' / f'{kind}.pdf'
                self.assertTrue(source.is_file(), f'Missing checked-in {kind} PDF')
                self.assertEqual(self.generator.semantic_content(source.read_bytes()),
                                 self.generator.semantic_content(self.generator.fixture_bytes(kind)))


if __name__ == '__main__':
    unittest.main()
