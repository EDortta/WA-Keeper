package br.com.wanotifkeeper

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DriveAuth {
    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    fun client(context: Context): GoogleSignInClient =
        GoogleSignIn.getClient(
            context,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(DRIVE_SCOPE))
                .build()
        )

    fun account(context: Context): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        return account.takeIf { GoogleSignIn.hasPermissions(it, Scope(DRIVE_SCOPE)) }
    }

    suspend fun accessToken(context: Context): String = withContext(Dispatchers.IO) {
        val account = account(context) ?: error("Google Drive não conectado")
        val androidAccount = account.account ?: error("Conta Google sem credencial Android")
        GoogleAuthUtil.getToken(context, androidAccount, "oauth2:$DRIVE_SCOPE")
    }

    suspend fun invalidateToken(context: Context, token: String) = withContext(Dispatchers.IO) {
        GoogleAuthUtil.clearToken(context, token)
    }
}
