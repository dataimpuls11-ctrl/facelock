package com.example.facelock

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Captures the owner's face and stores the geometry templates in encrypted storage. */
class EnrollmentActivity : ComponentActivity() {
    private var allowed = false

    override fun onDestroy() {
        super.onDestroy()
        // Returning to the dashboard should not ask for the password again right after adding a face.
        if (allowed && !isChangingConfigurations) LockState.grantAdmin()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // Adding a face requires the app password.
        if (!LockState.adminUnlocked()) { finish(); return }
        allowed = true

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var granted by remember { mutableStateOf(Permissions.camera(this)) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
                LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

                val session = remember { EnrollmentSession() }
                LaunchedEffect(session.done) {
                    if (session.done) {
                        SecureStore.addTemplates(this@EnrollmentActivity, session.samples.toList())
                        Toast.makeText(this@EnrollmentActivity, "Face enrolled", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }

                Column(
                    Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Add a face", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(24.dp))
                    if (granted) FaceCircle { face, emb -> session.onFace(face, emb) }
                    else Text("Camera permission is required.", textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Text(session.prompt, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { session.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = { finish() }) { Text("Cancel") }
                }
            }
        }
    }
}
