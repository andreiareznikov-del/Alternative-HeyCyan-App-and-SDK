"""Offline signer. Private material is supplied by path, never generated or uploaded by CI."""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--apksigner-jar', required=True)
parser.add_argument('--keystore', required=True)
parser.add_argument('--password-file', required=True)
parser.add_argument('--expected-cert-sha256', required=True)
parser.add_argument('unsigned')
parser.add_argument('signed')
args = parser.parse_args()
assert Path(args.unsigned).resolve() != Path(args.signed).resolve()
subprocess.run(['java', '-jar', args.apksigner_jar, 'sign', '--ks', args.keystore,
    '--ks-key-alias', 'w610chatgpt', '--ks-pass', 'file:'+args.password_file,
    '--key-pass', 'file:'+args.password_file, '--out', args.signed, args.unsigned], check=True)
result = subprocess.run(['java', '-jar', args.apksigner_jar, 'verify', '--verbose', '--print-certs', args.signed], check=True, text=True, capture_output=True)
cert = re.search(r'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)', result.stdout)
assert cert and cert.group(1).lower() == args.expected_cert_sha256.lower().replace(':',''), 'Unexpected signing certificate'
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in result.stdout
with zipfile.ZipFile(args.unsigned) as a, zipfile.ZipFile(args.signed) as b:
    assert a.read('AndroidManifest.xml') == b.read('AndroidManifest.xml'), 'Manifest changed after CI audit'
    for name in a.namelist():
        if not name.startswith('META-INF/'):
            assert a.read(name) == b.read(name), name
sha = hashlib.sha256(Path(args.signed).read_bytes()).hexdigest()
Path(args.signed+'.sha256').write_text(sha+'  '+Path(args.signed).name+'\n')
Path(args.signed+'.signature.txt').write_text(result.stdout)
print(result.stdout)
print('APK SHA-256:', sha)
