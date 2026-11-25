#!/usr/bin/env python3
"""Exercise per-variant pins using tiny isolated Git repositories and raw exports."""
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))
from source_tree import source_tree


class UpstreamLockWiringTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        (self.root/'scripts').mkdir()
        (self.root/'patches/cpu-numeric').mkdir(parents=True)
        for name in ('prepare-cpu-variant.py', 'source_tree.py', 'fetch-upstream.sh', 'export-build-source.py'):
            shutil.copy2(SCRIPTS/name, self.root/'scripts'/name)
        self.locks = {}
        variants = {}
        for name in ('legacy', 'new'):
            pristine = self.root/'third_party'/name
            pristine.mkdir(parents=True)
            self.git(pristine, 'init', '--quiet')
            (pristine/'value.txt').write_text(name+'\n')
            self.git(pristine, 'add', 'value.txt')
            # These commits exist only in the disposable fixture, never the project.
            self.git(pristine, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                     'commit', '--quiet', '-m', name)
            lock = dict(commit=self.git(pristine, 'rev-parse', 'HEAD').strip(),
                        tree=self.git(pristine, 'rev-parse', 'HEAD^{tree}').strip(),
                        path='third_party/'+name)
            self.locks[name] = lock
            lock_name = 'upstream.lock.json' if name == 'legacy' else 'upstream-v0.9.0.lock.json'
            self.write_json(lock_name, lock)
            patch = ('diff --git a/value.txt b/value.txt\n--- a/value.txt\n+++ b/value.txt\n'
                     '@@ -1 +1 @@\n-'+name+'\n+'+name+' patched\n').encode()
            patch_path = 'patches/cpu-numeric/'+name+'.patch'
            (self.root/patch_path).write_bytes(patch)
            (pristine/'value.txt').write_text(name+' patched\n')
            patched_tree = source_tree(pristine)
            self.git(pristine, 'checkout', '--', 'value.txt')
            variants[name] = dict(directory='.tools/'+name, patched_tree=patched_tree,
                                  patches=[dict(path=patch_path, sha256=hashlib.sha256(patch).hexdigest())])
            if name == 'new':
                variants[name]['upstream_lock'] = lock_name
        self.series = dict(upstream_commit=self.locks['legacy']['commit'], variants=variants)
        self.write_json('patches/cpu-numeric/series.json', self.series)

    def write_json(self, name, value):
        (self.root/name).write_text(json.dumps(value))

    def git(self, directory, *args):
        result = subprocess.run(['git', '-C', str(directory), *args], check=True, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                env=dict(os.environ, GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull))
        return result.stdout

    def run_script(self, name, *args, ok=True):
        command = ['bash' if name.endswith('.sh') else sys.executable, str(self.root/'scripts'/name), *args]
        result = subprocess.run(command, cwd=self.root, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if ok:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout)
        return result

    def test_git_variants_use_distinct_pins_and_preserve_edits(self):
        for name in ('legacy', 'new'):
            self.run_script('prepare-cpu-variant.py', name)
            self.run_script('prepare-cpu-variant.py', name)
            prepared = self.root/'.tools'/name
            self.assertEqual(self.git(prepared, 'rev-parse', 'HEAD').strip(), self.locks[name]['commit'])
            (prepared/'value.txt').write_text('local edits\n')
            self.run_script('prepare-cpu-variant.py', name, ok=False)
            self.assertEqual((prepared/'value.txt').read_text(), 'local edits\n')

    def test_raw_exports_use_distinct_pins_and_verify_complete_tree(self):
        for name in ('legacy', 'new'):
            shutil.rmtree(self.root/self.locks[name]['path']/'.git')
            self.run_script('prepare-cpu-variant.py', name)
            self.run_script('prepare-cpu-variant.py', name)
            metadata = json.loads((self.root/('.tools/'+name+'.motoai-source.json')).read_text())
            self.assertEqual(metadata['upstream_commit'], self.locks[name]['commit'])
            (self.root/self.locks[name]['path']/'value.txt').write_text('wrong pristine\n')
            self.run_script('prepare-cpu-variant.py', name, ok=False)

    def test_wrong_git_commit_or_tree_is_rejected(self):
        for field in ('commit', 'tree'):
            lock = dict(self.locks['new'], **{field: '0'*40})
            self.write_json('upstream-v0.9.0.lock.json', lock)
            self.run_script('prepare-cpu-variant.py', 'new', ok=False)
            self.assertFalse((self.root/'.tools/new').exists())

    def test_unsafe_lock_paths_are_rejected(self):
        for path in ('../upstream.lock.json', str(self.root/'upstream.lock.json')):
            self.series['variants']['new']['upstream_lock'] = path
            self.write_json('patches/cpu-numeric/series.json', self.series)
            self.run_script('prepare-cpu-variant.py', 'new', ok=False)
            self.run_script('fetch-upstream.sh', path, ok=False)

    def test_fetch_uses_selected_pin_without_network_and_preserves_edits(self):
        for name, lock_path in (('legacy', 'upstream.lock.json'), ('new', 'upstream-v0.9.0.lock.json')):
            self.run_script('fetch-upstream.sh', lock_path)
            pristine = self.root/self.locks[name]['path']
            (pristine/'value.txt').write_text('local edits\n')
            self.run_script('fetch-upstream.sh', lock_path, ok=False)
            self.assertEqual((pristine/'value.txt').read_text(), 'local edits\n')

    def test_export_includes_both_pins_for_offline_preparation(self):
        for name in ('.gitignore', 'LICENSE', 'README.md', 'build.gradle.kts', 'settings.gradle.kts',
                     'gradle.properties', 'gradlew', 'app/build.gradle.kts', 'baseline-driver/build.gradle.kts',
                     'toolchain.lock.json', 'tool-archives.lock.json', 'native-dependencies.lock.json'):
            path = self.root/name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n')
        (self.root/'scripts/check-public-source.py').write_text('pass\n')
        self.run_script('export-build-source.py', str(self.root/'build/export'))
        exported = self.root/'build/export'
        for name in ('legacy', 'new'):
            self.assertEqual(source_tree(exported/self.locks[name]['path']), self.locks[name]['tree'])
            result = subprocess.run([sys.executable, str(exported/'scripts/prepare-cpu-variant.py'), name],
                                    cwd=exported, text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
