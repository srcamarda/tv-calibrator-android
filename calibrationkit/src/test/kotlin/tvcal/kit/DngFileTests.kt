package tvcal.kit

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DngFileTests {
    private class Tag(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    /** A minimal TIFF: IFD0 with `ifd0`, and optionally a SubIFD with `sub`. */
    private fun tiff(little: Boolean, ifd0: List<Tag>, sub: List<Tag> = emptyList()): ByteArray {
        fun u16(v: Int) = if (little) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
        fun u32(v: Long) = if (little) u16((v and 0xFFFF).toInt()) + u16((v shr 16).toInt()) else u16((v shr 16).toInt()) + u16((v and 0xFFFF).toInt())

        val out = ByteArrayOutputStream()
        val blobs = ByteArrayOutputStream()
        fun ifdBytes(tags: List<Tag>, at: Long, blobBase: Long, extra: Tag? = null): ByteArray {
            val all = (tags + listOfNotNull(extra)).sortedBy { it.tag }
            val b = ByteArrayOutputStream()
            b.write(u16(all.size))
            for (t in all) {
                b.write(u16(t.tag)); b.write(u16(t.type)); b.write(u32(t.count.toLong()))
                if (t.data.size <= 4) b.write(t.data + ByteArray(4 - t.data.size))
                else { b.write(u32(blobBase + blobs.size())); blobs.write(t.data) }
            }
            b.write(u32(0))
            return b.toByteArray()
        }
        // Layout: header (8) | IFD0 | SubIFD | blobs
        val ifd0Size = 2 + 12 * (ifd0.size + if (sub.isEmpty()) 0 else 1) + 4
        val subAt = 8L + ifd0Size
        val subSize = if (sub.isEmpty()) 0 else 2 + 12 * sub.size + 4
        val blobBase = subAt + subSize
        val subTag = if (sub.isEmpty()) null else Tag(0x014A, 4, 1, u32(subAt))
        val i0 = ifdBytes(ifd0, 8, blobBase, subTag)
        val i1 = if (sub.isEmpty()) ByteArray(0) else ifdBytes(sub, subAt, blobBase)
        out.write(if (little) "II".toByteArray() else "MM".toByteArray())
        out.write(u16(42)); out.write(u32(8))
        out.write(i0); out.write(i1); out.write(blobs.toByteArray())
        return out.toByteArray()
    }

    private fun srational(little: Boolean, m: Mat3): ByteArray {
        val b = ByteArrayOutputStream()
        for (row in m) for (v in row) {
            val n = Math.round(v * 10000).toInt()
            for (x in listOf(n, 10000)) {
                val bytes = byteArrayOf((x shr 24).toByte(), (x shr 16).toByte(), (x shr 8).toByte(), x.toByte())
                b.write(if (little) bytes.reversedArray() else bytes)
            }
        }
        return b.toByteArray()
    }

    private fun short(little: Boolean, v: Int) = if (little) byteArrayOf(v.toByte(), 0) else byteArrayOf(0, v.toByte())

    private val cm1 = ColourMath.mat(doubleArrayOf(0.9, -0.3, -0.1), doubleArrayOf(-0.4, 1.2, 0.2), doubleArrayOf(-0.05, 0.15, 0.6))
    private val cm2 = ColourMath.mat(doubleArrayOf(0.8, -0.25, -0.08), doubleArrayOf(-0.45, 1.25, 0.18), doubleArrayOf(-0.1, 0.2, 0.55))
    private val fm1 = ColourMath.mat(doubleArrayOf(0.6, 0.25, 0.1), doubleArrayOf(0.25, 0.8, -0.05), doubleArrayOf(0.02, -0.1, 0.9))

    private fun assertMat(want: Mat3, got: Mat3?) {
        requireNotNull(got)
        for (r in 0..2) for (c in 0..2) assertEquals(want[r][c], got[r][c], 1e-4)
    }

    private fun check(little: Boolean) {
        val bytes = tiff(
            little,
            ifd0 = listOf(
                Tag(0x010F, 2, 8, "samsung\u0000".toByteArray()),
                Tag(0xC614, 2, 9, "SM-S936B\u0000".toByteArray()),
                Tag(0xC621, 10, 9, srational(little, cm1)),
                Tag(0xC622, 10, 9, srational(little, cm2)),
                Tag(0xC65A, 3, 1, short(little, 17)),
                Tag(0xC65B, 3, 1, short(little, 21)),
            ),
            // ForwardMatrix1 in a SubIFD, to check those are followed too.
            sub = listOf(Tag(0xC714, 10, 9, srational(little, fm1))),
        )
        val info = DngFile.read(bytes)
        assertEquals("samsung", info.make)
        assertEquals("SM-S936B", info.cameraModel)
        assertMat(cm1, info.colour.colorMatrix1)
        assertMat(cm2, info.colour.colorMatrix2)
        assertMat(fm1, info.colour.forwardMatrix1)
        assertNull(info.colour.forwardMatrix2)
        assertEquals(17, info.colour.illuminant1)
        assertEquals(21, info.colour.illuminant2)
        // And the matrices build a conversion.
        DngColour.cameraToXYZ(info.colour)
    }

    @Test fun littleEndian() = check(true)

    @Test fun bigEndian() = check(false)

    @Test
    fun rejectsOtherFiles() {
        assertFailsWith<IllegalArgumentException> { DngFile.read("not a dng file at all".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { DngFile.read(tiff(true, listOf(Tag(0x010F, 2, 4, "abc\u0000".toByteArray())))) }
    }

    @Test
    fun textRoundTrip() {
        val d = DngColourData(cm1, cm2, fm1, null, null, null, 17, 21)
        val (back, source) = DngColourText.read(DngColourText.write(d, "test.dng"))
        assertEquals("test.dng", source)
        assertMat(cm1, back.colorMatrix1)
        assertMat(fm1, back.forwardMatrix1)
        assertNull(back.forwardMatrix2)
        assertEquals(21, back.illuminant2)
    }
}
