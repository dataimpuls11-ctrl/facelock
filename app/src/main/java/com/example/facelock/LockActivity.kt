package com.example.facelock

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Full-screen face lock shown on top of a protected app.
 * No PIN / pattern / fingerprint / BiometricPrompt - the only way through is face + liveness.
 */
class LockActivity : ComponentActivity() {
    private var target by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        target = intent.getStringExtra(LockState.EXTRA_PKG) ?: packageName

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LockScreen(target = target, onSuccess = ::unlock, onCancel = ::goHome)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        target = intent.getStringExtra(LockState.EXTRA_PKG) ?: packageName
    }

    private fun unlock() {
        if (target == packageName) LockState.grantSelf() else LockState.unlockedPkg = target
        finish()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

@Composable
private fun LockScreen(target: String, onSuccess: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(Permissions.camera(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    val label = remember(target) {
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(target, 0)).toString() }
            .getOrDefault(target)
    }
    // Fresh session per target so a previous attempt never carries over.
    val session = remember(target) { VerificationSession(SecureStore.loadTemplates(ctx)) }
    LaunchedEffect(session.success) { if (session.success) onSuccess() }

    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Unlock $label", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        key(session) {
            if (granted) FaceCircle { face, emb -> session.onFace(face, emb) }
            else Text("Camera permission is required to unlock.", textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        Text(session.prompt, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}
