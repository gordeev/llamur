#!/usr/bin/env python3
"""Check Git-visible source files; optionally export a fresh public tree without Git history."""
import argparse
import pathlib
import re
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
PRIVATE_DIRS = {'.agents', '.agent-context', '.agent-handoff', '.ai', '.claude', '.codex',
                '.cursor', '.continue', '.skills', 'skills', '.idea', '.vscode', '.aws', '.ssh', '.android',
                '.tools', '.gradle', '.kotlin', '.cxx', '__pycache__', 'models', 'build', 'verify-logs'}
PRIVATE_NAMES = {'agents.md', 'claude.md', 'gemini.md', 'skill.md', '.mcp.json', '.mcp.toml',
                 'local.properties', 'key.properties', 'keystore.properties', 'credentials.json',
                 'google-services.json', '.netrc', '.npmrc', '.pypirc'}
PRIVATE_SUFFIXES = {'.jks', '.keystore', '.p12', '.pfx', '.key', '.pem', '.apk', '.aab',
                    '.apks', '.idsig', '.hprof', '.log', '.gguf', '.pyc'}
SECRETS = re.compile(rb'(?:sk-or-v1-[A-Za-z0-9]{24,}|sk-(?:proj-|ant-)?[A-Za-z0-9_-]{32,}|'
                     rb'hf_[A-Za-z0-9]{25,}|gh[pousr]_[A-Za-z0-9]{25,}|github_pat_[A-Za-z0-9_]{30,}|'
                     rb'AKIA[0-9A-Z]{16}|-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----)')
LOCAL_PATH = re.compile(rb'(?:/Users/[A-Za-z0-9._-]+/|/home/[A-Za-z0-9._-]+/)')


def private_path(name):
    path = pathlib.PurePosixPath(name)
    return (any(p in PRIVATE_DIRS for p in path.parts) or path.name.lower() in PRIVATE_NAMES or
            path.suffix.lower() in PRIVATE_SUFFIXES or name in {'PLAN.md', 'TASK.md'} or
            path.parts[0] in {'build', 'refs'} or name.startswith('bench/results/') or
            path.name.startswith('.env') and path.name not in {'.env.example', '.env.sample'} or
            path.name.startswith('service-account') and path.suffix == '.json')


def issues(name, data):
    found = []
    if private_path(name): found.append('local/generated/assistant file')
    if SECRETS.search(data): found.append('credential/private-key pattern')
    if LOCAL_PATH.search(data): found.append('absolute workstation home path')
    return found


def git(*args, **kwargs):
    return subprocess.check_output(['git', '-C', str(ROOT), *args], **kwargs)


def candidates():
    return sorted(set(git('ls-files', '--cached', '--others', '--exclude-standard', '-z').decode().strip('\0').split('\0')))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--history', action='store_true', help='Also check reachable Git blobs; never rewrites history')
    parser.add_argument('--export', type=pathlib.Path, help='Fresh directory inside build/; current sources only, no Git metadata or vendor checkout')
    args = parser.parse_args()
    names = candidates()
    failures = []
    for name in names:
        path = ROOT / name
        if path.is_symlink(): failures.append((name, 'symlink is not a public source file'));continue
        if not path.is_file(): failures.append((name, 'tracked file is missing'));continue
        for reason in issues(name, path.read_bytes()): failures.append((name, reason))
    if args.history:
        objects = [row.split(' ', 1) for row in git('rev-list', '--objects', '--all', text=True).splitlines() if ' ' in row]
        raw = git('cat-file', '--batch', input=''.join(oid+'\n' for oid, _ in objects).encode())
        offset = 0
        for oid, name in objects:
            end = raw.index(b'\n', offset);header = raw[offset:end].split();size = int(header[2])
            blob = raw[end+1:end+1+size];offset = end+size+2
            if header[1] == b'blob':
                for reason in issues(name, blob): failures.append((name+' @ '+oid[:12], reason))
    if failures:
        for name, reason in failures: print(f'FAIL {name}: {reason}')
        print('No file contents or credential values are printed. Nothing was exported.')
        return 1
    print(f'PASS: {len(names)} current source files; no matching secrets, local paths or excluded artifacts.')
    if args.export:
        target = args.export.resolve()
        if not target.is_relative_to(ROOT/'build') or target.exists(): parser.error('Use a fresh destination inside build/')
        target.mkdir(parents=True)
        for name in names:
            source = ROOT/name;destination = target/name;destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination);destination.chmod(0o755 if source.stat().st_mode & 0o111 else 0o644)
        print(f'Public tree without Git history: {target}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
