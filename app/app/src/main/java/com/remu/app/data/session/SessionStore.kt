package com.remu.app.data.session

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.remu.app.network.RemuJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Reads and writes the session. The repository keeps the decrypted copy in memory. */
interface SessionStore {
    suspend fun read(): StoredSession
    suspend fun update(transform: (StoredSession) -> StoredSession): StoredSession
}

/** DataStore serializer that writes only ciphertext (Android Keystore AES-GCM) to disk. */
class EncryptedSessionSerializer(private val cipher: BlobCipher) : Serializer<StoredSession> {
    override val defaultValue: StoredSession = StoredSession()

    override suspend fun readFrom(input: InputStream): StoredSession {
        val blob = input.readBytes()
        if (blob.isEmpty()) return defaultValue
        return try {
            val plain = cipher.decrypt(blob)
            RemuJson.decodeFromString(StoredSession.serializer(), plain.decodeToString())
        } catch (e: Exception) {
            throw CorruptionException("session could not be decrypted", e)
        }
    }

    override suspend fun writeTo(t: StoredSession, output: OutputStream) {
        val plain = RemuJson.encodeToString(StoredSession.serializer(), t).encodeToByteArray()
        output.write(cipher.encrypt(plain))
    }
}

class DataStoreSessionStore(file: File, cipher: BlobCipher, scope: CoroutineScope) : SessionStore {
    private val store: DataStore<StoredSession> = DataStoreFactory.create(
        serializer = EncryptedSessionSerializer(cipher),
        // A key lost with a factory reset or restore makes the blob unreadable: start unpaired.
        corruptionHandler = ReplaceFileCorruptionHandler { StoredSession() },
        scope = scope,
        produceFile = { file },
    )

    override suspend fun read(): StoredSession = store.data.first()
    override suspend fun update(transform: (StoredSession) -> StoredSession): StoredSession = store.updateData { transform(it) }
}

class InMemorySessionStore(initial: StoredSession = StoredSession()) : SessionStore {
    @Volatile private var value = initial
    override suspend fun read(): StoredSession = value
    @Synchronized private fun set(v: StoredSession) { value = v }
    override suspend fun update(transform: (StoredSession) -> StoredSession): StoredSession {
        val v = transform(value)
        set(v)
        return v
    }
}

/** The decrypted session in memory, loaded once at start, written through to the store. */
class SessionRepository(private val store: SessionStore, scope: CoroutineScope) {
    private val _state = MutableStateFlow(StoredSession())
    val state: StateFlow<StoredSession> = _state
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    init {
        scope.launch {
            _state.value = store.read()
            _loaded.value = true
        }
    }

    val current: StoredSession get() = _state.value

    suspend fun awaitLoaded(): StoredSession { loaded.first { it }; return _state.value }

    suspend fun update(transform: (StoredSession) -> StoredSession): StoredSession {
        val v = store.update(transform)
        _state.value = v
        return v
    }
}
