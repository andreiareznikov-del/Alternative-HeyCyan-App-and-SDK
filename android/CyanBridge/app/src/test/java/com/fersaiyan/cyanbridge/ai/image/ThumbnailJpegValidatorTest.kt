package com.fersaiyan.cyanbridge.ai.image

import java.io.File
import java.util.Base64
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ThumbnailJpegValidatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(bytes: ByteArray): File = temporary.newFile().apply { writeBytes(bytes) }
    private fun jpeg() = Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAIAAgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDGooor7I+VP//Z")

    @Test fun acceptsFreshDecodableJpeg() {
        assertTrue(ThumbnailJpegValidator.isValid(fixture(jpeg())))
    }
    @Test fun rejectsTruncatedJpegEvenWhenHeaderHasDimensions() {
        val bytes = jpeg()
        assertFalse(ThumbnailJpegValidator.isValid(fixture(bytes.copyOf(bytes.size - 15))))
    }
    @Test fun rejectsFramingWithoutImageData() {
        assertFalse(ThumbnailJpegValidator.isValid(fixture(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))))
    }
    @Test fun rejectsEmptyOrNonJpegData() {
        assertFalse(ThumbnailJpegValidator.isValid(fixture(byteArrayOf())))
        assertFalse(ThumbnailJpegValidator.isValid(fixture("not a JPEG".toByteArray())))
    }
    @Test fun rejectsMissingFile() {
        assertFalse(ThumbnailJpegValidator.isValid(File(temporary.root, "missing.jpg")))
    }
}
