#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, os, stat, subprocess, tempfile
from pathlib import Path
from bkx.core.impl.hasher import FallbackHasher
from bkx.core.impl.tool_detector import FallbackToolDetector
from bkx.core.impl.traversal import build_source_manifest


def run_json(binary: Path, *args: str, env: dict[str,str] | None=None):
    cp=subprocess.run([str(binary),*args],text=True,capture_output=True,check=False,env=env)
    if cp.returncode != 0:
        raise SystemExit(f"Go probe failed {args}: rc={cp.returncode}\nstdout={cp.stdout}\nstderr={cp.stderr}")
    return json.loads(cp.stdout)


def py_kind(mode:int)->str:
    if stat.S_ISDIR(mode): return 'directory'
    if stat.S_ISLNK(mode): return 'symlink'
    if stat.S_ISREG(mode): return 'file'
    return 'special'


def main()->int:
    ap=argparse.ArgumentParser(); ap.add_argument('--go-binary',type=Path,required=True); ns=ap.parse_args()
    binary=ns.go_binary.resolve()
    with tempfile.TemporaryDirectory(prefix='bkx-go04-parity-') as td:
        base=Path(td); root=base/'root'; (root/'sub').mkdir(parents=True)
        (root/'A.txt').write_text('alpha',encoding='utf-8')
        (root/'b.bin').write_bytes(b'\x00\x01\x02')
        (root/'sub'/'c.txt').write_text('charlie',encoding='utf-8')
        (root/'.hidden').write_text('hidden',encoding='utf-8')

        # Filesystem stat/list parity used by Android filesystem.stat/list.
        pstat=(root/'A.txt').lstat()
        py_stat={'path':str(root/'A.txt'),'name':'A.txt','kind':py_kind(pstat.st_mode),'size_bytes':max(0,pstat.st_size),'modified_at_epoch_millis':max(0,pstat.st_mtime_ns//1_000_000),'mode':stat.S_IMODE(pstat.st_mode),'uid':pstat.st_uid,'gid':pstat.st_gid}
        go_stat=run_json(binary,'migration','filesystem','stat',str(root/'A.txt'))
        assert go_stat == py_stat, (go_stat,py_stat)

        py_entries=sorted(root.iterdir(),key=lambda item:(not item.is_dir(),item.name.casefold(),item.name))
        py_entries=[p for p in py_entries if not p.name.startswith('.')]
        py_list=[]
        for p in py_entries:
            st=p.lstat(); py_list.append({'path':str(p),'name':p.name,'kind':py_kind(st.st_mode),'size_bytes':max(0,st.st_size),'modified_at_epoch_millis':max(0,st.st_mtime_ns//1_000_000),'mode':stat.S_IMODE(st.st_mode),'uid':st.st_uid,'gid':st.st_gid})
        go_list=run_json(binary,'migration','filesystem','list',str(root),'false','250','0')
        assert go_list['entries']==py_list, (go_list['entries'],py_list)
        assert go_list['total_count']==len(py_list) and not go_list['more']

        # Traversal names and metadata parity.
        pm=build_source_manifest((root,),symlink_policy='reject')
        gm=run_json(binary,'migration','traversal','manifest',str(root))
        py_manifest=[{'source_root':str(e.source_root),'path':str(e.path),'archive_name':e.archive_name,'is_dir':e.is_dir,'size':e.size,'mtime_ns':e.mtime_ns,'is_symlink':e.is_symlink} for e in pm.entries]
        assert gm['roots']==[str(x) for x in pm.roots]
        assert gm['entries']==py_manifest, (gm['entries'],py_manifest)

        # Hash file and directory checksum parity.
        hasher=FallbackHasher()
        for algo in ('md5','sha256','sha512'):
            py=hasher.hash_file(root/'A.txt',algo)
            go=run_json(binary,'migration','hash','file',str(root/'A.txt'),algo)
            assert go=={'algorithm':py.algorithm,'checksum':py.checksum,'bytes_hashed':py.bytes_hashed}, (go,py)
        pyd=hasher.hash_directory(root,'sha256')
        god=run_json(binary,'migration','hash','directory',str(root),'sha256')
        assert god['file_count']==pyd.file_count and god['total_bytes']==pyd.total_bytes
        assert god['directory_checksum']==pyd.directory_checksum, (god['directory_checksum'],pyd.directory_checksum)

        # Tool detection/version parsing parity on an isolated PATH.
        tools=base/'tools'; tools.mkdir(); fake=tools/'sha256sum'; fake.write_text("#!/bin/sh\necho 'sha256sum (fake) 7.8.9'\n"); fake.chmod(0o755)
        pyinfo=FallbackToolDetector(environ={'PATH':str(tools)},timeout_seconds=1).detect('sha256sum')
        env=dict(os.environ); env['PATH']=str(tools)
        goinfo=run_json(binary,'migration','tools','detect','sha256sum',env=env)
        assert goinfo['available'] is pyinfo.available
        assert goinfo['version']==pyinfo.version=='7.8.9'
        assert goinfo['capabilities']==list(pyinfo.capabilities)
        assert Path(goinfo['path']).resolve()==pyinfo.path.resolve()

        # Both implementations must reject a source-tree symlink.
        outside=base/'outside'; outside.write_text('outside')
        link=root/'escape'
        try: link.symlink_to(outside)
        except OSError: pass
        else:
            try: hasher.hash_directory(root)
            except Exception: pass
            else: raise AssertionError('Python unexpectedly accepted symlink')
            cp=subprocess.run([str(binary),'migration','hash','directory',str(root),'sha256'],text=True,capture_output=True)
            assert cp.returncode != 0, cp.stdout
    print('BKX GO04 Python-Go filesystem/traversal/hash/tool parity passed.')
    return 0
if __name__=='__main__': raise SystemExit(main())
