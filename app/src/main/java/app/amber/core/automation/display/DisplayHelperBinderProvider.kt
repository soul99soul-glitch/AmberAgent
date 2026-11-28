package app.amber.core.automation.display

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.CompletableDeferred
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom

/** The host process accepts exactly one helper for each launch it initiated. */
class DisplayHelperBinderProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != Process.SHELL_UID) {
            throw SecurityException("Display helper registration requires shell UID")
        }
        require(method == REGISTER_HELPER) { "Unknown display helper request" }
        val helper = requireNotNull(extras?.getBinder(HELPER_BINDER)) { "Missing display helper binder" }
        register(arg, helper)
        return Bundle().apply { putBinder(HOST_BINDER, hostLifetime) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    internal class Registration internal constructor(val token: String) : Closeable {
        internal val result = CompletableDeferred<IBinder>()
        suspend fun awaitBinder(): IBinder = result.await()
        override fun close() = cancel(this)
    }

    companion object {
        internal const val REGISTER_HELPER = "register_helper"
        internal const val HELPER_BINDER = "helper"
        internal const val HOST_BINDER = "host"
        private val random = SecureRandom()
        private val hostLifetime = Binder()
        private var pending: Registration? = null

        @Synchronized
        internal fun expect(): Registration {
            check(pending == null) { "Display helper registration already pending" }
            val bytes = ByteArray(32).also(random::nextBytes)
            return Registration(bytes.joinToString("") { "%02x".format(it) }).also { pending = it }
        }

        @Synchronized
        private fun register(token: String?, binder: IBinder) {
            val registration = pending
            if (registration == null || token?.length != 64 ||
                !MessageDigest.isEqual(registration.token.toByteArray(Charsets.US_ASCII), token.toByteArray(Charsets.US_ASCII))
            ) {
                throw SecurityException("Invalid or expired display helper token")
            }
            pending = null
            registration.result.complete(binder)
        }

        @Synchronized
        private fun cancel(registration: Registration) {
            if (pending === registration) {
                pending = null
                registration.result.cancel()
            }
        }
    }
}
