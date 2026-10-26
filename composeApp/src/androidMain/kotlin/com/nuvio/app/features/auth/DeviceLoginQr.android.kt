package com.nuvio.app.features.auth

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

@Composable
internal actual fun DeviceLoginQr(url: String) {
    val bitmap = remember(url) {
        runCatching {
            val matrix = QRCodeWriter().encode(
                url,
                BarcodeFormat.QR_CODE,
                320,
                320,
                mapOf(EncodeHintType.MARGIN to 2),
            )
            val pixels = IntArray(matrix.width * matrix.height) { index ->
                if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK
                else android.graphics.Color.WHITE
            }
            Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Scan to sign in on another device",
            modifier = Modifier
                .size(200.dp)
                .background(Color.White, RoundedCornerShape(12.dp))
                .padding(8.dp),
        )
    }
}
