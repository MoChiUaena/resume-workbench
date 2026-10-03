"""Verify published image evidence from real files, without Docker or networking."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name('verify-published-image.py')
IMAGE = 'ghcr.io/mochiuaena/resume-workbench:0.8.0'
DIGEST = 'ghcr.io/mochiuaena/resume-workbench@sha256:' + 'a' * 64


class PublishedImageVerifierTest(unittest.TestCase):
    def execute(self, env_image=IMAGE, repo_digest=DIGEST, architecture='amd64'):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><version>0.8.0</version></project>', encoding='utf-8')
            (root / 'compose.yml').write_text('services:\n  app:\n    image: ${RESUME_APP_IMAGE:-' + IMAGE + '}\n', encoding='utf-8')
            (root / '.env.example').write_text('RESUME_APP_IMAGE=' + env_image + '\n', encoding='utf-8')
            metadata = root / 'inspection.json'
            metadata.write_text(json.dumps([{'Id': 'sha256:' + 'b' * 64, 'RepoTags': [IMAGE],
                'RepoDigests': [repo_digest], 'Architecture': architecture, 'Os': 'linux'}]), encoding='utf-8')
            output = root / 'evidence/published-image-verification.json'
            result = subprocess.run([sys.executable, '-X', 'utf8', str(SCRIPT), '--root', str(root), '--image', IMAGE,
                                     '--inspection', str(metadata), '--output', str(output)],
                                    capture_output=True, text=True, encoding='utf-8')
            evidence = json.loads(output.read_text(encoding='utf-8')) if output.exists() else None
            return result, evidence

    def test_actual_digest_and_architecture_are_recorded(self):
        result, evidence = self.execute()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(evidence, {'image': IMAGE, 'applicationVersion': '0.8.0',
            'imageId': 'sha256:' + 'b' * 64, 'repoDigest': DIGEST, 'platform': 'linux/amd64',
            'publicDefaultsMatchVersion': True})

    def test_stale_public_config_default_is_rejected(self):
        result, evidence = self.execute(env_image='ghcr.io/mochiuaena/resume-workbench:0.7.0')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('.env.example', result.stderr)
        self.assertIsNone(evidence)

    def test_digest_for_another_repository_cannot_be_claimed(self):
        result, evidence = self.execute(repo_digest='ghcr.io/other/image@sha256:' + 'a' * 64)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('repository digest', result.stderr)
        self.assertIsNone(evidence)

    def test_wrong_platform_cannot_be_claimed_as_release_image(self):
        result, evidence = self.execute(architecture='arm64')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('linux/amd64', result.stderr)
        self.assertIsNone(evidence)


if __name__ == '__main__':
    unittest.main()
