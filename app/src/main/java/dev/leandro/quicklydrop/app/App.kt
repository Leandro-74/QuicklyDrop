package dev.leandro.quicklydrop.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leandro.quicklydrop.R
import dev.leandro.quicklydrop.core.service.TransferFormat
import dev.leandro.quicklydrop.core.service.TransferStatusState
import dev.leandro.quicklydrop.core.transfer.TransferProgress
import dev.leandro.quicklydrop.core.transfer.TransferResult
import dev.leandro.quicklydrop.protocol.FileMeta

@Composable
fun QuicklyDropTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) {
        darkColorScheme(
            primary = Color(0xFF4DD8C8),
            onPrimary = Color(0xFF003731),
            secondary = Color(0xFFB1CCC6),
            background = Color(0xFF101413),
            surface = Color(0xFF101413),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF006A60),
            onPrimary = Color.White,
            secondary = Color(0xFF4A635F),
            background = Color(0xFFFBFDFB),
            surface = Color(0xFFFBFDFB),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun QuicklyDropApp(
    viewModel: MainViewModel,
    onReceive: () -> Unit,
    onStopReceive: () -> Unit,
    onPickFiles: () -> Unit,
    onStartScan: () -> Unit,
    onCancelSend: () -> Unit,
    onDismissSend: () -> Unit,
    onRetrySend: () -> Unit,
    onResetReceive: () -> Unit,
) {
    val send by viewModel.sendState.collectAsStateWithLifecycle()
    val receive by viewModel.receiveState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))

            when {
                send !is SendState.Idle -> SendContent(
                    state = send,
                    onStartScan = onStartScan,
                    onCancelSend = onCancelSend,
                    onDismissSend = onDismissSend,
                    onRetrySend = onRetrySend,
                )

                receive !is TransferStatusState.Idle -> ReceiveContent(
                    state = receive,
                    onStopReceive = onStopReceive,
                    onResetReceive = onResetReceive,
                )

                else -> HomeContent(
                    onReceive = onReceive,
                    onPickFiles = onPickFiles,
                )
            }
        }
    }

    message?.let { detail ->
        AlertDialog(
            onDismissRequest = viewModel::clearMessage,
            title = { Text(stringResource(R.string.app_name)) },
            text = { Text(detail) },
            confirmButton = {
                TextButton(onClick = viewModel::clearMessage) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun HomeContent(
    onReceive: () -> Unit,
    onPickFiles: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.app_privacy),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onReceive,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(stringResource(R.string.receive_files))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onPickFiles,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(stringResource(R.string.send_files))
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.home_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ReceiveContent(
    state: TransferStatusState,
    onStopReceive: () -> Unit,
    onResetReceive: () -> Unit,
) {
    Text(
        text = stringResource(R.string.receive_files),
        style = MaterialTheme.typography.headlineMedium,
    )
    Spacer(Modifier.height(24.dp))

    when (state) {
        is TransferStatusState.Preparing -> {
            Text(
                text = stringResource(R.string.receive_starting),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onStopReceive, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.receive_cancel))
            }
        }

        is TransferStatusState.Armed -> {
            Text(
                text = stringResource(R.string.receive_ready),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.receive_tap),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.receive_keep_open),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onStopReceive, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.receive_cancel))
            }
        }

        is TransferStatusState.Active -> {
            val progress = state.progress
            Text(
                text = stringResource(R.string.receiving, progress.file.name),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(16.dp))
            ProgressBar(progress)
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.receive_keep_open),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onStopReceive, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.receive_cancel))
            }
        }

        is TransferStatusState.Finished -> {
            ResultContent(
                result = state.result,
                successTitle = stringResource(R.string.receive_complete),
                failureTitle = stringResource(R.string.receive_failed),
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onResetReceive, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.done))
            }
        }

        TransferStatusState.Idle -> Unit
    }
}

@Composable
private fun SendContent(
    state: SendState,
    onStartScan: () -> Unit,
    onCancelSend: () -> Unit,
    onDismissSend: () -> Unit,
    onRetrySend: () -> Unit,
) {
    Text(
        text = stringResource(R.string.send_files),
        style = MaterialTheme.typography.headlineMedium,
    )
    Spacer(Modifier.height(24.dp))

    when (state) {
        SendState.Idle -> Unit

        SendState.Loading -> {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.working))
        }

        is SendState.Ready -> {
            Text(
                text = stringResource(R.string.ready_to_send),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            FileList(state.files)
            Spacer(Modifier.height(24.dp))

            if (state.scanning) {
                Text(
                    text = stringResource(R.string.scanning),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.send_tap),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = onDismissSend, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                Text(
                    text = stringResource(R.string.send_tap),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onStartScan, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(stringResource(R.string.send_scan))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.send_keep_open),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDismissSend) {
                    Text(stringResource(R.string.back))
                }
            }
        }

        is SendState.Sending -> {
            val progress = state.progress
            Text(
                text = if (progress != null) {
                    stringResource(R.string.sending, progress.file.name)
                } else {
                    stringResource(R.string.working)
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(16.dp))
            if (progress != null) {
                ProgressBar(progress)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.send_keep_open),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onCancelSend, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.send_cancel))
            }
        }

        is SendState.Finished -> {
            ResultContent(
                result = state.result,
                successTitle = stringResource(R.string.send_complete),
                failureTitle = stringResource(R.string.send_failed),
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDismissSend, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.done))
            }
        }

        is SendState.Error -> {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onRetrySend, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.retry))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismissSend) {
                Text(stringResource(R.string.back))
            }
        }
    }
}

@Composable
private fun ResultContent(
    result: TransferResult,
    successTitle: String,
    failureTitle: String,
) {
    when (result) {
        is TransferResult.Success -> {
            Text(
                text = successTitle,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.file_count, result.files))
            Text(TransferFormat.bytes(result.bytes))
        }

        is TransferResult.Failure -> {
            Text(
                text = failureTitle,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Text(result.message)
        }
    }
}

@Composable
private fun ProgressBar(progress: TransferProgress) {
    LinearProgressIndicator(
        progress = { progress.totalFraction },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(
            R.string.transfer_progress,
            TransferFormat.bytes(progress.fileBytes),
            TransferFormat.bytes(progress.file.size),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun FileList(files: List<FileMeta>) {
    Text(
        text = stringResource(R.string.file_count, files.size),
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(8.dp))
    files.take(5).forEach { file ->
        Text(
            text = "• ${file.name}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (files.size > 5) {
        Text(
            text = stringResource(R.string.more_files, files.size - 5),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
