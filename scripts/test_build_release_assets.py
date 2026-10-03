"""Exercise release packaging with public synthetic inputs and isolated outputs."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).with_name('build-release-assets.py')
IMAGE = 'ghcr.io/mochiuaena/resume-workbench:0.8.0'
INVENTORY = 'src/main/resources/META-INF/third-party/inventory.json'
ASSETS = {'resume-workbench-config.zip', 'dependency-notices.zip',
          'resume-workbench-demo.gif', 'SHA256SUMS.txt'}


class ReleaseAssetsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='resume-release-assets-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / 'public-source'
        self.output = Path(self.temporary.name) / 'assets'
        self.fixture()

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content, encoding='utf-8')

    def fixture(self, version='0.8.0'):
        image = 'ghcr.io/mochiuaena/resume-workbench:' + version
        self.write('pom.xml', '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                   '<parent><version>3.5.16</version></parent>'
                   '<version>' + version + '</version></project>')
        self.write('frontend/package.json', json.dumps({'version': version}))
        self.write('frontend/package-lock.json', json.dumps({
            'version': version, 'lockfileVersion': 3,
            'packages': {'': {'version': version}, 'node_modules/example': {'version': '9.1.0'}}}))
        self.write(INVENTORY, json.dumps({'inventoryVersion': 1, 'applicationVersion': version,
                                         'java': [], 'npm': []}))
        self.write('compose.yml', 'name: resume-workbench\nservices:\n  app:\n'
                   '    image: ${RESUME_APP_IMAGE:-' + image + '}\n'
                   '    volumes: [resume_data:/app/data]\n  db:\n    image: postgres:16-alpine\n')
        self.write('.env.example', '# Public configuration only\n'
                   'RESUME_DB_PASSWORD=replace-with-a-long-random-password\nRESUME_APP_IMAGE=' + image + '\n')
        self.write('LICENSE', 'Synthetic public project license\n')
        self.write('THIRD_PARTY_NOTICES.md', 'Synthetic dependency notice\n')
        self.write('docs/dependency-licenses.md', 'Synthetic dependency list\n')
        self.write('src/main/resources/META-INF/third-party/texts/example/LICENSE.txt', 'Example license\n')
        self.write('src/main/resources/static/fonts/OFL.txt', 'Sans license\n')
        self.write('src/main/resources/static/fonts/OFL-Serif.txt', 'Serif license\n')
        self.write('docs/demo.gif', b'GIF89a synthetic public demo')
        self.write('.env', 'PRIVATE_ENV_CANARY=do-not-package\n')
        self.write('data/private-backup.zip', b'PRIVATE_DATA_CANARY')
        self.write('output/private-report.zip', b'PRIVATE_REPORT_CANARY')
        (self.root / 'scripts').mkdir(exist_ok=True)
        shutil.copyfile(SCRIPT, self.root / 'scripts/build-release-assets.py')

    def run_builder(self, *arguments):
        return subprocess.run([sys.executable, str(self.root / 'scripts/build-release-assets.py'),
                               *arguments], cwd=self.root, capture_output=True, text=True, check=False)

    def build(self):
        result = self.run_builder('--output-dir', str(self.output))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(self.output.is_dir(), 'Requested output directory must be used')
        self.assertEqual({p.name for p in self.output.iterdir()} - {'resume-workbench-demo.webm'}, ASSETS)
        return result

    def test_import_does_not_create_release_directory(self):
        result = subprocess.run([sys.executable, '-c',
            "import importlib.util; s=importlib.util.spec_from_file_location('release', "
            "'scripts/build-release-assets.py'); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)"],
            cwd=self.root, capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.root / 'output/release').exists(), 'Import must not package assets')
        self.assertEqual(result.stdout, '')

    def test_config_and_notices_contain_only_public_inputs(self):
        self.build()
        self.assertEqual({p.name for p in self.output.iterdir()}, ASSETS)
        with zipfile.ZipFile(self.output / 'resume-workbench-config.zip') as config:
            self.assertEqual(set(config.namelist()), {'compose.yml', '.env.example'})
            self.assertEqual(config.read('compose.yml'), (self.root / 'compose.yml').read_bytes())
            self.assertIn(IMAGE.encode(), config.read('.env.example'))
        with zipfile.ZipFile(self.output / 'dependency-notices.zip') as notices:
            self.assertEqual(set(notices.namelist()), {
                'LICENSE', 'THIRD_PARTY_NOTICES.md', 'docs/dependency-licenses.md',
                'third-party/inventory.json', 'third-party/texts/example/LICENSE.txt',
                'fonts/OFL.txt', 'fonts/OFL-Serif.txt'})
            self.assertEqual(json.loads(notices.read('third-party/inventory.json'))['applicationVersion'], '0.8.0')
        for path in self.output.iterdir():
            self.assertNotIn(b'PRIVATE_ENV_CANARY', path.read_bytes())
            self.assertNotIn(b'PRIVATE_DATA_CANARY', path.read_bytes())
            self.assertNotIn(b'PRIVATE_REPORT_CANARY', path.read_bytes())
        self.assertEqual((self.root / '.env').read_text(), 'PRIVATE_ENV_CANARY=do-not-package\n')
        self.assertEqual((self.root / 'data/private-backup.zip').read_bytes(), b'PRIVATE_DATA_CANARY')

    def test_checksum_lists_each_actual_artifact_once_with_independent_hash(self):
        self.build()
        lines = (self.output / 'SHA256SUMS.txt').read_text(encoding='utf-8').splitlines()
        entries = [line.split('  ', 1) for line in lines]
        self.assertEqual([name for _, name in entries], [
            'dependency-notices.zip', 'resume-workbench-config.zip', 'resume-workbench-demo.gif'])
        for digest, name in entries:
            self.assertEqual(digest, hashlib.sha256((self.output / name).read_bytes()).hexdigest(), name)

    def test_optional_public_video_is_copied_and_hashed(self):
        self.write('output/demo/resume-workbench-demo.webm', b'synthetic public video')
        self.write('output/demo/private-notes.txt', 'PRIVATE_VIDEO_DIRECTORY_CANARY')
        self.build()
        self.assertEqual({p.name for p in self.output.iterdir()}, ASSETS | {'resume-workbench-demo.webm'})
        self.assertEqual((self.output / 'resume-workbench-demo.webm').read_bytes(), b'synthetic public video')
        expected = hashlib.sha256(b'synthetic public video').hexdigest() + '  resume-workbench-demo.webm'
        self.assertIn(expected, (self.output / 'SHA256SUMS.txt').read_text().splitlines())

    def test_version_is_derived_from_project_pom_not_parent_or_hardcoded_release(self):
        self.fixture('1.2.3')
        self.build()
        with zipfile.ZipFile(self.output / 'dependency-notices.zip') as notices:
            self.assertEqual(json.loads(notices.read('third-party/inventory.json'))['applicationVersion'], '1.2.3')
        with zipfile.ZipFile(self.output / 'resume-workbench-config.zip') as config:
            self.assertIn(b'ghcr.io/mochiuaena/resume-workbench:1.2.3', config.read('compose.yml'))

    def test_each_version_disagreement_is_rejected_before_writing(self):
        conflicts = {
            'frontend/package.json': {'version': '0.7.0'},
            'frontend/package-lock.json': {'version': '0.7.0', 'packages': {'': {'version': '0.8.0'}}},
            'frontend/package-lock.json root package': {'version': '0.8.0', 'packages': {'': {'version': '0.7.0'}}},
            INVENTORY: {'applicationVersion': '0.7.0'},
            'compose.yml': 'services:\n  app:\n    image: ${RESUME_APP_IMAGE:-ghcr.io/mochiuaena/resume-workbench:0.7.0}\n',
            '.env.example': 'RESUME_APP_IMAGE=ghcr.io/mochiuaena/resume-workbench:0.7.0\n',
        }
        for label, content in conflicts.items():
            with self.subTest(conflict=label):
                self.fixture()
                path = label.removesuffix(' root package')
                self.write(path, json.dumps(content) if isinstance(content, dict) else content)
                result = self.run_builder('--output-dir', str(self.output))
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn(path, result.stderr)
                self.assertFalse(self.output.exists(), result.stderr)

    def test_unstable_pom_versions_cannot_be_published(self):
        for version in ['0.8.0-SNAPSHOT', '0.8.0-dev', 'latest']:
            with self.subTest(version=version):
                self.fixture(version)
                result = self.run_builder('--output-dir', str(self.output))
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn('stable', result.stderr.lower())
                self.assertFalse(self.output.exists())

    def test_matching_expected_release_version_is_supported(self):
        result = self.run_builder('--expected-version', '0.8.0', '--output-dir', str(self.output))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual({p.name for p in self.output.iterdir()}, ASSETS)
        with zipfile.ZipFile(self.output / 'dependency-notices.zip') as notices:
            self.assertEqual(json.loads(notices.read('third-party/inventory.json'))['applicationVersion'], '0.8.0')

    def test_wrong_expected_release_version_is_rejected_before_any_output(self):
        for expected in ['0.7.0', 'v0.8.0']:
            with self.subTest(expected=expected):
                result = self.run_builder('--expected-version', expected, '--output-dir', str(self.output))
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn('pom.xml', result.stderr)
                self.assertIn(expected, result.stderr)
                self.assertIn('0.8.0', result.stderr)
                self.assertFalse(self.output.exists())
                self.assertFalse((self.root / 'output/release').exists())

    def test_empty_duplicate_image_assignment_is_rejected(self):
        self.write('.env.example', 'RESUME_APP_IMAGE=' + IMAGE + '\nRESUME_APP_IMAGE=\n')
        result = self.run_builder('--output-dir', str(self.output))
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('.env.example', result.stderr)
        self.assertFalse(self.output.exists())

    def test_dirty_output_is_rejected_and_all_existing_contents_are_preserved(self):
        self.output.mkdir()
        self.output.joinpath('old-private-backup.zip').write_bytes(b'never overwrite or hash this')
        self.output.joinpath('resume-workbench-config.zip').write_bytes(b'old public config')
        self.output.joinpath('subdirectory').mkdir()
        self.output.joinpath('subdirectory/keep.txt').write_text('keep nested file')
        result = self.run_builder('--output-dir', str(self.output))
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('empty', result.stderr.lower())
        self.assertEqual({p.name for p in self.output.iterdir()}, {
            'old-private-backup.zip', 'resume-workbench-config.zip', 'subdirectory'})
        self.assertEqual(self.output.joinpath('old-private-backup.zip').read_bytes(), b'never overwrite or hash this')
        self.assertEqual(self.output.joinpath('resume-workbench-config.zip').read_bytes(), b'old public config')
        self.assertEqual(self.output.joinpath('subdirectory/keep.txt').read_text(), 'keep nested file')

    def test_previous_allowlisted_assets_require_fresh_output(self):
        self.output.mkdir()
        for name in ASSETS:
            (self.output / name).write_bytes(b'previous release must remain')
        result = self.run_builder('--output-dir', str(self.output))
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('empty', result.stderr.lower())
        for name in ASSETS:
            self.assertEqual((self.output / name).read_bytes(), b'previous release must remain')

    def test_empty_existing_directory_and_default_directory_are_supported(self):
        self.output.mkdir()
        self.build()
        result = self.run_builder()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual({p.name for p in (self.root / 'output/release').iterdir()}, ASSETS)

    def test_missing_public_input_is_rejected_without_partial_output(self):
        (self.root / 'src/main/resources/static/fonts/OFL-Serif.txt').unlink()
        result = self.run_builder('--output-dir', str(self.output))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('OFL-Serif.txt', result.stderr)
        self.assertFalse(self.output.exists())
        self.assertFalse((self.root / 'output/release').exists())


if __name__ == '__main__':
    unittest.main()
