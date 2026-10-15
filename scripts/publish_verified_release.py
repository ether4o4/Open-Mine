"""Publish a successful workflow's exact signed APK; never build, re-sign, or replace a release."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.error
import urllib.parse
import urllib.request


EXPECTED_JOBS = {'build', 'android-runtime', 'android-modern-ui', 'android-native-inference'}
PUBLISHED_041_SIGNER = '19e9092f2cdd8124db62b27e2fca6c269c0e2d8fbc1b764de9fdea7ad33235cb'


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def sha256(path):
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


class GitHub:
    def __init__(self, repository):
        require(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository), 'Invalid repository')
        self.repository = repository
        self.base = f'https://api.github.com/repos/{repository}'
        self.token = os.environ['GH_TOKEN']

    def request(self, path, method='GET', data=None, raw=False):
        url = path if path.startswith('https://') else self.base + '/' + path
        require(urllib.parse.urlsplit(url).hostname in ('api.github.com', 'uploads.github.com'),
                'Unexpected GitHub API host')
        headers = {'Authorization': f'Bearer {self.token}', 'Accept': 'application/vnd.github+json',
                   'X-GitHub-Api-Version': '2022-11-28'}
        if data is not None:
            headers['Content-Type'] = 'application/octet-stream' if raw else 'application/json'
            if not raw:
                data = json.dumps(data).encode()
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return json.load(response)
        except urllib.error.HTTPError as exc:
            # Do not echo headers, credentials, or a potentially large API response.
            raise RuntimeError(f'GitHub {method} {urllib.parse.urlsplit(url).path}: HTTP {exc.code}') from None

    def items(self, path, field=None):
        result = []
        for page in range(1, 101):
            separator = '&' if '?' in path else '?'
            response = self.request(f'{path}{separator}per_page=100&page={page}')
            batch = response[field] if field else response
            result.extend(batch)
            if len(batch) < 100:
                return result
        raise RuntimeError('GitHub pagination exceeded the publication limit')


def validate_source(source, main):
    require(re.fullmatch(r'[0-9a-f]{40}', source), 'Run has no exact source SHA')
    subprocess.run(['git', 'merge-base', '--is-ancestor', source, main], check=True)
    changed = command('git', 'diff', '--name-only', source, main).splitlines()
    permitted = {'README.md', '.github/workflows/publish.yml', 'scripts/publish_verified_release.py'}
    unexpected = [path for path in changed if path not in permitted and not path.startswith('docs/')]
    require(not unexpected, 'Main differs from tested build/source inputs: ' + ', '.join(unexpected))
    require(not command('git', 'status', '--porcelain', '--untracked-files=no'), 'Checkout has tracked changes')


def validate_run(api, run_id):
    run = api.request(f'actions/runs/{run_id}')
    require(run['head_repository']['full_name'] == api.repository, 'Run source is from another repository')
    require(run['path'] == '.github/workflows/build.yml', 'Run is not the APK verification workflow')
    require(run['event'] in ('push', 'workflow_dispatch'), 'Use a push or manually dispatched verification run')
    require(run['status'] == 'completed' and run['conclusion'] == 'success', 'Verification run must succeed completely')
    jobs = api.items(f'actions/runs/{run_id}/attempts/{run["run_attempt"]}/jobs', 'jobs')
    require({job['name'] for job in jobs} == EXPECTED_JOBS, 'Expected all four verification jobs')
    require(all(job['conclusion'] == 'success' for job in jobs), 'Every verification job must pass')
    return run, jobs


def download_build(api, run, destination):
    name = 'Open-Mine-build-' + run['head_sha']
    matches = [item for item in api.items(f'actions/runs/{run["id"]}/artifacts', 'artifacts')
               if item['name'] == name and not item['expired']]
    require(len(matches) == 1, 'Expected exactly one unexpired signed build artifact')
    subprocess.run(['gh', 'run', 'download', str(run['id']), '--repo', api.repository,
                    '--name', name, '--dir', str(destination)], check=True)
    return matches[0]


def inspect_build(destination, tools, source):
    evidence_path = destination / 'verification/apk-evidence.json'
    evidence = json.loads(evidence_path.read_text())
    require(evidence['source_commit'] == source, 'Artifact source evidence does not match the run')
    require((destination / 'verification/source-commit.txt').read_text().strip() == source,
            'Artifact source commit does not match the run')
    require(not (destination / 'verification/source-status.txt').read_text().strip(),
            'Artifact was built from tracked modifications')
    apk = destination / 'app/build/outputs/apk/debug/app-debug.apk'
    require(sha256(apk) == evidence['apk_sha256'], 'APK checksum does not match original evidence')
    require(apk.stat().st_size == evidence['apk_bytes'], 'APK size does not match original evidence')
    inspected = destination / 'independent-inspection'
    subprocess.run(['python3', 'scripts/inspect_apk.py', str(apk), '--build-tools', str(tools),
                    '--commit', source, '--output', str(inspected)], check=True)
    actual = json.loads((inspected / 'apk-evidence.json').read_text())
    require(actual == evidence, 'Independent APK signature/package/version inspection differs from build evidence')
    require((evidence['package'], evidence['version_name'], evidence['version_code']) ==
            ('com.openmine', '0.4.2-dev', 12), 'Unexpected application or release version')
    require(evidence['published_041_signer_sha256'] == PUBLISHED_041_SIGNER,
            'Published 0.4.1 signing identity changed in evidence')
    require(evidence['published_041_signer_matches'] ==
            (evidence['signer_certificate_sha256'] == PUBLISHED_041_SIGNER), 'Incorrect upgrade compatibility evidence')
    return apk, evidence


def prepare_assets(directory, apk, evidence, run, jobs, artifact, main):
    require(run['conclusion'] == 'success' and all(job['conclusion'] == 'success' for job in jobs),
            'Do not generate passing release notes before verification finishes successfully')
    directory.mkdir()
    asset_name = f'Open-Mine-{evidence["version_name"]}.apk'
    shutil.copyfile(apk, directory / asset_name)
    published_evidence = dict(evidence, release_asset_file=asset_name, verification_run_id=run['id'],
                              verification_run_url=run['html_url'], verification_run_attempt=run['run_attempt'],
                              build_artifact_id=artifact['id'], publication_main_commit=main)
    (directory / 'apk-evidence.json').write_text(json.dumps(published_evidence, indent=2) + '\n')
    warning = evidence['upgrade_compatibility']
    notes = f'''Open Mine {evidence['version_name']} (version code {evidence['version_code']}) includes the verified 0.4.1 source, PR #3 fixes, and the requested runtime and workspace controls.

**{warning}**

- Persistent Bash/background commands, foreground runtime service, managed processes, and authenticated Termux controls.
- Height-only Browser and Terminal resizing with separate saved sizes.
- Return/Send executes shell commands and clears accepted input; up/down recalls the last 100 commands across restarts.
- Hugging Face catalog button and downloaded-GGUF import.
- PR #3 model validation, lifetime, health, and import fixes, with 6% larger text.
- Original orbital dashboard, application ID, and project storage paths preserved.

The APK is copied unchanged from the successful [verification run]({run['html_url']}) (attempt {run['run_attempt']}); publication does not rebuild or re-sign it.

Exact tested source: `{run['head_sha']}`.
Main at publication: `{main}`. Build and source inputs match the tested commit; only publication/documentation changes may differ.
Build artifact ID: `{artifact['id']}`.

Verification jobs:
'''
    for job in sorted(jobs, key=lambda item: item['name']):
        notes += f'- [{job["name"]}]({job["html_url"]}): passed.\n'
    notes += f'''
These include unit tests, lint, packaged APK checks, API29 application/Ollama journeys, API35 UI/platform journeys, and API29 Linux shell/GGUF inference. Physical Samsung S24 Ultra inference remains unverified. Emulator success does not establish physical-device or every-ABI runtime coverage.

Package: `{evidence['package']}`.
APK SHA-256: `{evidence['apk_sha256']}`.
Signer SHA-256: `{evidence['signer_certificate_sha256']}`.
Published 0.4.1 signer SHA-256: `{PUBLISHED_041_SIGNER}`.

Download `{asset_name}`. `apk-evidence.json` records identity and compatibility; `SHA256SUMS.txt` covers the attached APK, evidence, and these notes. This is a development prerelease.
'''
    (directory / 'VERIFICATION.md').write_text(notes)
    sums = ''.join(f'{sha256(path)}  {path.name}\n' for path in sorted(directory.iterdir()))
    (directory / 'SHA256SUMS.txt').write_text(sums)
    return notes


def publish(api, tag, source, directory, notes, main):
    releases = [item for item in api.items('releases') if item['tag_name'] == tag]
    require(len(releases) <= 1, 'Multiple releases found for this tag')
    if releases:
        release = releases[0]
        require(release['draft'], 'Release is already published; never overwrite published assets')
        require(release['target_commitish'] == source, 'Existing draft targets another source commit')
    else:
        release = api.request('releases', 'POST', {'tag_name': tag, 'target_commitish': source,
                              'name': f'Open Mine {tag[1:]} — runtime and workspace controls',
                              'body': notes, 'draft': True, 'prerelease': True})
    expected = {path.name: path for path in directory.iterdir()}
    assets = api.items(f'releases/{release["id"]}/assets')
    require(all(asset['name'] in expected for asset in assets), 'Existing draft has unexpected assets')
    require(len({asset['name'] for asset in assets}) == len(assets), 'Existing draft has duplicate assets')
    uploaded = {asset['name']: asset for asset in assets}
    for name, path in sorted(expected.items()):
        if name not in uploaded:
            upload_url = release['upload_url'].split('{', 1)[0] + '?' + urllib.parse.urlencode({'name': name})
            uploaded[name] = api.request(upload_url, 'POST', path.read_bytes(), raw=True)
        asset = uploaded[name]
        require(asset['state'] == 'uploaded' and asset['size'] == path.stat().st_size,
                f'Release asset upload is incomplete: {name}')
        require(asset.get('digest') == 'sha256:' + sha256(path), f'Release asset checksum mismatch: {name}')
    require(api.request('git/ref/heads/main')['object']['sha'] == main,
            'Main moved during publication; leave the draft unpublished and recheck')
    # Source tags must refer to the actual APK input commit, not the later publisher commit.
    tags = api.items('tags')
    existing_tag = next((item for item in tags if item['name'] == tag), None)
    require(existing_tag is None or existing_tag['commit']['sha'] == source, 'Release tag points at another commit')
    # Recheck the release state immediately before its one-way publication transition.
    require(api.request(f'releases/{release["id"]}')['draft'], 'Release was published concurrently')
    result = api.request(f'releases/{release["id"]}', 'PATCH', {'body': notes, 'draft': False,
                         'prerelease': True, 'target_commitish': source,
                         'name': f'Open Mine {tag[1:]} — runtime and workspace controls'})
    print('Published unchanged verified APK:', result['html_url'])
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as output:
            output.write(f'[Published release]({result["html_url"]})\n\n' + notes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--build-tools', required=True, type=Path)
    parser.add_argument('--work-dir', required=True, type=Path)
    args = parser.parse_args()
    require(re.fullmatch(r'[1-9][0-9]*', args.run_id), 'verification_run_id must contain only digits')
    require(os.environ.get('GITHUB_REF') == 'refs/heads/main', 'Publication must run from main')
    require(not args.work_dir.exists(), 'Use a fresh publication directory')
    args.work_dir.mkdir(parents=True)
    api = GitHub(os.environ['GITHUB_REPOSITORY'])
    run, jobs = validate_run(api, args.run_id)
    main_commit = command('git', 'rev-parse', 'HEAD')
    require(api.request('git/ref/heads/main')['object']['sha'] == main_commit, 'Checkout must be current main')
    validate_source(run['head_sha'], main_commit)
    artifact_dir = args.work_dir / 'artifact'
    artifact = download_build(api, run, artifact_dir)
    apk, evidence = inspect_build(artifact_dir, args.build_tools, run['head_sha'])
    release_dir = args.work_dir / 'release'
    notes = prepare_assets(release_dir, apk, evidence, run, jobs, artifact, main_commit)
    publish(api, 'v' + evidence['version_name'], run['head_sha'], release_dir, notes, main_commit)


if __name__ == '__main__':
    main()
