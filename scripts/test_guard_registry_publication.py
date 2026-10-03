"""Public registry absence must be proven through controlled HTTP responses."""
import importlib.util
import io
import json
from pathlib import Path
import unittest
from urllib.error import HTTPError, URLError


SCRIPT = Path(__file__).with_name('guard-registry-publication.py')
TARGET = 'ghcr.io/mochiuaena/resume-workbench:0.8.0'
BASELINE_URL = 'https://ghcr.io/v2/mochiuaena/resume-workbench/manifests/0.7.0'
TARGET_URL = 'https://ghcr.io/v2/mochiuaena/resume-workbench/manifests/0.8.0'
TOKEN_URL = 'https://ghcr.io/token?service=ghcr.io&scope=repository%3Amochiuaena%2Fresume-workbench%3Apull'
TOKEN = 'synthetic-token-do-not-print'
MANIFEST = {'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.index.v1+json',
            'manifests': [{'mediaType': 'application/vnd.oci.image.manifest.v1+json',
                           'digest': 'sha256:' + 'a' * 64, 'size': 100}]}
MISSING = {'errors': [{'code': 'MANIFEST_UNKNOWN', 'message': 'manifest unknown'}]}


class Response(io.BytesIO):
    status = 200

    def getcode(self):
        return self.status


class RegistryTransport:
    def __init__(self, target_status=404, target_body=None, baseline_status=200, baseline_body=None,
                 token_status=200, token_body=None, network_error_at=None):
        self.steps = [
            (TOKEN_URL, token_status, token_body if token_body is not None else {'token': TOKEN}),
            (BASELINE_URL, baseline_status, baseline_body if baseline_body is not None else MANIFEST),
            (TARGET_URL, target_status, target_body if target_body is not None else MISSING),
        ]
        self.urls = []
        self.network_error_at = network_error_at

    def __call__(self, request, timeout):
        index = len(self.urls)
        expected_url, status, body = self.steps[index]
        assert request.full_url == expected_url, 'Guard requested an unexpected registry/manifest'
        assert request.get_method() == 'GET' and 0 < timeout <= 30
        if index:
            assert request.get_header('Authorization') == 'Bearer ' + TOKEN
            assert 'application/vnd.oci.image.index.v1+json' in request.get_header('Accept')
            assert 'application/vnd.docker.distribution.manifest.v2+json' in request.get_header('Accept')
        else:
            assert request.get_header('Authorization') is None
        self.urls.append(request.full_url)
        if request.full_url == self.network_error_at:
            raise URLError('network failed: ' + TOKEN)
        payload = body if isinstance(body, bytes) else json.dumps(body).encode()
        if status != 200:
            raise HTTPError(request.full_url, status, 'controlled registry response', {}, io.BytesIO(payload))
        return Response(payload)


class RegistryPublicationGuardTest(unittest.TestCase):
    def guard(self):
        self.assertTrue(SCRIPT.is_file(), 'Missing fail-closed public registry publication guard')
        spec = importlib.util.spec_from_file_location('registry_guard', SCRIPT)
        guard = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(guard)
        return guard

    def denied(self, **responses):
        guard = self.guard()
        with self.assertRaises(guard.PublicationBlocked) as raised:
            guard.confirm_missing(TARGET, '0.8.0', opener=RegistryTransport(**responses))
        self.assertNotIn(TOKEN, str(raised.exception))

    def test_only_readable_known_manifest_and_explicit_missing_tag_permit_publication(self):
        guard = self.guard()
        transport = RegistryTransport()
        evidence = guard.confirm_missing(TARGET, '0.8.0', opener=transport)
        self.assertEqual(evidence, {'target': TARGET, 'baseline': 'ghcr.io/mochiuaena/resume-workbench:0.7.0',
            'baselineManifestReadable': True, 'confirmedMissing': True})
        self.assertEqual(transport.urls, [TOKEN_URL, BASELINE_URL, TARGET_URL])
        self.assertNotIn(TOKEN, json.dumps(evidence))

    def test_existing_tag_is_blocked_even_if_its_success_body_is_empty(self):
        for body in [MANIFEST, b'']:
            with self.subTest(body=bool(body)):
                self.denied(target_status=200, target_body=body)

    def test_supported_oci_and_docker_manifest_formats_establish_the_public_baseline(self):
        guard = self.guard()
        for media_type in ['application/vnd.oci.image.manifest.v1+json',
                           'application/vnd.docker.distribution.manifest.v2+json']:
            with self.subTest(media_type=media_type):
                manifest = {'schemaVersion': 2, 'mediaType': media_type,
                    'config': {'mediaType': 'application/vnd.oci.image.config.v1+json',
                               'digest': 'sha256:' + 'b' * 64, 'size': 42},
                    'layers': [{'mediaType': 'application/vnd.oci.image.layer.v1.tar+gzip',
                                'digest': 'sha256:' + 'c' * 64, 'size': 100}]}
                evidence = guard.confirm_missing(TARGET, '0.8.0', opener=RegistryTransport(baseline_body=manifest))
                self.assertTrue(evidence['baselineManifestReadable'] and evidence['confirmedMissing'])

    def test_anonymous_token_authentication_or_service_failure_is_blocked(self):
        for status in [401, 403, 429, 503]:
            with self.subTest(status=status):
                self.denied(token_status=status)

    def test_authentication_rate_limit_and_registry_failure_cannot_mean_absence(self):
        for status in [301, 302, 401, 403, 429, 500, 503]:
            with self.subTest(status=status):
                self.denied(target_status=status)

    def test_ambiguous_or_unrelated_404_errors_are_blocked(self):
        for body in [b'', b'not-json', {}, {'errors': []},
                     {'errors': [{'code': 'NAME_UNKNOWN'}]},
                     {'errors': [{'code': 'MANIFEST_UNKNOWN'}, {'code': 'NAME_UNKNOWN'}]}]:
            with self.subTest(body=body):
                self.denied(target_body=body)

    def test_baseline_must_be_public_and_a_valid_manifest_before_target_is_probed(self):
        for status, body in [(401, MISSING), (403, MISSING), (404, MISSING), (500, MISSING),
                             (200, b''), (200, b'not-json'), (200, {}),
                             (200, {'schemaVersion': 2, 'mediaType': MANIFEST['mediaType'], 'manifests': []})]:
            with self.subTest(status=status, body=body):
                guard = self.guard()
                transport = RegistryTransport(baseline_status=status, baseline_body=body)
                with self.assertRaises(guard.PublicationBlocked):
                    guard.confirm_missing(TARGET, '0.8.0', opener=transport)
                self.assertNotIn(TARGET_URL, transport.urls)

    def test_network_failures_are_sanitized_and_never_allow_publication(self):
        for url in [TOKEN_URL, BASELINE_URL, TARGET_URL]:
            with self.subTest(url=url):
                self.denied(network_error_at=url)

    def test_missing_token_malformed_json_and_oversized_responses_fail_closed(self):
        for token_body in [{}, {'token': ''}, {'token': TOKEN + '\n'}, b'not-json', b'x' * 200000]:
            with self.subTest(token_body=type(token_body).__name__):
                self.denied(token_body=token_body)
        self.denied(target_body=b'x' * 200000)

    def test_foreign_repo_unstable_tag_and_source_version_disagreement_are_blocked_before_http(self):
        guard = self.guard()
        for image, version in [('ghcr.io/other/image:0.8.0', '0.8.0'),
                               ('ghcr.io/mochiuaena/resume-workbench:latest', '0.8.0'),
                               ('ghcr.io/mochiuaena/resume-workbench:0.8.0-rc.1', '0.8.0'),
                               (TARGET, '0.7.0')]:
            with self.subTest(image=image, version=version):
                def no_http(*args, **kwargs):
                    self.fail('Invalid image must be blocked before HTTP')
                with self.assertRaises(guard.PublicationBlocked):
                    guard.confirm_missing(image, version, opener=no_http)


if __name__ == '__main__':
    unittest.main()
