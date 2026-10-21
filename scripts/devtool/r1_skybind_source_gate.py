#!/usr/bin/env python3
"""BKX opt-in real R1 client source gate; no SDK/Gradle invocation locally."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
CLIENT = ROOT/'android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/r1/SkybindR1ReadClient.kt'
SCREEN = ROOT/'android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/more/RcloneRemotesScreen.kt'
TEST = ROOT/'android/BKX.Android/app/src/androidTest/java/com/mikeyphw/bkx/android/r1/SkybindR1ClientSafetyInstrumentationTest.kt'

def check():
    client,screen,tests = (f.read_text() for f in (CLIENT,SCREEN,TEST))
    checks={
        'REAL_BKX_UI_CALLS_CLIENT':'SkybindR1ReadClient.read(op, remote, path)' in screen,
        'READS_RUN_OFF_MAIN':'withContext(Dispatchers.IO)' in screen,
        'APP_UID_SOCKET_NOT_ROOT_PROCESS':'android.net.LocalSocket' in client and 'su -c' not in client,
        'EXACT_READONLY_SET':all(o in client for o in ('rclone.lsjson','rclone.size','rclone.listremotes','rclone.version','system.capabilities')),
        'OPERATOR_GRANT_REQUIRED':'GRANTS' in client and 'skybind-r1-grant-handoff-v1' in client,
        'NO_UNAUTHORIZED_NATIVE_FALLBACK':'rclone copy' not in client.lower() and 'ProcessBuilder' not in client,
        'VALIDATE_BEFORE_NETWORK':'require(validPath(path))' in client and 'require(operation in operations)' in client,
        'MALICIOUS_ARGUMENT_TESTS':'deniesWriteOperationsBeforeConnecting' in tests and 'deniesPathEscapeBeforeConnecting' in tests,
    }
    for name, passed in checks.items():
        print(('PASS_' if passed else 'FAIL_')+name)
    return 0 if all(checks.values()) else 2
if __name__=='__main__':
    raise SystemExit(check())
