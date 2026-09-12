package app.amber.core.utils

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ContextImageExportTest {
    @Test
    fun `image exports report failure when the gallery rejects insertion`() {
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, object : ContentProvider() {
            override fun onCreate() = true
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun getType(uri: Uri): String? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        })
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("image-export", ".png", activity.cacheDir)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertFalse(activity.exportImage(activity, bitmap))
            assertFalse(activity.exportJpegImage(activity, bitmap))
            assertFalse(activity.exportImageFile(activity, file))
        } finally {
            file.delete()
            bitmap.recycle()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `image exports remove inserted gallery entries when closing output fails`() {
        val insertedUri = Uri.parse("content://media/external/images/media/1")
        val deletedUris = mutableListOf<Uri>()
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, object : ContentProvider() {
            override fun onCreate() = true
            override fun insert(uri: Uri, values: ContentValues?) = insertedUri
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun getType(uri: Uri): String? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
                deletedUris += uri
                return 1
            }
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        })
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        shadowOf(activity.contentResolver).registerOutputStreamSupplier(insertedUri) {
            object : ByteArrayOutputStream() {
                override fun close() = throw IOException("Unable to finish output")
            }
        }
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("image-export", ".png", activity.cacheDir)
        try {
            assertFalse(activity.exportImage(activity, bitmap))
            assertFalse(activity.exportJpegImage(activity, bitmap))
            assertFalse(activity.exportImageFile(activity, file))
            assertEquals(listOf(insertedUri, insertedUri, insertedUri), deletedUris)
        } finally {
            file.delete()
            bitmap.recycle()
            controller.pause().stop().destroy()
        }
    }
}
