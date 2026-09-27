package com.nethra.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nethra.app.ui.theme.NethraColors

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/** Permission status re-read whenever the screen resumes (the user may have changed it in Settings). */
class PermissionsState(
    val granted: Map<String, Boolean>,
    val askedOnce: Boolean,
    val request: () -> Unit
) {
    fun isGranted(p: String) = granted[p] == true
    val allGranted get() = granted.values.all { it }
}

@Composable
fun rememberPermissions(permissions: List<String>, onResult: (Map<String, Boolean>) -> Unit = {}): PermissionsState {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var tick by remember { mutableIntStateOf(0) }
    var asked by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        asked = true
        tick++
        onResult(result)
    }
    val granted = remember(tick, permissions) { permissions.associateWith { context.hasPermission(it) } }
    return PermissionsState(granted, asked) {
        launcher.launch(permissions.filterNot { context.hasPermission(it) }.toTypedArray())
    }
}

/**
 * Explains why a permission is needed before asking, and offers Settings when
 * Android will no longer show the dialog (the user chose "Don't allow" twice).
 */
@Composable
fun PermissionExplainer(
    title: String,
    reason: String,
    state: PermissionsState,
    required: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val blocked = state.askedOnce && !state.isGranted(required) &&
        activity?.shouldShowRequestPermissionRationale(required) == false

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        GlassCard(strong = true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.Icon(Icons.Filled.Lock, null, tint = NethraColors.Accent)
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
            Spacer(Modifier.height(10.dp))
            Text(reason, style = MaterialTheme.typography.bodyMedium, color = NethraColors.TextDim)
            if (blocked) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Android won't ask again. Turn the permission on in Settings → Permissions, then come back.",
                    style = MaterialTheme.typography.bodyMedium, color = NethraColors.Warn
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Back", onBack, icon = Icons.AutoMirrored.Filled.ArrowBack)
                if (blocked) GlassButton("Open Settings", { context.openAppSettings() }, icon = Icons.Filled.Settings, accent = true)
                else GlassButton("Allow", state.request, accent = true)
            }
        }
    }
}
