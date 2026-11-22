package com.antigravity.remote.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.antigravity.remote.data.UiText

fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Raw -> value
    is UiText.Res -> context.getString(id, *args.toTypedArray())
}

@Composable
fun UiText.asString(): String = resolve(LocalContext.current)
