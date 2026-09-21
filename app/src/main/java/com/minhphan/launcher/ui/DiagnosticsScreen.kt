package com.minhphan.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.minhphan.launcher.R
import com.minhphan.launcher.diagnostics.DiagnosticLine
import com.minhphan.launcher.diagnostics.collectDiagnostics

/** Full-screen report of what the firmware supports, plus a step-by-step network test. */
@Composable
fun DiagnosticsScreen(
    connectivity: List<DiagnosticLine>,
    connectivityRunning: Boolean,
    onTestConnectivity: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // Recompute whenever we come back to the screen (e.g. after changing the clock in Settings).
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val lines = remember(refresh) { collectDiagnostics(context) }

    OverlayScreen(title = stringResource(R.string.diagnostics_title), onClose = onClose) {
        Report(lines + connectivity)
        Button(
            onClick = onTestConnectivity,
            enabled = !connectivityRunning,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text(
                stringResource(if (connectivityRunning) R.string.testing_connectivity else R.string.test_connectivity),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** The report as one card of label / value rows with thin dividers. */
@Composable
private fun ColumnScope.Report(lines: List<DiagnosticLine>) {
    val shape = RoundedCornerShape(24.dp)
    LazyColumn(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        itemsIndexed(lines) { index, line ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(
                    line.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.5f),
                )
                Text(
                    line.value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(0.5f),
                )
            }
        }
    }
}
