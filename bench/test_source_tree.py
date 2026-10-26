import pathlib
import subprocess
import sys
import tempfile
import unittest
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'scripts'))
from source_tree import source_tree


class SourceExportIdentityTests(unittest.TestCase):
    def test_matches_git_tree_without_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory)
            subprocess.run(['git','init','-q',str(root)],check=True)
            (root/'a').write_text('ordinary\n');(root/'adir').mkdir()
            (root/'adir'/'z').write_bytes(b'\0binary\xff')
            (root/'a.c').write_text('file sorting before a-directory/\n')
            (root/'run').write_text('#!/bin/sh\n');(root/'run').chmod(0o755)
            (root/'link').symlink_to('adir/z')
            subprocess.run(['git','-C',str(root),'add','--all'],check=True)
            expected=subprocess.check_output(['git','-C',str(root),'write-tree'],text=True).strip()
            self.assertEqual(source_tree(root),expected)
            (root/'a').write_text('altered\n')
            self.assertNotEqual(source_tree(root),expected)
