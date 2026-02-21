package com.rendyhd.vicu.data.remote.interceptor

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BaseUrlHolder @Inject constructor() {
    @Volatile
    var baseUrl: String = ""

    fun getFullBaseUrl(): String {
        if (baseUrl.isEmpty()) return ""
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return "${normalized}api/v1/"
    }
}
