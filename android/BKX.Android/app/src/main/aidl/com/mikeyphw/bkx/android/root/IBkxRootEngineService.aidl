package com.mikeyphw.bkx.android.root;

/** Narrow JSON-only privileged transport. No arbitrary command execution is exposed. */
interface IBkxRootEngineService {
    int uid();
    String readinessJson();
    String executeJson(String requestJson);
    String cancelJson(String requestId);
}
