package com.minhphan.launcher.ui

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.minhphan.launcher.R

/** [otherHomePackage] is the Home app that currently wins when Home is pressed, if it isn't us. */
data class HomeStatus(val isDefault: Boolean, val otherHomePackage: String?)

fun queryHomeStatus(context: Context): HomeStatus {
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val pkg = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName
    return HomeStatus(
        isDefault = pkg == context.packageName,
        // "android" is the system chooser, i.e. no default has been picked.
        otherHomePackage = pkg?.takeIf { it != context.packageName && it != "android" },
    )
}

/** Re-checks every time the screen comes back, so it flips as soon as the user changes the setting. */
@Composable
fun rememberHomeStatus(): State<HomeStatus> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(queryHomeStatus(context)) }
    LifecycleResumeEffect(Unit) {
        state.value = queryHomeStatus(context)
        onPauseOrDispose { }
    }
    return state
}

/**
 * Returns a function that asks the system to make this app the Home app: first the Android 10+ role
 * dialog; if that is missing or the system closes it straight away (some head-unit firmware does), the
 * Home / default-apps settings pages instead.
 */
@Composable
fun rememberRequestDefaultHome(): () -> Unit {
    val context = LocalContext.current
    var startedAt by remember { mutableLongStateOf(0L) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val closedImmediately = SystemClock.elapsedRealtime() - startedAt < ROLE_DIALOG_MIN_MS
        if (result.resultCode != Activity.RESULT_OK && closedImmediately) openHomeSettings(context)
    }
    return {
        startedAt = SystemClock.elapsedRealtime()
        val roleIntent = roleRequestIntent(context)
        val started = roleIntent != null && try {
            launcher.launch(roleIntent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
        if (!started) openHomeSettings(context)
    }
}

private const val ROLE_DIALOG_MIN_MS = 1_000L

private fun roleRequestIntent(context: Context): Intent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val roles = context.getSystemService(RoleManager::class.java) ?: return null
    return if (roles.isRoleAvailable(RoleManager.ROLE_HOME)) roles.createRequestRoleIntent(RoleManager.ROLE_HOME) else null
}

private fun openHomeSettings(context: Context) {
    val opened = startFirstAvailable(
        context,
        Intent(Settings.ACTION_HOME_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    if (!opened) Toast.makeText(context, R.string.set_default_manual, Toast.LENGTH_LONG).show()
}

/** Opens the system "App info" page of the current launcher, which has Open by default -> Clear defaults. */
fun openAppDetails(context: Context, packageName: String) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    if (!startFirstAvailable(context, intent)) {
        Toast.makeText(context, R.string.set_default_manual, Toast.LENGTH_LONG).show()
    }
}

private fun startFirstAvailable(context: Context, vararg intents: Intent): Boolean {
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Try the next fallback.
        } catch (_: SecurityException) {
            // Vendor firmware may restrict the page; try the next fallback.
        }
    }
    return false
}

@Composable
fun DefaultHomeBanner(
    otherHomePackage: String?,
    onSetDefault: () -> Unit,
    onOpenCurrentLauncher: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.not_default_home),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Button(onClick = onSetDefault, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.set_as_default), style = MaterialTheme.typography.titleMedium)
        }
        if (otherHomePackage != null) {
            OutlinedButton(
                onClick = { onOpenCurrentLauncher(otherHomePackage) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(stringResource(R.string.open_current_launcher), style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(
            text = stringResource(R.string.default_home_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Text(
            text = stringResource(R.string.home_debug_info, Build.VERSION.SDK_INT, otherHomePackage ?: "-"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}
