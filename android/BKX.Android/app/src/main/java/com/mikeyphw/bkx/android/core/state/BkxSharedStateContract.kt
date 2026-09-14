package com.mikeyphw.bkx.android.core.state

/**
 * Non-secret state shared with the on-device CLI.
 *
 * Credentials, Google OAuth tokens, Restic passwords, and the app's rclone
 * config do not live here. This directory contains only saved-job, named repository, schedule, and
 * execution-control documents plus the marker used by the Termux CLI resolver.
 */
object BkxSharedStateContract {
    const val ROOT = "/storage/emulated/0/BKX/.state"
    const val MARKER = ".bkx-state.json"
    const val ENVIRONMENT = "BKX_STATE_DIR"
    const val MARKER_KIND = "bkx-shared-control-plane"
    const val SCHEMA_VERSION = 2
    const val MIN_SCHEMA_VERSION = 1
}

