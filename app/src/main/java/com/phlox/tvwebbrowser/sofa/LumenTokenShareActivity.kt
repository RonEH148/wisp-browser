package com.phlox.tvwebbrowser.sofa

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.phlox.tvwebbrowser.AppContext
import java.security.MessageDigest

/**
 * Hands the GitHub update settings (repository + access token) to LeanKeyboard Plus, so its own
 * "Check for updates" works without typing the token again. Only answers LeanKeyboard Plus signed
 * with its own key, and only after the user agrees.
 */
class LumenTokenShareActivity : Activity() {
    companion object {
        const val EXTRA_REPO = "repo"
        const val EXTRA_TOKEN = "token"
        private const val KEYBOARD_PACKAGE = "com.liskovsoft.leankeyboard.plus"
        /** SHA-256 of the LeanKey Plus signing certificate (lkplus.keystore, CN=LeanKey Plus). */
        private const val KEYBOARD_CERT_SHA256 =
            "13C19FE248DF972BC3A04FFF207737EA5C461B1D83FC60BA82E207A7121CB051"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callingPackage
        if (caller != KEYBOARD_PACKAGE || !signedByKeyboardKey(caller)) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val config = AppContext.provideConfig()
        val repo = config.lumenUpdateRepo
        AlertDialog.Builder(this)
            .setTitle("Share update access with LeanKeyboard Plus?")
            .setMessage("LeanKeyboard Plus asks for Lumen's update source, so it can update itself from the same " +
                    "GitHub repository:\n\n$repo" + (if (LumenUpdater.hasToken()) "\n(with the access token)" else ""))
            .setPositiveButton("Share") { _, _ ->
                val data = Intent().putExtra(EXTRA_REPO, repo)
                LumenUpdater.tokenForSharing()?.let { data.putExtra(EXTRA_TOKEN, it) }
                setResult(RESULT_OK, data)
                finish()
            }
            .setNegativeButton("Don't share") { _, _ ->
                setResult(RESULT_CANCELED)
                finish()
            }
            .setOnCancelListener {
                setResult(RESULT_CANCELED)
                finish()
            }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun signedByKeyboardKey(pkg: String): Boolean = try {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        sigs != null && sigs.isNotEmpty() && sigs.all { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                .joinToString("") { "%02X".format(it) } == KEYBOARD_CERT_SHA256
        }
    } catch (e: Exception) {
        false
    }
}
