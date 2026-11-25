package org.openax12.core.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.openax12.core.model.ModelType
import java.io.File

/** Default picture of a model type (drawable in :core:designsystem), aspect MODEL_PICTURE_ASPECT. */
@DrawableRes
fun defaultModelPicture(type: ModelType): Int = when (type) {
    ModelType.GLIDER -> R.drawable.ax_model_glider
    ModelType.PLANE -> R.drawable.ax_model_plane
    ModelType.WING -> R.drawable.ax_model_wing
    ModelType.MULTIROTOR -> R.drawable.ax_model_multirotor
    ModelType.HELICOPTER -> R.drawable.ax_model_helicopter
    ModelType.OTHER -> R.drawable.ax_model_other
}

/**
 * A model's picture: the custom file (`picturePath`, cache key `pictureVersion`) or the default of `type`.
 *
 * The caller sizes it (normally with `Modifier.aspectRatio(MODEL_PICTURE_ASPECT)`). A custom
 * picture is cropped to fill, the default drawing is fitted. While the file loads, or if it
 * cannot be read, the default of [type] is shown.
 *
 * @param picturePath absolute path of the custom picture, or null for the default
 * @param pictureVersion changes whenever the file content changes, so a replaced picture is
 *   reloaded although the path is the same
 */
@Composable
fun ModelPicture(picturePath: String?, pictureVersion: Long, type: ModelType, modifier: Modifier = Modifier) {
    val fallback = painterResource(defaultModelPicture(type))
    val description = stringResource(R.string.ax_cd_model_picture)
    if (picturePath.isNullOrBlank() || LocalInspectionMode.current) {
        Image(painter = fallback, contentDescription = description, modifier = modifier, contentScale = ContentScale.Fit)
        return
    }
    val context = LocalContext.current
    val request = remember(context, picturePath, pictureVersion) {
        ImageRequest.Builder(context)
            .data(File(picturePath))
            .memoryCacheKey("$picturePath#$pictureVersion")
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = description,
        modifier = modifier,
        placeholder = fallback,
        error = fallback,
        contentScale = ContentScale.Crop,
    )
}
