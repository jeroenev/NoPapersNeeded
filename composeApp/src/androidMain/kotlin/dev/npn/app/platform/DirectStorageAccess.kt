package dev.npn.app.platform

import android.os.Environment

/** True when the app may read and write any folder on shared storage (Android's all-files grant). */
fun hasDirectStorageAccess(): Boolean = Environment.isExternalStorageManager()
