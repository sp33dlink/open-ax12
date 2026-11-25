package org.openax12.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.tools.screenshot.PreviewTest

/**
 * Sample screenshot test that proves the rendering pipeline. It prints the canvas size it was
 * given so the PNG itself shows whether the 853x480 dp configuration was applied.
 */
@PreviewTest
@Ax12Preview
@Composable
fun ScaffoldCanvasScreenshot() {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFDDF2F6)),
    ) {
        val canvasSize = "${maxWidth.value.toInt()} x ${maxHeight.value.toInt()} dp"
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(Color(0xFF14283A)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "open-ax12", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = canvasSize, color = Color(0xFF1B2B3A), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.padding(top = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    listOf(0xFF2F7FB5, 0xFF1FB8A6, 0xFFF2A65A, 0xFF12A66A).forEach { argb ->
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(Color(argb), RoundedCornerShape(16.dp)),
                        )
                    }
                }
            }
        }
    }
}
