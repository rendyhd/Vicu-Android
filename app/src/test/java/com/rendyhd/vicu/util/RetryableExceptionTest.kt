package com.rendyhd.vicu.util

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class RetryableExceptionTest {

    private fun http(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "".toResponseBody("application/json".toMediaType())))

    @Test fun `unknown host is retriable`() =
        assertTrue(isRetriableNetworkError(UnknownHostException("x")))

    @Test fun `socket timeout is retriable`() =
        assertTrue(isRetriableNetworkError(SocketTimeoutException("x")))

    @Test fun `plain socket exception is retriable`() =
        assertTrue(isRetriableNetworkError(SocketException("Network is unreachable")))

    @Test fun `interrupted io is retriable`() =
        assertTrue(isRetriableNetworkError(InterruptedIOException("x")))

    @Test fun `generic io exception is retriable`() =
        assertTrue(isRetriableNetworkError(IOException("unexpected end of stream")))

    @Test fun `wrapped io cause is retriable`() =
        assertTrue(isRetriableNetworkError(RuntimeException(IOException("x"))))

    @Test fun `ssl handshake is NOT retriable`() =
        assertFalse(isRetriableNetworkError(SSLHandshakeException("bad cert")))

    @Test fun `wrapped ssl cause is NOT retriable`() =
        assertFalse(isRetriableNetworkError(IOException(SSLHandshakeException("bad cert"))))

    @Test fun `http 500 and 429 are retriable`() {
        assertTrue(isRetriableNetworkError(http(500)))
        assertTrue(isRetriableNetworkError(http(429)))
    }

    @Test fun `http 404 and 400 are NOT retriable`() {
        assertFalse(isRetriableNetworkError(http(404)))
        assertFalse(isRetriableNetworkError(http(400)))
    }

    @Test fun `non-network exception is NOT retriable`() =
        assertFalse(isRetriableNetworkError(IllegalStateException("x")))
}
