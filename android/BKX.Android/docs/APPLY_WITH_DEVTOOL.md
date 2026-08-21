# Apply BKX Android AOV-25→32

This is the final release-seal overlay, so do **not** use `--no-validate`.

```bash
devtool --yes --copy \
  -r ~/Code/bkx \
  --target bkx_android \
  apply overlay \
  ~/Downloads/bkx_android_aov25_aov32_final_release_seal_v5.zip
```

If a device/root grant is not available during the normal validator, the explicit root qualification test remains opt-in. After the APK and test APK are installed and BKX is authorized in the root manager, run `android/BKX.Android/scripts/qualify-device.sh`.
