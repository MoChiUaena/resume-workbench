"""Read-only, fail-closed protection against replacing a published version tag."""
import argparse
import json
from pathlib import Path
import re
import urllib.request
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'mochiuaena/resume-workbench'
IMAGE = 'ghcr.io/' + REPOSITORY
BASELINE_VERSION = '0.7.0'
LIMIT = 131072
MANIFEST_TYPES = ('application/vnd.oci.image.index.v1+json', 'application/vnd.oci.image.manifest.v1+json',
                  'application/vnd.docker.distribution.manifest.list.v2+json',
                  'application/vnd.docker.distribution.manifest.v2+json')


class PublicationBlocked(RuntimeError):
    """A safe diagnostic that never includes token or response contents."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, newurl):
        return None


def _get(url, headers, label, opener):
    request = urllib.request.Request(url, headers=headers, method='GET')
    try:
        try:
            response = opener(request, timeout=20)
        except HTTPError as error:
            response = error
        with response:
            status = response.getcode()
            body = response.read(LIMIT + 1)
        if len(body) > LIMIT:
            raise PublicationBlocked(label + ' response exceeded the size limit')
        return status, body
    except (URLError, OSError, TimeoutError, ValueError):
        raise PublicationBlocked(label + ' request failed; publication is blocked') from None


def _json(body, label):
    try:
        value = json.loads(body)
    except (ValueError, UnicodeError):
        raise PublicationBlocked(label + ' response was not valid JSON') from None
    if not isinstance(value, dict):
        raise PublicationBlocked(label + ' response was not a JSON object')
    return value


def _manifest_readable(value):
    if value.get('schemaVersion') != 2 or value.get('mediaType') not in MANIFEST_TYPES:
        return False
    if value['mediaType'] in (MANIFEST_TYPES[0], MANIFEST_TYPES[2]):
        entries = value.get('manifests')
    else:
        config = value.get('config')
        if not isinstance(config, dict) or not re.fullmatch(r'sha256:[0-9a-f]{64}', str(config.get('digest', ''))):
            return False
        entries = value.get('layers')
    return isinstance(entries, list) and bool(entries) and all(
        isinstance(entry, dict) and re.fullmatch(r'sha256:[0-9a-f]{64}', str(entry.get('digest', '')))
        for entry in entries)


def confirm_missing(target, source_version, *, opener=None):
    if not re.fullmatch(r'(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)', source_version or '') or target != IMAGE + ':' + source_version:
        raise PublicationBlocked('Target must be the fixed public repository and exact stable source version')
    opener = opener or urllib.request.build_opener(NoRedirect()).open
    token_url = 'https://ghcr.io/token?' + urlencode({'service': 'ghcr.io', 'scope': 'repository:' + REPOSITORY + ':pull'})
    status, body = _get(token_url, {'Accept': 'application/json'}, 'Anonymous token', opener)
    if status != 200:
        raise PublicationBlocked('Anonymous token HTTP status ' + str(status) + '; publication is blocked')
    token = _json(body, 'Anonymous token').get('token')
    if not isinstance(token, str) or not re.fullmatch(r'[!-~]{1,8192}', token):
        raise PublicationBlocked('Anonymous token was missing or invalid')
    headers = {'Authorization': 'Bearer ' + token, 'Accept': ', '.join(MANIFEST_TYPES)}
    prefix = 'https://ghcr.io/v2/' + REPOSITORY + '/manifests/'
    status, body = _get(prefix + BASELINE_VERSION, headers, 'Known public baseline manifest', opener)
    if status != 200 or not _manifest_readable(_json(body, 'Known public baseline manifest')):
        raise PublicationBlocked('Known public baseline manifest could not be confirmed readable')
    status, body = _get(prefix + source_version, headers, 'Target manifest', opener)
    if status == 200:
        raise PublicationBlocked('Target version image already exists; publication must not replace it')
    if status != 404:
        raise PublicationBlocked('Target manifest HTTP status ' + str(status) + '; absence is unconfirmed')
    errors = _json(body, 'Target manifest').get('errors')
    if not isinstance(errors, list) or len(errors) != 1 or not isinstance(errors[0], dict) or errors[0].get('code') != 'MANIFEST_UNKNOWN':
        raise PublicationBlocked('Target manifest did not return an unambiguous MANIFEST_UNKNOWN 404')
    return {'target': target, 'baseline': IMAGE + ':' + BASELINE_VERSION,
            'baselineManifestReadable': True, 'confirmedMissing': True}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--output', type=Path, default=ROOT / 'output/registry-publication-gate.json')
    args = parser.parse_args()
    try:
        source_version = ET.parse(args.root / 'pom.xml').getroot().findtext('{http://maven.apache.org/POM/4.0.0}version')
        evidence = confirm_missing(args.image, source_version)
    except (PublicationBlocked, OSError, ET.ParseError) as error:
        parser.exit(1, str(error) + '\n')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(evidence, indent=2))
