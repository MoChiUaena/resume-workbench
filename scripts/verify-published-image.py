"""Validate release defaults and record the actual anonymously pulled Linux image."""
import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'ghcr.io/mochiuaena/resume-workbench'


def verify(root, image, inspection):
    version = ET.parse(root / 'pom.xml').getroot().findtext('{http://maven.apache.org/POM/4.0.0}version')
    assert version and re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version), 'Expected stable application version'
    expected = REPOSITORY + ':' + version
    assert image == expected, 'Selected published image must match the checked-out application version'
    compose = (root / 'compose.yml').read_text(encoding='utf-8')
    defaults = re.findall(r'\$\{RESUME_APP_IMAGE:-([^}]+)\}', compose)
    assert defaults == [expected], 'compose.yml default image disagrees with the application version'
    env_defaults = re.findall(r'^RESUME_APP_IMAGE=(.+)$', (root / '.env.example').read_text(encoding='utf-8'), re.M)
    assert env_defaults == [expected], '.env.example default image disagrees with the application version'
    assert len(inspection) == 1, 'Expected one pulled image inspection'
    metadata = inspection[0]
    assert metadata['Os'] == 'linux' and metadata['Architecture'] == 'amd64', 'Release requires linux/amd64'
    assert image in metadata['RepoTags'], 'Image inspection does not contain the selected release tag'
    assert re.fullmatch(r'sha256:[0-9a-f]{64}', metadata['Id']), 'Invalid local image ID'
    digests = [digest for digest in metadata.get('RepoDigests', [])
               if re.fullmatch(re.escape(REPOSITORY) + r'@sha256:[0-9a-f]{64}', digest)]
    assert len(set(digests)) == 1, 'Expected exactly one matching repository digest from the anonymous pull'
    return {'image': image, 'applicationVersion': version, 'imageId': metadata['Id'],
            'repoDigest': digests[0], 'platform': 'linux/amd64', 'publicDefaultsMatchVersion': True}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--image', required=True)
    parser.add_argument('--inspection', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'output/published-image-verification.json')
    args = parser.parse_args()
    evidence = verify(args.root, args.image, json.loads(args.inspection.read_text(encoding='utf-8')))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(evidence, indent=2))
