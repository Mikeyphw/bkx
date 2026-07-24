
import os, site, sys

# First, drop system-sites related paths.
original_sys_path = sys.path[:]
known_paths = set()
for path in {'/data/data/com.termux/files/home/.local/share/devtool/python/venvs/bkx-f97d7d275cf5a39c/bkx/lib/python3.14/site-packages'}:
    site.addsitedir(path, known_paths=known_paths)
system_paths = set(
    os.path.normcase(path)
    for path in sys.path[len(original_sys_path):]
)
original_sys_path = [
    path for path in original_sys_path
    if os.path.normcase(path) not in system_paths
]
sys.path = original_sys_path

# Second, add lib directories.
# ensuring .pth file are processed.
for path in ['/data/data/com.termux/files/home/Code/bkx/.devtool/tmp/python/pip-build-env-8cb3oyyz/overlay/lib/python3.14/site-packages', '/data/data/com.termux/files/home/Code/bkx/.devtool/tmp/python/pip-build-env-8cb3oyyz/normal/lib/python3.14/site-packages']:
    assert not path in sys.path
    site.addsitedir(path)
