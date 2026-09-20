package com.mikeyphw.bkx.android.root;

import android.os.ParcelFileDescriptor;

/** Narrow JSON-only privileged transport. No arbitrary command execution is exposed. */
interface IBkxRootEngineService {
    int uid();
    String readinessJson();
    String chrootctlReadinessJson();
    String chrootctlQueryJson(String requestJson);
    String chrootctlPreviewJson(String requestJson);
    String chrootctlStartOperation(String requestJson, in ParcelFileDescriptor output);
    String chrootctlCopyManagedExportArtifact(String copyId, String path, in ParcelFileDescriptor output);
    String chrootctlOperationJson(String operationId);
    String chrootctlCancelOperationJson(String operationId);
    String chrootctlReconcileOperationJson(String operationId);
    String executeJson(String requestJson);
    String progressJson(String requestId);
    String cancelJson(String requestId);
}
