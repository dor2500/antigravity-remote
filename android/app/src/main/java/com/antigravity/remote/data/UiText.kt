package com.antigravity.remote.data

import androidx.annotation.StringRes

/** Text shown to the user: either a string resource (translated on screen) or text from the PC. */
sealed interface UiText {
    data class Raw(val value: String) : UiText
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
}

/** An HTTP error from the bridge, with the bridge's own (already translated) message. */
class BridgeHttpException(val code: Int, message: String) : java.io.IOException(message)
