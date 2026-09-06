package dev.andrii.headroom

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.andrii.headroom.credential.CredentialStore
import dev.andrii.headroom.domain.CredentialImport
import dev.andrii.headroom.domain.PayloadException
import java.io.File
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Links the app from a payload file, for verification runs.
 *
 * Debug source set only. It exists because the supported path is scanning a
 * QR with a camera, which an emulator does not have — and because passing a
 * live credential as a shell argument would write it into command history and
 * logs. Reading it from a file keeps it out of both.
 *
 * adb push payload.txt /sdcard/headroom_payload.txt
 * adb shell am start -n dev.andrii.headroom/.DebugLinkActivity
 */
class DebugLinkActivity : ComponentActivity() {

    private val credentialStore: CredentialStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path") ?: "/sdcard/headroom_payload.txt"

        lifecycleScope.launch {
            val result = runCatching {
                val text = File(path).readText().trim()
                credentialStore.save(CredentialImport.decode(text))
                "linked"
            }.getOrElse { error ->
                // Never echo the payload: only what went wrong with it.
                when (error) {
                    is PayloadException -> "payload rejected: ${error.message}"
                    else -> "could not read $path: ${error::class.simpleName}"
                }
            }
            Toast.makeText(this@DebugLinkActivity, result, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
