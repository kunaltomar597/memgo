package com.remu.app.data

import com.remu.app.data.session.DataStoreSessionStore
import com.remu.app.data.session.EncryptedSessionSerializer
import com.remu.app.data.session.InMemoryKeyCipher
import com.remu.app.data.session.StoredSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** TASK-041: the token and fingerprint are on disk only as AES-GCM ciphertext. */
class SessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val secret = StoredSession(hubBaseUrl = "https://192.168.1.20:8443/", hubToken = "app-token-SECRET", certSha256 = "AB:CD:EF", ownerAccessToken = "jwt-SECRET")

    @Test fun roundTripIsEncrypted() = runBlocking {
        val ser = EncryptedSessionSerializer(InMemoryKeyCipher())
        val out = ByteArrayOutputStream()
        ser.writeTo(secret, out)
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains("SECRET"))
        assertFalse(text.contains("192.168.1.20"))
        assertFalse(text.contains("AB:CD:EF"))
        assertEquals(secret, ser.readFrom(ByteArrayInputStream(bytes)))
    }

    @Test fun twoWritesUseDifferentIvs() = runBlocking {
        val ser = EncryptedSessionSerializer(InMemoryKeyCipher())
        val a = ByteArrayOutputStream().also { ser.writeTo(secret, it) }.toByteArray()
        val b = ByteArrayOutputStream().also { ser.writeTo(secret, it) }.toByteArray()
        assertFalse(a.contentEquals(b))
    }

    @Test fun dataStoreFileHoldsOnlyCiphertextAndUnreadableFileResets() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = tmp.newFolder().resolve("session.enc")
        val store = DataStoreSessionStore(file, InMemoryKeyCipher(), scope)
        store.update { secret }
        assertEquals(secret, store.read())
        assertTrue(file.exists())
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("SECRET"))
        scope.cancel()

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val broken = tmp.newFolder().resolve("session.enc").apply { writeBytes(ByteArray(40) { 7 }) }
        assertEquals(StoredSession(), DataStoreSessionStore(broken, InMemoryKeyCipher(), scope2).read())
        scope2.cancel()
    }
}
