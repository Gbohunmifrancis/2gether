package app.heartpact.ui.auth

import android.app.Activity
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.CreateCredentialException
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * After signing up or in with a password, the phone's password manager offers to keep it (Credential Manager: Google
 * Password Manager, or whichever provider the person chose). Launched on the activity, so it outlives the sign-in
 * screen as the app moves on; declining or having no provider is silent.
 */
object PasswordSaving {
    fun offer(activity: Activity?, email: String, password: String) {
        val owner = activity as? ComponentActivity ?: return
        if (email.isBlank() || password.isEmpty()) return
        owner.lifecycleScope.launch {
            try {
                CredentialManager.create(owner).createCredential(owner, CreatePasswordRequest(email.trim(), password))
            } catch (e: CreateCredentialException) {
                Log.i("PasswordSaving", "Not saved: ${e.type}")
            } catch (e: IllegalArgumentException) {
                Log.i("PasswordSaving", "Not saved: ${e.message}")
            }
        }
    }
}
