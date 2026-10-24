#!/usr/bin/env python3
"""REL09 adversarial source integration regression: no signed APK/device claims."""
import ast
from pathlib import Path
import importlib.util
import unittest

ROOT = Path(__file__).resolve().parents[1]
GO = ROOT/'android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt'
BUILDER = ROOT/'scripts/bkx-rel07-build-matching-instrumentation.py'
SIGNER = ROOT/'scripts/bkx_apksigner_certificate.py'
SCRIPT = ROOT/'scripts/bkx-rel09-device-runtime-probe.sh'

class TestRuntimeAndDeviceContracts(unittest.TestCase):
    def test_native_extraction_preserved(self):
        text=(ROOT/'android/BKX.Android/app/build.gradle.kts').read_text()
        self.assertIn('useLegacyPackaging = true',text)
        self.assertIn('flavorDimensions += "edition"',text)
    def test_process_launcher_no_shell_and_trusted_fallback(self):
        text=GO.read_text()
        self.assertIn('launchPackagedGoRuntime(builder, home)',text)
        self.assertIn('private fun launchPackagedGoRuntime(',text)
        self.assertIn('File("/system/bin/linker64")',text)
        self.assertIn('!home.isDirectory',text)
        self.assertIn('!binary.canExecute()',text)
        self.assertIn('linkerError.addSuppressed(directError)',text)
        self.assertIn('listOf(linker.absolutePath) + primary.command()',text)
        self.assertNotIn('ProcessBuilder("/system/bin/sh"',text)
        self.assertNotIn('ProcessBuilder("su"',text)
        self.assertNotIn('Runtime.getRuntime().exec',text)
    def test_go_pie_verifier_wired_at_build_boundary(self):
        text=(ROOT/'scripts/build-go-android-runtime.sh').read_text()
        self.assertIn('CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build',text)
        self.assertIn('-buildmode=pie',text)
        self.assertIn('verify-bkx-go-android-executable.py',text)
        self.assertIn('chmod 0755 "$tmp"',text)
    def test_local_life_instrumentation_no_hardcoded_release_identity(self):
        text=BUILDER.read_text()
        ast.parse(text)
        self.assertIn("SHA = audited_identity['source_sha']",text)
        self.assertIn("SIGNER = result['signer_sha256']",text)
        self.assertIn("audit.audit(import_doc,sign_doc)",text)
        self.assertIn("'--no-configuration-cache'",text)
        self.assertIn("'testBuildType = 'release'",text) if False else None
        self.assertIn("testBuildType = 'release'",text)
        self.assertIn('sign_test(root,candidate,edition,dest)',text)
        self.assertIn('source_check(root)',text)
        self.assertNotIn('adb install',text)
    def test_instrumentation_provenance_tracks_audited_new_run(self):
        import importlib.util
        spec=importlib.util.spec_from_file_location('bkx_test_builder_under_test',BUILDER)
        builder=importlib.util.module_from_spec(spec)
        spec.loader.exec_module(builder)
        for run,attempt in [(40114502372,1),(40123456789,2)]:
            result=builder.audited_release_provenance({
                'source_sha':'a'*40,'run_id':run,'run_attempt':attempt,
                'receipts_pass':True,'qualified_release':False})
            self.assertEqual(result['github_run_id'],run)
            self.assertEqual(result['github_run_attempt'],attempt)
            self.assertEqual(result['source_sha'],'a'*40)
        for bad in [
                {'source_sha':'a'*40,'run_id':False,'run_attempt':1,'receipts_pass':True,'qualified_release':False},
                {'source_sha':'a'*40,'run_id':0,'run_attempt':1,'receipts_pass':True,'qualified_release':False},
                {'source_sha':'a'*40,'run_id':1,'run_attempt':0,'receipts_pass':True,'qualified_release':False},
                {'source_sha':'a'*40,'run_id':1,'run_attempt':1,'receipts_pass':False,'qualified_release':False},
                {'source_sha':'a'*40,'run_id':1,'run_attempt':1,'receipts_pass':True,'qualified_release':True},
                {'source_sha':'bad','run_id':1,'run_attempt':1,'receipts_pass':True,'qualified_release':False},
        ]:
            with self.assertRaises(builder.Rejected):
                builder.audited_release_provenance(bad)
        self.assertNotIn("'github_run_id':40045942016",BUILDER.read_text())

    def test_termux_and_sdk_signer_path_supported(self):
        text=(ROOT/'android/BKX.Android/scripts/qualify-device.sh').read_text()
        self.assertIn('bkx_apksigner_certificate.py',text)
        self.assertIn('--apksigner "$APKSIGNER"',text)
        self.assertNotIn("sed -n 's/^Signer #1",text)
        self.assertIn('V[1-4]',SIGNER.read_text())
    def test_readonly_probe_never_installs_or_mutates(self):
        text=SCRIPT.read_text()
        for bad in ('install -r','pm clear','uninstall','chmod ','rm -','su -c "cat >'):
            self.assertNotIn(bad,text)
        self.assertIn('adb -s "$serial" shell su -c id',text)
        self.assertIn('^/data/app/',text)
    def test_instrumentation_requires_real_go_launch(self):
        text=(ROOT/'android/BKX.Android/app/src/androidTest/java/com/mikeyphw/bkx/android/embedded/GoNativeRuntimeInstrumentationTest.kt').read_text()
        self.assertIn('extractedGo.isFile',text)
        self.assertIn('extractedGo.canExecute()',text)
        self.assertIn('gateway.readinessJson()',text)

if __name__ == '__main__':unittest.main(verbosity=2)
