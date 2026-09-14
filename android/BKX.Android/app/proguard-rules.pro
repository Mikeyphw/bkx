# BKX release shrink/minify contract.
# Keep the Tasker/Locale plugin entry points and libsu service types discoverable
# while allowing R8 to shrink the rest of the Compose app in release builds.
-keep class com.mikeyphw.bkx.android.automation.BkxAutomationReceiver { *; }
-keep class com.mikeyphw.bkx.android.automation.BkxTaskerEditActivity { *; }
-keep class com.mikeyphw.bkx.android.automation.BkxTaskerEventEditActivity { *; }
-keep class com.mikeyphw.bkx.android.automation.BkxTaskerStateEditActivity { *; }
-keep class com.mikeyphw.bkx.android.automation.BkxAutomationService { *; }
-keep class com.mikeyphw.bkx.android.automation.BkxAutomationJobService { *; }
-keep class com.mikeyphw.bkx.android.automation.BkxSavedJobScheduleService { *; }
-keep class com.mikeyphw.bkx.android.root.BkxRootEngineService { *; }
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**
