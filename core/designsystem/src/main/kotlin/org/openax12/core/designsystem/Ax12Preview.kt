package org.openax12.core.designsystem

import androidx.compose.ui.tooling.preview.Preview

/**
 * Preview configuration matching the AX12 screen: 1280x720 px landscape at density 1.5,
 * i.e. the 853x480 dp virtual canvas.
 *
 * Use it for every full-screen preview and screenshot test (see CLAUDE.md > Visual checks).
 */
@Preview(
    name = "AX12",
    device = AX12_DEVICE_SPEC,
    showSystemUi = false,
    showBackground = true,
    backgroundColor = 0xFFDDF2F6,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
annotation class Ax12Preview

/** Device spec string for [Preview.device] that reproduces the AX12 screen. */
const val AX12_DEVICE_SPEC: String = "spec:width=1280px,height=720px,dpi=240"
