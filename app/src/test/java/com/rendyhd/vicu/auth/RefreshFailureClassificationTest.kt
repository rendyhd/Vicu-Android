package com.rendyhd.vicu.auth

import com.rendyhd.vicu.util.isNetworkFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class RefreshFailureClassificationTest {

    private fun classify(e: Exception) = classifyRefreshException(e)

    @Test fun `unknown host is a network error`() =
        assertEquals(RefreshFailure.NetworkError, classify(UnknownHostException("vikunja.test")))

    @Test fun `socket timeout is a network error`() =
        assertEquals(RefreshFailure.NetworkError, classify(SocketTimeoutException("timeout")))

    @Test fun `refused connection is a network error`() =
        assertEquals(RefreshFailure.NetworkError, classify(ConnectException("refused")))

    @Test fun `connection reset is a network error`() =
        assertEquals(RefreshFailure.NetworkError, classify(SocketException("Connection reset")))

    @Test fun `interrupted io is a network error`() =
        assertEquals(RefreshFailure.NetworkError, classify(InterruptedIOException("x")))

    @Test fun `plain io exception is a network error`() =
        assertEquals(RefreshFailure.NetworkError, classify(IOException("unexpected end of stream")))

    @Test fun `io exception wrapped by another exception is a network error`() =
        assertEquals(RefreshFailure.NetworkError, classify(RuntimeException("wrapper", IOException("x"))))

    @Test fun `tls failures are still transport failures`() =
        assertEquals(RefreshFailure.NetworkError, classify(SSLHandshakeException("bad cert")))

    @Test fun `non network exceptions stay server errors`() {
        assertEquals(RefreshFailure.ServerError, classify(IllegalStateException("boom")))
        assertEquals(RefreshFailure.ServerError, classify(IllegalArgumentException("bad json")))
    }

    @Test fun `a cause chain without io exceptions is not a network failure`() {
        assertFalse(isNetworkFailure(RuntimeException(IllegalStateException(RuntimeException("deep")))))
    }

    @Test fun `a self referencing cause chain terminates`() {
        val loop = RuntimeException("loop")
        val other = RuntimeException("other", loop)
        loop.initCause(other)
        assertFalse(isNetworkFailure(loop))
        assertTrue(isNetworkFailure(IOException("io", loop)))
    }
}
