"""Keep local agent state, signing material and credentials out of public snapshots."""
import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('public_source', ROOT/'scripts/check-public-source.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PublicSourceTests(unittest.TestCase):
    def test_private_paths(self):
        for name in ['AGENTS.md', '.agents/rules/generic.md', 'CLAUDE.md', 'TASK.md',
                     'PLAN.md', '.env', '.envrc', '.netrc', '.aws/credentials', 'app/.env.local', 'release.jks', 'signing.key',
                     'app/build/outputs/app.apk', 'models/model.gguf', '.codex/config.toml',
                     'skills/local/SKILL.md', 'bench/results/phone/report.json']:
            self.assertTrue(module.private_path(name), name)
        for name in ['README.md', '.env.example', 'app/src/main/AndroidManifest.xml',
                     'gradle/wrapper/gradle-wrapper.jar', 'bench/model-lock.json', 'LICENSE']:
            self.assertFalse(module.private_path(name), name)

    def test_secret_values_are_detected_without_printing(self):
        for prefix in [b'sk-or-v1-', b'sk-proj-', b'hf_', b'ghp_']:
            self.assertIn('credential/private-key pattern', module.issues('fixture.txt', prefix+b'x'*64))
        private = b'-----BEGIN '+b'PRIVATE KEY-----'
        self.assertIn('credential/private-key pattern', module.issues('fixture.txt', private))
        self.assertEqual([], module.issues('fixture.txt', b'API_KEY=YOUR_API_KEY'))

    def test_ignore_matches_private_examples(self):
        import subprocess
        names=['.agents/local.md','AGENTS.md','.env.local','app/signing.jks','models/example.gguf','app/build/app.apk']
        found=subprocess.check_output(['git','-C',str(ROOT),'check-ignore','--no-index','--stdin'],input='\n'.join(names)+'\n',text=True).splitlines()
        self.assertEqual(names,found)
