"""Record identity of the exact inspected APK. Runtime claims belong to separate test evidence."""
import argparse
import hashlib
import json
import pathlib
import re
import struct
import subprocess
import zipfile


def elf_details(data):
    if data[:4] != b'\x7fELF':
        raise ValueError('Packaged native library is not ELF')
    endian = '<' if data[5] == 1 else '>'
    bits = 64 if data[4] == 2 else 32
    machine = struct.unpack_from(endian + 'H', data, 18)[0]
    if bits == 64:
        offset = struct.unpack_from(endian + 'Q', data, 32)[0]
        size, count = struct.unpack_from(endian + 'HH', data, 54)
        align_offset, align_type = 48, 'Q'
    else:
        offset = struct.unpack_from(endian + 'I', data, 28)[0]
        size, count = struct.unpack_from(endian + 'HH', data, 42)
        align_offset, align_type = 28, 'I'
    load_alignments = []
    for number in range(count):
        start = offset + number * size
        if struct.unpack_from(endian + 'I', data, start)[0] == 1:
            load_alignments.append(struct.unpack_from(endian + align_type, data, start + align_offset)[0])
    return {'elf_bits': bits, 'machine': machine, 'load_segment_alignment': load_alignments,
            'has_16k_elf_alignment': bool(load_alignments) and all(x >= 16384 for x in load_alignments)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=pathlib.Path)
    parser.add_argument('--build-tools', required=True, type=pathlib.Path)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--output', required=True, type=pathlib.Path)
    args = parser.parse_args()
    assert re.fullmatch(r'[a-f0-9]{40}', args.commit), 'An exact source commit is required'
    args.output.mkdir(parents=True, exist_ok=True)
    badging = subprocess.check_output([str(args.build_tools / 'aapt'), 'dump', 'badging', str(args.apk)], text=True)
    signing = subprocess.check_output([str(args.build_tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(args.apk)], text=True, stderr=subprocess.STDOUT)
    (args.output / 'apk-manifest.txt').write_text(badging)
    (args.output / 'apk-signature.txt').write_text(signing)
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    assert package, 'Cannot read APK package identity'
    assert package.groups() == ('com.openmine', '5', '0.3.0-dev'), 'Unexpected package or upgrade version'
    cert = re.search(r'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)', signing)
    assert cert, 'Cannot read APK signer certificate'
    native = {}
    with zipfile.ZipFile(args.apk) as archive:
        for name in sorted(archive.namelist()):
            if name.startswith('lib/') and name.endswith('.so'):
                data = archive.read(name)
                native[name] = {'sha256': hashlib.sha256(data).hexdigest(), **elf_details(data)}
        shell = archive.read('assets/sandbox/morsllm.sh')
        for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64'):
            for library in ('libproot.so', 'libproot-loader.so', 'libtalloc.so'):
                assert f'lib/{abi}/{library}' in native, f'Missing shell runtime: {abi}/{library}'
    checksum = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    abi_list = sorted({name.split('/')[1] for name in native})
    evidence = {
        'source_commit': args.commit, 'variant': 'debug', 'package': package.group(1),
        'version_code': int(package.group(2)), 'version_name': package.group(3),
        'apk_file': args.apk.name, 'apk_bytes': args.apk.stat().st_size, 'apk_sha256': checksum,
        'signer_certificate_sha256': cert.group(1).lower(), 'signature_verification': 'passed apksigner verify',
        'minimum_sdk': re.search(r"sdkVersion:'([^']+)'", badging).group(1),
        'target_sdk': re.search(r"targetSdkVersion:'([^']+)'", badging).group(1),
        'packaged_abis': abi_list, 'native_libraries': native,
        'packaged_shell_sha256': hashlib.sha256(shell).hexdigest(),
        'upgrade_compatibility': 'UNVERIFIED: existing installed signing identity/private signing key unavailable; never uninstall or clear data to bypass a signature mismatch.',
        'abi_runtime_coverage': 'See Android test artifacts. Packaging an ABI does not prove it runs.',
        'inference_status': 'UNVERIFIED unless separate actual GGUF/Ollama runtime evidence establishes it; no fixture result is inference evidence.',
        'distribution': 'Development APK only. CI debug signing keys are not stable upgrade/release credentials.',
    }
    (args.output / 'apk-evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
    (args.output / 'app-debug.apk.sha256').write_text(f'{checksum}  app-debug.apk\n')
    print(json.dumps({key: evidence[key] for key in ('source_commit', 'variant', 'package', 'version_code', 'version_name', 'apk_sha256', 'signer_certificate_sha256', 'packaged_abis')}, indent=2))


if __name__ == '__main__':
    main()
