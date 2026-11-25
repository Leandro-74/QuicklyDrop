package dev.leandro.quicklydrop.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareIntentsTest {

    private val first = Uri.parse("content://test/first")
    private val second = Uri.parse("content://test/second")

    @Test
    fun `single send reads the stream extra`() {
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, first)

        assertEquals(listOf(first), ShareIntents.uris(intent))
    }

    @Test
    fun `multiple send reads the list`() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))

        assertEquals(listOf(first, second), ShareIntents.uris(intent))
    }

    @Test
    fun `clip data is merged and duplicates are removed`() {
        val intent = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM, first)
            .apply { clipData = ClipData.newRawUri("file", first) }
        intent.clipData = ClipData.newRawUri("second", second)

        assertEquals(listOf(first, second), ShareIntents.uris(intent))
    }

    @Test
    fun `ignores unrelated intents`() {
        assertEquals(emptyList<Uri>(), ShareIntents.uris(Intent(Intent.ACTION_MAIN)))
        assertEquals(emptyList<Uri>(), ShareIntents.uris(null))
    }
}
