package com.openmine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Test APK only, protected by DUMP. Run cleanup as the URI owner, never as the grantee. */
public final class FixtureGrantResetReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        for (String name : new String[]{TestDocumentsProvider.RECORD_NAME,
                TestDocumentsProvider.PROJECT_NAME, TestDocumentsProvider.INVALID_NAME}) {
            context.revokeUriPermission(TestDocumentsProvider.uri(name),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }
        setResultCode(37);
    }
}
