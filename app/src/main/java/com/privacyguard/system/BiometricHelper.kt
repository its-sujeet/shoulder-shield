package com.privacyguard.system

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executors

/**
 * Helper for Biometric (fingerprint / device credential) authentication.
 * Wraps the AndroidX Biometric library so callers need only a single function call.
 */
class BiometricHelper {

    companion object {

        /**
         * Quick capability check — does this device support BIOMETRIC_STRONG?
         * Returns true if [BiometricManager.BIOMETRIC_SUCCESS].
         */
        fun canAuthenticate(context: Context): Boolean {
            return try {
                BiometricManager.from(context)
                    .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Launch the system biometric prompt.
         *
         * @param activity   The current [FragmentActivity] used to show the prompt.
         * @param title      Prompt title (shown in the system dialog).
         * @param subtitle   Prompt subtitle (shown in the system dialog).
         * @param onSuccess  Called when authentication succeeds.
         * @param onError    Called on a non-recoverable error — receives (errorCode, errString).
         * @param onFailed   Called when the user fails to authenticate (recoverable).
         */
        @JvmStatic
        fun authenticate(
            activity: FragmentActivity,
            title: String,
            subtitle: String,
            onSuccess: () -> Unit,
            onError: (Int, String) -> Unit,
            onFailed: () -> Unit
        ) {
            val executor = Executors.newSingleThreadExecutor()

            val callback = object : BiometricPrompt.AuthenticationCallback() {

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errorCode, errString.toString())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onFailed()
                }
            }

            val prompt = BiometricPrompt(activity, executor, callback)

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                            or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()

            prompt.authenticate(promptInfo)
        }
    }
}
