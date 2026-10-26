"""Verify a Git source tree from raw exported files, without Git metadata/network."""
import hashlib
import os
import pathlib
import stat


def git_hash(kind, data):
    return hashlib.sha1(kind.encode()+b' '+str(len(data)).encode()+b'\0'+data).digest()


def source_tree(directory):
    directory=pathlib.Path(directory)
    entries=[]
    for child in directory.iterdir():
        if child.name=='.git': continue
        mode=child.lstat().st_mode
        name=os.fsencode(child.name)
        if stat.S_ISDIR(mode):
            digest=bytes.fromhex(source_tree(child)); kind=b'40000'; key=name+b'/'
        elif stat.S_ISREG(mode):
            digest=git_hash('blob',child.read_bytes());kind=b'100755' if mode&0o111 else b'100644';key=name
        elif stat.S_ISLNK(mode):
            digest=git_hash('blob',os.fsencode(os.readlink(child)));kind=b'120000';key=name
        else: raise ValueError('Unsupported file kind in source export: '+str(child))
        entries.append((key,kind+b' '+name+b'\0'+digest))
    return git_hash('tree',b''.join(item[1] for item in sorted(entries))).hex()
