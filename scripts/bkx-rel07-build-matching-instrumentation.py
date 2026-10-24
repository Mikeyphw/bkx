#!/usr/bin/env python3
"""Local-only BKX REL-07 instrumentation APK builder & LIFE signer.

Creates no source commits and never installs APKs, alters signing identity, or
copies secrets into files. Requires two audited local RM-16 data receipts for the
new release source; outputs separate same-LIFE-signer test APKs. Explicit --execute means operator approves local
build and signing of androidTest APKs using the existing LIFE identity.
"""
from __future__ import annotations
import argparse, contextlib, hashlib, json, os, re, subprocess, sys, tempfile
from pathlib import Path

# Source SHA and LIFE certificate are read from audited local Devtool receipts.
# Never bake a previous release SHA or signer into a future build utility.
SHA = ''
SIGNER = ''
PKG = 'com.mikeyphw.bkx.android'
TESTPKG = PKG + '.test'
RUNNER = 'androidx.test.runner.AndroidJUnitRunner'
EDITION = ('embedded', 'lite')

class Rejected(RuntimeError): pass

def require(cond, msg):
    if not cond: raise Rejected(msg)

def command(argv, *, cwd=None, env=None, timeout=120):
    try:
        r = subprocess.run(argv, cwd=cwd, env=env, capture_output=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise Rejected('Tool unavailable or timed out: '+str(argv[0])) from None
    require(r.returncode == 0, 'Tool exited nonzero: '+str(argv[0]))
    return r.stdout.decode('utf-8', 'replace')

def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for c in iter(lambda:f.read(1024*1024), b''):h.update(c)
    return h.hexdigest()

def parse_cert(output):
    require(re.search(r'^Verifies\s*$', output, re.M) is not None, 'APK did not verify')
    counts = re.findall(r'^Number of signers:\s*(\d+)\s*$', output, re.M)
    require(len(counts) == 1 and counts[0] == '1', 'Expected exactly one signer')
    certs = re.findall(r'^(?:Signer #\d+ certificate SHA-256 digest:|V\d+(?:\.\d+)? Signer: certificate SHA-256 digest:)\s*([0-9a-fA-F:]{64,95})\s*$',output,re.M)
    vals = {v.replace(':','').lower() for v in certs}
    require(len(vals) == 1 and all(re.fullmatch(r'[0-9a-f]{64}',v) for v in vals), 'Signer certificate missing or ambiguous')
    return next(iter(vals))

def parse_badging(output):
    pkg = re.findall(r"^package: name='([^']+)'", output, re.M)
    instr = re.findall(r"^instrumentation:\s+name='([^']+)'\s+targetPackage='([^']+)'", output, re.M)
    require(pkg == [TESTPKG], 'Test APK package identity mismatch')
    require(instr == [(RUNNER, PKG)], 'Instrumentation runner or target identity mismatch')

def source_check(root):
    require(root.is_dir() and not root.is_symlink(), 'Repository unavailable')
    require(command(['git','rev-parse','HEAD'],cwd=root).strip() == SHA, 'Git HEAD differs from signed release source')
    require(not command(['git','status','--porcelain','--untracked-files=normal'],cwd=root).strip(), 'Git checkout must be clean')
    sign = json.loads((Path.home()/'bkx-rm16-sign.json').read_text())
    require(sign.get('operation') == 'local-life-sign' and sign.get('complete') is True, 'Missing complete local LIFE signing receipt')
    signed = sign.get('signed')
    require(isinstance(signed,list) and len(signed) == 2, 'Missing exact signed production pair')
    for i,kind in enumerate(EDITION):
        info=signed[i].get('signing',{})
        require(info.get('expected_signer_sha256')==SIGNER and info.get('remote_head_sha')==SHA and
                info.get('variant') == kind+'Release' and info.get('signature_verified') is True,
                'Release signer/source/edition receipt mismatch')
    # Also bind exact production bytes, which are NEVER rewritten by this script.
    for item in signed:
        d = item['signing']
        p = Path(d['signed_package'])
        require(p.is_file() and not p.is_symlink() and sha(p)==d['signed_sha256'], 'Production signed APK missing or changed')
    return sign

def metadata_candidate(project, edition):
    base = project/'app/build/outputs/apk/androidTest'
    require(base.is_dir(), 'AndroidTest output directory not produced')
    found=[]
    for m in base.rglob('output-metadata.json'):
        if m.is_symlink() or m.stat().st_size>1_048_576:continue
        meta=json.loads(m.read_text())
        if meta.get('variantName') != edition+'ReleaseAndroidTest':continue
        require(meta.get('applicationId') == TESTPKG, 'AndroidTest AGP package mismatch')
        elems=meta.get('elements')
        require(isinstance(elems,list) and len(elems)==1, 'Expected one AGP instrumentation artifact')
        file=elems[0].get('outputFile')
        require(isinstance(file,str) and re.fullmatch(r'[A-Za-z0-9_.-]+\.apk',file), 'Unsafe or missing AGP APK basename')
        p=m.parent/file
        require(p.is_file() and not p.is_symlink(), 'AGP referenced missing or unsafe APK')
        found.append(p)
    require(len(found)==1, 'Expected exactly one matching AndroidTest release variant metadata record')
    return found[0]

def build(root, variants, log):
    project=root/'android/BKX.Android'
    wrapper=project/'gradlew'
    require(wrapper.is_file() and not wrapper.is_symlink(), 'BKX reviewed Gradle wrapper missing')
    with tempfile.TemporaryDirectory(prefix='bkx-rel07-gradle-') as tmp:
        init=Path(tmp)/'test-build-type.init.gradle'
        init.write_text('''// Outside source checkout; select release for instrumentation builds.
gradle.beforeProject { p ->
    if (p.path == ':app') {
        p.pluginManager.withPlugin('com.android.application') {
            p.extensions.getByName('android').testBuildType = 'release'
        }
    }
}
''')
        argv=['bash',str(wrapper),'--no-daemon','--no-parallel','--max-workers=1',
              '--no-configuration-cache','--init-script',str(init)] + [':app:assemble'+x.capitalize()+'ReleaseAndroidTest' for x in variants]
        print('Building AndroidTest release variants using the pinned Gradle wrapper. No APK installation.',flush=True)
        with log.open('xb') as f:
            try:
                proc=subprocess.run(argv,cwd=project,stdout=f,stderr=subprocess.STDOUT,timeout=2700,check=False)
            except (OSError,subprocess.TimeoutExpired):
                raise Rejected('Gradle could not start or timed out; see local build log') from None
        require(proc.returncode==0, 'Gradle AndroidTest build failed; see local build log')
    return {v:metadata_candidate(project,v) for v in variants}

def sign_test(root, apk, edition, dest):
    # Import existing Devtool signing provider only at an explicitly authorized local boundary.
    from devtool.actions_setup import _signing_profile, _keystore, _signer_material
    from devtool.actions_local_release import _local_signing_password
    profile=_signing_profile(root,identity_id='devtool-android-release',target='bkx_android',variant=edition+'Release')
    require(profile.mode=='devtool' and profile.expected_cert_sha256.lower()==SIGNER,
            'LIFE identity fingerprint or policy changed')
    _keystore(profile.keystore,root)
    require(not dest.exists() and not dest.is_symlink(), 'Refusing overwrite of signed test APK')
    with _local_signing_password(profile):
        keystore, secrets, fingerprint = _signer_material(root,profile)
        try:
            require(fingerprint.lower()==SIGNER,'LIFE signer changed during selection')
            with tempfile.TemporaryDirectory(prefix='bkx-test-sign-') as tmp:
                aligned=Path(tmp)/'aligned.apk'
                args=['zipalign','-f','-p','4',str(apk),str(aligned)]
                command(args)
                env=os.environ.copy()
                env.update(secrets)
                try:
                    signer=['apksigner','sign','--ks',str(keystore),'--ks-key-alias',profile.alias,
                            '--ks-pass','env:DEVTOOL_ANDROID_SIGNING_STORE_PASSWORD',
                            '--key-pass','env:DEVTOOL_ANDROID_SIGNING_KEY_PASSWORD',
                            '--out',str(dest),str(aligned)]
                    command(signer,env=env)
                finally:env.clear()
        finally:secrets.clear()
    # Validate the final bytes and fail closed if any old signer survives.
    cert=parse_cert(command(['apksigner','verify','--verbose','--print-certs',str(dest)]))
    require(cert==SIGNER,'Signed test APK has wrong certificate')
    parse_badging(command(['aapt','dump','badging',str(dest)]))
    command(['zipalign','-c','-p','4',str(dest)])
    return {'edition':edition,'test_apk':str(dest),'test_sha256':sha(dest),
            'source_apk_sha256':sha(apk),'test_package':TESTPKG,'instrumentation_target':PKG,
            'signer_sha256':cert,'locally_signed':True,'signature_verified':True,
            'source_sha':SHA,'qualified_release':False}

def selftest():
    assert parse_cert('Verifies\nNumber of signers: 1\nV3.0 Signer: certificate SHA-256 digest: '+SIGNER+'\n')==SIGNER
    assert parse_cert('Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: '+SIGNER+'\n')==SIGNER
    for bad in ['Verifies\nNumber of signers: 2\nV3.0 Signer: certificate SHA-256 digest: '+SIGNER,
                'Verifies\nNumber of signers: 1\nV3.0 Signer: certificate SHA-256 digest: '+'0'*64+'\nV2 Signer: certificate SHA-256 digest: '+SIGNER,
                'not verified']:
        try:parse_cert(bad)
        except Rejected:continue
        raise AssertionError('Malformed signing output accepted')
    parse_badging("package: name='"+TESTPKG+"' versionCode='1000000'\ninstrumentation: name='"+RUNNER+"' targetPackage='"+PKG+"'\n")
    for bad in ["package: name='com.other.test'\n", "package: name='"+TESTPKG+"'\ninstrumentation: name='wrong' targetPackage='"+PKG+"'"]:
        try:parse_badging(bad)
        except Rejected:continue
        raise AssertionError('Wrong instrumentation identity accepted')
    with tempfile.TemporaryDirectory(prefix='bkx-rel07-selftest-') as temp:
        base=Path(temp)/'app/build/outputs/apk/androidTest/embedded/release'
        base.mkdir(parents=True)
        candidate=base/'app-embedded-release-androidTest.apk'
        candidate.write_bytes(b'fixture')
        data={'variantName':'embeddedReleaseAndroidTest','applicationId':TESTPKG,
              'elements':[{'outputFile':candidate.name}]}
        (base/'output-metadata.json').write_text(json.dumps(data))
        assert metadata_candidate(Path(temp),'embedded')==candidate
        data['applicationId']='wrong.package'
        (base/'output-metadata.json').write_text(json.dumps(data))
        try:metadata_candidate(Path(temp),'embedded')
        except Rejected:pass
        else:raise AssertionError('Wrong package accepted')
    print('PASS_BKX_REL07_LIFE_INSTRUMENTATION_HELPER_SELFTEST')

def main():
    global SHA, SIGNER
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--execute',action='store_true',help='Explicit consent for local Gradle build + LIFE signing of TEST APKs only')
    p.add_argument('--editions',choices=('embedded','lite','both'),default='both')
    p.add_argument('--self-test',action='store_true')
    args=p.parse_args()
    if args.self_test:
        # Self-tests use deterministic fixture material, never access a signer.
        SHA = 'a'*40
        SIGNER = 'b'*64
        selftest()
        return 0
    root=Path(__file__).resolve().parents[1]
    from importlib.util import spec_from_file_location, module_from_spec
    import_doc = json.loads((Path.home()/'bkx-rm16-import.json').read_text())
    sign_doc = json.loads((Path.home()/'bkx-rm16-sign.json').read_text())
    spec = spec_from_file_location('bkx_rel06_audit', root/'scripts/bkx-audit-rm16-receipts.py')
    audit = module_from_spec(spec)
    spec.loader.exec_module(audit)
    result = audit.audit(import_doc,sign_doc)
    SHA = result['source_sha']
    SIGNER = result['signer_sha256']
    require(SHA and SIGNER and len(SHA)==40 and len(SIGNER)==64,'REL06 source and signer authority absent')
    source_check(root)
    editions=list(EDITION if args.editions=='both' else [args.editions])
    if not args.execute:
        print(json.dumps({'operation':'test-apk-build-and-life-sign-plan','source_sha':SHA,
              'editions':editions,'existing_LIFE_signer_sha256':SIGNER,'release_apk_modification':False,
              'device_modification':False,'ready':True},indent=2));return 0
    out=Path.home()/'bkx-rel07-test-artifacts'
    require(not out.is_symlink(), 'Unsafe test output directory')
    out.mkdir(mode=0o700,exist_ok=True)
    require(out.stat().st_mode & 0o077 == 0, 'Test artifact directory must be private (0700)')
    for edition in editions:
        dest=out/f'bkx-{edition}Release-androidTest-life-{SHA[:12]}.apk'
        require(not dest.exists() and not dest.is_symlink(), 'Test artifact already exists; refuse overwrite before Gradle')
    log=out/('gradle-androidTest-'+os.urandom(5).hex()+'.log')
    candidates=build(root,editions,log)
    results=[]
    for edition,candidate in candidates.items():
        parse_badging(command(['aapt','dump','badging',str(candidate)]))
        dest=out/f'bkx-{edition}Release-androidTest-life-{SHA[:12]}.apk'
        print('Signing '+edition+' test APK with existing LIFE identity (no release APK modification).',flush=True)
        results.append(sign_test(root,candidate,edition,dest))
    source_check(root)
    evidence={'schema':'bkx-rel07-local-matching-instrumentation-v1',
              'source_sha':SHA,'github_run_id':40045942016,
              'release_application_package':PKG,'test_package':TESTPKG,'test_runner':RUNNER,
              'gradle_build_log':str(log),'source_clean_after_build':True,'results':results,
              'device_installation':'NOT_RUN','instrumentation':'NOT_RUN','qualified_release':False}
    p=out/('life-androidTest-'+SHA[:12]+'.json')
    require(not p.exists(),'Refusing overwrite of test signing evidence')
    with p.open('x') as f:
        json.dump(evidence,f,indent=2,sort_keys=True);f.write('\n')
    p.chmod(0o600)
    print(json.dumps({'status':'SIGNED_TEST_APKS_READY','evidence':str(p),
                      'signed_test_apks':[{k:r[k] for k in ('edition','test_apk','test_sha256','signer_sha256')} for r in results],
                      'release_apks_untouched':True,'device_untouched':True,'qualified_release':False},indent=2))
    return 0

if __name__=='__main__':
    try:sys.exit(main())
    except Rejected as exc:
        # Rejected messages are fixed, non-secret diagnostics authored above.
        print('BKX_REL07_TEST_APK_BLOCKED: '+str(exc),file=sys.stderr)
        sys.exit(2)
    except Exception as exc:
        # Never print provider exception strings; they could contain secret material.
        print('BKX_REL07_TEST_APK_BLOCKED: '+type(exc).__name__+
              '; check source, prerequisites or private local Gradle log',file=sys.stderr)
        sys.exit(2)
