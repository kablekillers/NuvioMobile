package com.nuvio.app.features.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.auth_device_login_qr_description
import org.jetbrains.compose.resources.stringResource

@Composable
internal actual fun DeviceLoginQr(url: String) {
    val modules = remember(url) { QrCodeEncoder.encode(url) } ?: return
    val description = stringResource(Res.string.auth_device_login_qr_description)
    Canvas(
        modifier = Modifier
            .size(200.dp)
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .semantics { contentDescription = description },
    ) {
        val cell = size.minDimension / modules.size
        // Slight overlap avoids hairline gaps between neighbouring modules.
        val cellSize = Size(cell + 0.5f, cell + 0.5f)
        modules.forEachIndexed { y, row ->
            row.forEachIndexed { x, dark ->
                if (dark) drawRect(Color.Black, Offset(x * cell, y * cell), cellSize)
            }
        }
    }
}
