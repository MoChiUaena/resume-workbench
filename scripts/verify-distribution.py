"""Confirm the executable JAR matches the checked-in dependency inventory."""
from pathlib import Path
import hashlib
import json
import zipfile
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
inventory=json.loads((ROOT/'src/main/resources/META-INF/third-party/inventory.json').read_text(encoding='utf-8'))
application_version=ET.parse(ROOT/'pom.xml').getroot().find('{http://maven.apache.org/POM/4.0.0}version').text
assert inventory['applicationVersion']==application_version and not inventory['unresolvedDeclarations']
with zipfile.ZipFile(ROOT/'target/resume-workbench.jar') as archive:
    expected=set()
    for dependency in inventory['java']:
        if not dependency['packaged']: continue
        _,artifact,version=dependency['coordinate'].split(':')
        name=f'BOOT-INF/lib/{artifact}-{version}.jar';expected.add(name)
        assert hashlib.sha256(archive.read(name)).hexdigest()==dependency['sha256'],f'Dependency drift: {name}'
        for text in dependency['upstreamTexts']:
            assert archive.read('META-INF/third-party/'+text),text
    actual={name for name in archive.namelist() if name.startswith('BOOT-INF/lib/') and name.endswith('.jar')}
    assert actual==expected,(actual-expected,expected-actual)
    assert b'Start-Class: dev.localresume.LocalResumeApplication' in archive.read('META-INF/MANIFEST.MF')
    assert b'Spring-Boot-Version: 3.5.16' in archive.read('META-INF/MANIFEST.MF')
    assert f'Implementation-Version: {application_version}'.encode() in archive.read('META-INF/MANIFEST.MF')
    assert not any(name.endswith('.class') for name in archive.namelist() if name.startswith('META-INF/third-party/'))
print(f'Distribution verified: {len(expected)} runtime JARs, exact hashes, notices and fixed entry point.')
