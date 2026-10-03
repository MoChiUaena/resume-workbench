"""Package version-matched public configuration and notices into a fresh directory."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
INVENTORY = 'src/main/resources/META-INF/third-party/inventory.json'
IMAGE_REPOSITORY = 'ghcr.io/mochiuaena/resume-workbench'


def _read_public_file(root, relative):
    path = root / relative
    for part in (path, *path.parents):
        if part == root:
            break
        if part.is_symlink():
            raise ValueError(f'Public input must not be a symbolic link: {relative}')
    if not path.is_file():
        raise ValueError(f'Missing public input: {relative}')
    return path.read_bytes()


def _json_object(root, relative):
    value = json.loads(_read_public_file(root, relative))
    if not isinstance(value, dict):
        raise ValueError(f'{relative} must contain a JSON object')
    return value


def _compose_app_images(text):
    """Read direct services.app.image scalars in the public block-style Compose file."""
    scope = []
    images = []
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        indent = len(line) - len(line.lstrip())
        while scope and scope[-1][0] >= indent:
            scope.pop()
        if [key for _, key in scope] == ['services', 'app']:
            image = re.fullmatch(r'\s*image:\s*(.*?)\s*(?:#.*)?', line)
            if image:
                images.append(image.group(1).strip("'\""))
        block = re.fullmatch(r'\s*([\w-]+):\s*(?:#.*)?', line)
        if block:
            scope.append((indent, block.group(1)))
    return images


def _validate_release_metadata(root):
    pom = ET.fromstring(_read_public_file(root, 'pom.xml'))
    namespace = pom.tag.partition('}')[0] + '}' if pom.tag.startswith('{') else ''
    version = (pom.findtext(namespace + 'version') or '').strip()
    if not re.fullmatch(r'(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)', version):
        raise ValueError('pom.xml project version must be a stable major.minor.patch release')

    package = _json_object(root, 'frontend/package.json')
    lock = _json_object(root, 'frontend/package-lock.json')
    packages = lock.get('packages')
    lock_root = packages.get('') if isinstance(packages, dict) else None
    inventory_bytes = _read_public_file(root, INVENTORY)
    inventory = json.loads(inventory_bytes)
    if not isinstance(inventory, dict):
        raise ValueError(f'{INVENTORY} must contain a JSON object')
    versions = {
        'frontend/package.json version': package.get('version'),
        'frontend/package-lock.json version': lock.get('version'),
        'frontend/package-lock.json packages[""] version':
            lock_root.get('version') if isinstance(lock_root, dict) else None,
        f'{INVENTORY} applicationVersion': inventory.get('applicationVersion'),
    }
    for label, actual in versions.items():
        if actual != version:
            raise ValueError(f'{label} must match pom.xml {version}; got {actual!r}')

    image = f'{IMAGE_REPOSITORY}:{version}'
    compose_bytes = _read_public_file(root, 'compose.yml')
    if _compose_app_images(compose_bytes.decode('utf-8')) != ['${RESUME_APP_IMAGE:-' + image + '}']:
        raise ValueError(f'compose.yml services.app.image must default to {image}')
    env_bytes = _read_public_file(root, '.env.example')
    env_images = re.findall(r'(?m)^[ \t]*RESUME_APP_IMAGE[ \t]*=[ \t]*([^\r\n#]*)',
                            env_bytes.decode('utf-8'))
    if [value.strip().strip("'\"") for value in env_images] != [image]:
        raise ValueError(f'.env.example RESUME_APP_IMAGE must select {image}')
    return version, {'compose.yml': compose_bytes, '.env.example': env_bytes}, inventory_bytes


def _archive_bytes(files):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, content in sorted(files.items()):
            archive.writestr(name, content)
    return stream.getvalue()


def build_release_assets(root=ROOT, output_dir=None, *, expected_version=None):
    """Validate public metadata, then return allowlisted assets written to an empty output."""
    root = Path(root).resolve()
    output = Path(output_dir) if output_dir is not None else root / 'output/release'
    if output.is_symlink() or (output.exists() and
            (not output.is_dir() or any(output.iterdir()))):
        raise ValueError(f'Release output must be a new or empty directory: {output}')

    version, config, inventory_bytes = _validate_release_metadata(root)
    if expected_version is not None and expected_version != version:
        raise ValueError(f'Expected release version {expected_version!r} must match pom.xml {version}')
    notices = {name: _read_public_file(root, name) for name in (
        'LICENSE', 'THIRD_PARTY_NOTICES.md', 'docs/dependency-licenses.md')}
    third_party = root / 'src/main/resources/META-INF/third-party'
    for path in sorted(third_party.rglob('*')):
        if path.is_symlink():
            raise ValueError(f'Public notice must not be a symbolic link: {path.relative_to(root)}')
        if path.is_file():
            name = path.relative_to(third_party).as_posix()
            notices['third-party/' + name] = (inventory_bytes if name == 'inventory.json' else
                                              _read_public_file(root, path.relative_to(root)))
    for name in ('OFL.txt', 'OFL-Serif.txt'):
        notices['fonts/' + name] = _read_public_file(root, 'src/main/resources/static/fonts/' + name)

    artifacts = {
        'resume-workbench-config.zip': _archive_bytes(config),
        'dependency-notices.zip': _archive_bytes(notices),
        'resume-workbench-demo.gif': _read_public_file(root, 'docs/demo.gif'),
    }
    video = 'output/demo/resume-workbench-demo.webm'
    if (root / video).exists():
        artifacts['resume-workbench-demo.webm'] = _read_public_file(root, video)
    checksums = ''.join(hashlib.sha256(content).hexdigest() + '  ' + name + '\n'
                        for name, content in sorted(artifacts.items()))

    output.mkdir(parents=True, exist_ok=True)
    for name, content in sorted(artifacts.items()):
        with (output / name).open('xb') as target:
            target.write(content)
    with (output / 'SHA256SUMS.txt').open('x', encoding='utf-8', newline='\n') as target:
        target.write(checksums)
    return [output / name for name in sorted((*artifacts, 'SHA256SUMS.txt'))]


def main(argv=None, *, root=ROOT):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path,
                        help='new or empty output directory (default: output/release in the project)')
    parser.add_argument('--expected-version', help='require the exact stable source version, without a v prefix')
    args = parser.parse_args(argv)
    try:
        assets = build_release_assets(root, args.output_dir, expected_version=args.expected_version)
    except (OSError, ValueError, ET.ParseError) as error:
        parser.error(str(error))
    print('Release assets:', ', '.join(path.name for path in assets))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
