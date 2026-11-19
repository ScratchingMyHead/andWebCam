package net.andwebcam

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Invisible. Its only job is to stand in for the service, which cannot start
 * an activity for a result itself, and to keep whatever the system folder
 * picker returns. It has no visible window of its own.
 */
class FolderPickActivity : Activity() {
    private companion object {
        const val REQ_PICK = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivityForResult(LogFolder.pickIntent(), REQ_PICK)
        } catch (e: Exception) {
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK) {
            if (resultCode == RESULT_OK) {
                data?.data?.let { LogFolder.onPick(this, it) }
            }
            finish()
        }
    }
}
