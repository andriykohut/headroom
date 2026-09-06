package dev.andrii.headroom.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import dev.andrii.headroom.domain.Credential
import dev.andrii.headroom.domain.CredentialImport
import dev.andrii.headroom.domain.PayloadException
import java.util.concurrent.Executors

sealed interface ScanOutcome {
    data class Linked(val credential: Credential) : ScanOutcome
    data class Rejected(val message: String) : ScanOutcome
    /** Not a Headroom code at all — ignore silently and keep scanning. */
    data object NotOurs : ScanOutcome
}

/**
 * Pure classification of scanned or pasted text.
 *
 * A camera sees every code in frame, so a non-Headroom code must be ignored
 * rather than reported as an error; only a damaged *Headroom* code is worth
 * telling the user about.
 */
fun interpretScan(text: String): ScanOutcome {
    if (!CredentialImport.looksLikePayload(text)) return ScanOutcome.NotOurs
    return try {
        ScanOutcome.Linked(CredentialImport.decode(text))
    } catch (e: PayloadException) {
        ScanOutcome.Rejected(e.message ?: "That code couldn't be read.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onLinked: (Credential) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    // Saveable: the payload is long, and losing a paste to a rotation is the
    // kind of small cruelty that makes people give up on linking.
    var pasted by rememberSaveable { mutableStateOf("") }
    var showPaste by rememberSaveable { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        asked = true
        // A denied camera is a dead end unless the alternative is already open,
        // so open it rather than making the user find it.
        if (!result) showPaste = true
    }

    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Link an account") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            if (granted && !showPaste) {
                CameraSquare(onText = { text ->
                    when (val outcome = interpretScan(text)) {
                        is ScanOutcome.Linked -> onLinked(outcome.credential)
                        is ScanOutcome.Rejected -> message = outcome.message
                        ScanOutcome.NotOurs -> Unit // keep scanning
                    }
                })
            } else if (!granted && asked && !showPaste) {
                PermissionPanel()
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Point the camera at the code printed by /headroom-link.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "On your computer, run /headroom-link in Claude Code, " +
                    "or tools/headroom-link from a terminal.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            message?.let {
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            if (showPaste) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = { Text("Paste the headroom1: code") },
                    minLines = 4,
                    textStyle = MaterialTheme.typography.bodyMedium
                        .copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        when (val outcome = interpretScan(pasted)) {
                            is ScanOutcome.Linked -> onLinked(outcome.credential)
                            is ScanOutcome.Rejected -> message = outcome.message
                            ScanOutcome.NotOurs -> message =
                                "That doesn't look like a Headroom code."
                        }
                    },
                    enabled = pasted.isNotBlank(),
                ) { Text("Link") }
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { showPaste = !showPaste }) {
                Text(if (showPaste) "Scan instead" else "Paste the code instead")
            }
        }
    }
}

@Composable
private fun PermissionPanel() {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Camera unavailable",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Headroom can't scan without camera access. Paste the code instead — " +
                    "it links exactly the same way.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A square viewfinder with corner brackets.
 *
 * Square rather than full-bleed: a QR is square, and framing it is easier when
 * the target says so.
 */
@Composable
private fun CameraSquare(onText: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val bracket = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.large),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val provider = ProcessCameraProvider.getInstance(ctx).get()
                val scanner = BarcodeScanning.getClient()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { proxy ->
                    val image = proxy.image
                    if (image == null) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    scanner.process(
                        InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees),
                    )
                        .addOnSuccessListener { barcodes ->
                            barcodes.firstNotNullOfOrNull { it.rawValue }?.let(onText)
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                )
                previewView
            },
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val arm = size.minDimension * 0.12f
            val inset = size.minDimension * 0.06f
            val stroke = 3.dp.toPx()
            val corners = listOf(
                Offset(inset, inset) to listOf(Offset(arm, 0f), Offset(0f, arm)),
                Offset(size.width - inset, inset) to listOf(Offset(-arm, 0f), Offset(0f, arm)),
                Offset(inset, size.height - inset) to listOf(Offset(arm, 0f), Offset(0f, -arm)),
                Offset(size.width - inset, size.height - inset) to
                    listOf(Offset(-arm, 0f), Offset(0f, -arm)),
            )
            corners.forEach { (origin, arms) ->
                arms.forEach { delta ->
                    drawLine(
                        color = bracket,
                        start = origin,
                        end = Offset(origin.x + delta.x, origin.y + delta.y),
                        strokeWidth = stroke,
                    )
                }
            }
        }
    }
}
