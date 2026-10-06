package com.rendyhd.vicu.util

import java.util.Base64

actual object Base64Decoder {
    // java.util.Base64 (API 26+, the app's minSdk) accepts input with or without padding and,
    // unlike android.util.Base64, also works in plain JVM unit tests.
    actual fun decodeUrlSafe(src: String): ByteArray {
        return Base64.getUrlDecoder().decode(src)
    }
}
