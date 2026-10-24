#!/usr/bin/env python3
"""REL05-HF04 cross-layer edition-binding checks, independent of Gradle/GitHub."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[1]

class EditionBindings(unittest.TestCase):
    def test_gradle_passes_variant_to_go_cli(self):
        gradle=(ROOT/'android/BKX.Android/app/build.gradle.kts').read_text()
        chunk=gradle.split('abstract class VerifyBkxApkNativePageSizeTask',1)[1].split('abstract class BkxSigningStatusTask',1)[0]
        self.assertIn('"--edition", expectedVariant.get().removeSuffix("Release")',chunk)
        self.assertIn('BkxReleaseApkOutputs.locate(',chunk)
        self.assertIn('"--min-page-size", minimumPageSize.get().toString()',chunk)
        self.assertIn('expectedVariant.set("embeddedRelease")',gradle)
        self.assertIn('expectedVariant.set("liteRelease")',gradle)
    def test_producer_passes_independent_edition(self):
        producer=(ROOT/'scripts/produce-bkx-unsigned-candidates.py').read_text()
        self.assertIn("edition=consumer['variant'].removesuffix('Release').lower()",producer)
        self.assertIn("'apk','--edition',edition,'--min-page-size'",producer)
        self.assertIn("if edition not in ('embedded','lite')",producer)
    def test_go_requires_explicit_edition_and_protects_lite(self):
        src=(ROOT/'scripts/verify-native-page-size.go').read_text()
        self.assertIn('fs.String("edition", "",',src)
        self.assertIn('(*edition != "embedded" && *edition != "lite")',src)
        self.assertIn('if *edition == "embedded" {',src)
        self.assertIn('Lite must not package rclone',src)
        self.assertIn('required BKX Go runtime is missing',src)
        self.assertIn('duplicate ZIP member',src)

if __name__=='__main__':unittest.main()
