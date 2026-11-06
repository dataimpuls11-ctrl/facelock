package com.example.facelock

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { AppRoot(tick) } } }
    }

    override fun onResume() {
        super.onResume()
        tick++
        if (Permissions.usage(this) && SecureStore.hasFace(this)) WatchdogService.start(this)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app closes the admin session: the password is asked again next time.
        LockState.revokeAdmin()
    }
}

private data class AppItem(val pkg: String, val label: String, val icon: ImageBitmap)
private data class SetupItem(val title: String, val ok: Boolean, val action: () -> Unit)

@Composable
private fun AppRoot(tick: Int) {
    val ctx = LocalContext.current
    val hasPw = remember(tick) { SecureStore.hasPassword(ctx) }
    var unlocked by remember(tick) { mutableStateOf(hasPw && LockState.adminUnlocked()) }

    // First launch: choose the app password. Every later launch: enter it.
    if (!unlocked) {
        PasswordScreen(setup = !hasPw) { LockState.grantAdmin(); unlocked = true }
        return
    }
    Dashboard(tick)
}

@Composable
private fun PasswordScreen(setup: Boolean, onSuccess: () -> Unit) {
    val ctx = LocalContext.current
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var fails by remember { mutableIntStateOf(0) }
    var lockedUntil by remember { mutableLongStateOf(0L) }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (setup) "Set app password" else "Enter app password", style = MaterialTheme.typography.headlineSmall)
        if (setup) Text("This password is needed to open FaceLock and to add new faces.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        if (setup) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(pw2, { pw2 = it }, Modifier.fillMaxWidth(), label = { Text("Confirm password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        }
        error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            val now = SystemClock.elapsedRealtime()
            when {
                now < lockedUntil -> error = "Too many attempts. Wait ${(lockedUntil - now) / 1000 + 1}s"
                setup && pw.length < 6 -> error = "Use at least 6 characters"
                setup && pw != pw2 -> error = "Passwords do not match"
                setup -> { SecureStore.setPassword(ctx, pw.toCharArray()); onSuccess() }
                SecureStore.verifyPassword(ctx, pw.toCharArray()) -> onSuccess()
                else -> {
                    error = "Wrong password"
                    if (++fails >= 5) { fails = 0; lockedUntil = now + 30_000 }
                }
            }
        }) { Text(if (setup) "Save password" else "Unlock") }
    }
}

@Composable
private fun Dashboard(tick: Int) {
    val ctx = LocalContext.current
    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val setup = remember(tick) {
        listOf(
            SetupItem("Camera permission", Permissions.camera(ctx)) { camLauncher.launch(Manifest.permission.CAMERA) },
            SetupItem("Display over other apps", Permissions.overlay(ctx)) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            },
            SetupItem("Usage access", Permissions.usage(ctx)) { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
            SetupItem("Accessibility service", Permissions.accessibility(ctx)) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            SetupItem("Device admin (anti-uninstall)", Permissions.admin(ctx)) {
                ctx.startActivity(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, AdminHelper.component(ctx))
                        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Prevents FaceLock from being uninstalled without authorization.")
                )
            }
        )
    }
    var enrolled by remember(tick) { mutableStateOf(SecureStore.hasFace(ctx)) }

    val protectedSet = remember { mutableStateListOf<String>().apply { addAll(SecureStore.getProtected(ctx)) } }
    val apps by produceState(emptyList<AppItem>()) {
        value = withContext(Dispatchers.Default) {
            val pm = ctx.packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(q, 0)
                .filter { it.activityInfo.packageName != ctx.packageName }
                .distinctBy { it.activityInfo.packageName }
                .map { AppItem(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm).toBitmap(96, 96).asImageBitmap()) }
                .sortedBy { it.label.lowercase() }
        }
    }
    var query by remember { mutableStateOf("") }
    val shown = remember(apps, query) { apps.filter { it.label.contains(query, true) } }

    LazyColumn(Modifier.fillMaxSize().systemBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("FaceLock", style = MaterialTheme.typography.headlineMedium) }
        items(setup) { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text((if (s.ok) "✓  " else "✗  ") + s.title, Modifier.weight(1f))
                if (!s.ok) Button(onClick = s.action) { Text("Grant") }
            }
        }
        item {
            Button(onClick = { ctx.startActivity(Intent(ctx, EnrollmentActivity::class.java)) }, Modifier.fillMaxWidth()) {
                Text(if (enrolled) "Add another face" else "Add face (required)")
            }
        }
        if (enrolled) item {
            OutlinedButton(onClick = { SecureStore.clearFaces(ctx); enrolled = false }, Modifier.fillMaxWidth()) { Text("Remove all faces") }
        } else item { Text("Apps are not locked until a face is added.", color = MaterialTheme.colorScheme.error) }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search apps") }, singleLine = true) }
        items(shown, key = { it.pkg }) { app ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Image(app.icon, null, Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                    Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = app.pkg in protectedSet, onCheckedChange = { on ->
                    SecureStore.setProtected(ctx, app.pkg, on)
                    if (on) protectedSet.add(app.pkg) else protectedSet.remove(app.pkg)
                })
            }
        }
    }
}
