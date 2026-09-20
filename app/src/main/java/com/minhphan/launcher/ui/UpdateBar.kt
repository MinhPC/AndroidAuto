package com.minhphan.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minhphan.launcher.R
import com.minhphan.launcher.update.UpdateInfo
import com.minhphan.launcher.update.UpdateState

/** One row under the clock: the installed version on the left, update status / action on the right. */
@Composable
fun UpdateBar(
    versionName: String,
    state: UpdateState,
    onCheck: () -> Unit,
    onInstall: (UpdateInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
    val minHeight = Modifier.heightIn(min = 56.dp)

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.app_version, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            when (state) {
                UpdateState.NotConfigured ->
                    Text(stringResource(R.string.update_not_configured), style = MaterialTheme.typography.bodySmall, color = muted)
                UpdateState.Idle ->
                    TextButton(onClick = onCheck, modifier = minHeight) { Text(stringResource(R.string.check_update)) }
                UpdateState.Checking ->
                    Text(stringResource(R.string.checking_update), style = MaterialTheme.typography.bodyMedium, color = muted)
                UpdateState.UpToDate ->
                    TextButton(onClick = onCheck, modifier = minHeight) { Text(stringResource(R.string.up_to_date)) }
                is UpdateState.Available ->
                    Button(onClick = { onInstall(state.info) }, modifier = minHeight) {
                        Text(stringResource(R.string.update_available, state.info.versionName))
                    }
                is UpdateState.Downloading ->
                    Text(stringResource(R.string.downloading_update, state.percent), style = MaterialTheme.typography.bodyMedium, color = muted)
                is UpdateState.Installing ->
                    Text(stringResource(R.string.installing_update), style = MaterialTheme.typography.bodyMedium, color = muted)
                is UpdateState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(state.messageRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    TextButton(onClick = { state.info?.let(onInstall) ?: onCheck() }, modifier = minHeight) {
                        Text(stringResource(R.string.update_retry))
                    }
                }
            }
        }
        if (state is UpdateState.Downloading) {
            LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        if (state is UpdateState.Available && state.info.notes != null) {
            Text(
                text = state.info.notes,
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
