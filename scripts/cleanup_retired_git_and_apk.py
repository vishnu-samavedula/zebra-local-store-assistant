"""Apply the exact reviewed cleanup plan, preserving real Git objects and history."""
import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / 'output/cleanup-git-archive-20261020'


def sha(path):
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


def main():
    plan = json.loads((EVIDENCE / 'plan.json').read_text())
    if (EVIDENCE / 'result.json').exists():
        raise FileExistsError('Existing cleanup result preserved')
    assert not (ROOT / '.gitignore').exists(), 'Concurrent root ignore changes must be preserved'
    locks = list((ROOT / '.git').rglob('*.lock'))
    assert not locks, f'Git lock files present: {locks}'
    processes = subprocess.check_output(['ps', '-axo', 'pid=,comm='], text=True)
    writers = []
    for line in processes.splitlines():
        parts = line.strip().split(None, 1)
        if len(parts) == 2:
            command = Path(parts[1]).name
            if command == 'git' or command.startswith('git-'):
                writers.append(line)
    assert not writers, f'Active Git process; defer cleanup: {writers}'
    handles = subprocess.run(['/usr/sbin/lsof', '-nP', '+d', str(ROOT / '.git/objects/98')], text=True, capture_output=True)
    assert handles.returncode in (0, 1), handles.stderr
    assert 'tmp_obj_' not in handles.stdout, 'A temporary Git object is still open'
    (EVIDENCE / 'open-handle-check.json').write_text(json.dumps(dict(returncode=handles.returncode, stdout=handles.stdout, stderr=handles.stderr), indent=2)+'\n')
    assert subprocess.check_output(['git', 'show-ref'], cwd=ROOT, text=True) == plan['refs']
    for rel, expected in plan['protected_sha256'].items():
        assert sha(ROOT / rel) == expected, f'Concurrent change: {rel}'
    for rel, expected in plan['expected_doc_sha256'].items():
        assert sha(ROOT / rel) == expected, f'Concurrent documentation change: {rel}'
    assert len(plan['git_temporary_files']) == 199
    for row in plan['git_temporary_files']:
        path = ROOT / row['path']
        assert path.parent == ROOT / '.git/objects/98'
        assert re.fullmatch(r'tmp_obj_[A-Za-z0-9]+', path.name)
        assert not path.is_symlink()
        stat = path.stat()
        assert stat.st_ino == row['inode'] and stat.st_size == row['bytes'] and stat.st_mtime_ns == row['mtime_ns']
        assert time.time() - stat.st_mtime > 7 * 86400
    for row in plan['obsolete_packages']:
        path = ROOT / row['path']
        assert path.parent == ROOT / 'archive/apk' and path.suffix == '.apk'
        assert not path.is_symlink() and sha(path) == row['sha256']
    removed = []
    try:
        for row in plan['git_temporary_files'] + plan['obsolete_packages']:
            (ROOT / row['path']).unlink()
            removed.append(row['path'])
        (ROOT / '.gitignore').write_bytes((EVIDENCE / 'gitignore.proposed').read_bytes())
        (ROOT / 'demo_poc_solution.md').write_bytes((EVIDENCE / 'demo_poc_solution.proposed').read_bytes())
        (ROOT / 'archive/apk/README.md').write_bytes((EVIDENCE / 'archive-readme.proposed').read_bytes())
        changed = [rel for rel, expected in plan['protected_sha256'].items() if not (ROOT / rel).is_file() or sha(ROOT / rel) != expected]
        assert not changed, changed
        assert subprocess.check_output(['git', 'show-ref'], cwd=ROOT, text=True) == plan['refs']
        counts = subprocess.run(['git', 'count-objects', '-vH'], cwd=ROOT, text=True, capture_output=True, check=True)
        assert 'garbage: 0\n' in counts.stdout and not counts.stderr, counts.stderr
        result = dict(completed=True, completed_at=datetime.datetime.now().astimezone().isoformat(),
            removed_temporary_files=len(plan['git_temporary_files']), removed_packages=len(plan['obsolete_packages']),
            reclaimed_bytes=sum(r['bytes'] for r in plan['git_temporary_files'] + plan['obsolete_packages']),
            protected_files_verified=len(plan['protected_sha256']), protected_files_changed=changed,
            git_refs_unchanged=True, git_objects_summary=counts.stdout,
            ignore_restored=True, archive_notes_updated=True, removed_paths=removed)
        (EVIDENCE / 'result.json').write_text(json.dumps(result, indent=2)+'\n')
        print(json.dumps({k:v for k,v in result.items() if k != 'removed_paths'}, indent=2))
    finally:
        (EVIDENCE / 'deletion-progress.json').write_text(json.dumps(dict(removed_paths=removed), indent=2)+'\n')


if __name__ == '__main__':
    main()
