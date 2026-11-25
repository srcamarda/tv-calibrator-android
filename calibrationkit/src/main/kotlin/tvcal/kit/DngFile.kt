package tvcal.kit

/** Random access to a file's bytes, so a large DNG never has to be read whole. */
fun interface ByteSource {
    /** Up to `length` bytes starting at `offset` (fewer at the end of the file). */
    fun read(offset: Long, length: Int): ByteArray
}

/** What a DNG file says about its camera's colour. */
class DngInfo(
    val colour: DngColourData,
    /** DNG UniqueCameraModel, e.g. "SM-S936B". */
    val cameraModel: String?,
    /** TIFF Make and Model. */
    val make: String?,
    val model: String?,
)

/**
 * Reads the colour tags of a DNG file: ColorMatrix1/2, CameraCalibration1/2, ForwardMatrix1/2 and
 * CalibrationIlluminant1/2.
 *
 * Some phones (Samsung's, for one) don't report these matrices through Camera2, but their own camera
 * app writes them into its DNGs. They are the same for every phone of a model, so one DNG from the
 * camera that measures is enough.
 */
object DngFile {
    private const val MAKE = 0x010F
    private const val MODEL = 0x0110
    private const val SUB_IFDS = 0x014A
    private const val EXIF_IFD = 0x8769
    private const val UNIQUE_CAMERA_MODEL = 0xC614
    private const val COLOR_MATRIX_1 = 0xC621
    private const val COLOR_MATRIX_2 = 0xC622
    private const val CAMERA_CALIBRATION_1 = 0xC623
    private const val CAMERA_CALIBRATION_2 = 0xC624
    private const val CALIBRATION_ILLUMINANT_1 = 0xC65A
    private const val CALIBRATION_ILLUMINANT_2 = 0xC65B
    private const val FORWARD_MATRIX_1 = 0xC714
    private const val FORWARD_MATRIX_2 = 0xC715

    private val typeSize = mapOf(1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 6 to 1, 7 to 1, 8 to 2, 9 to 4, 10 to 8, 11 to 4, 12 to 8, 13 to 4)

    private class Entry(val type: Int, val count: Long, val data: ByteArray)

    private class Reader(val src: ByteSource, val little: Boolean) {
        fun u16(b: ByteArray, i: Int): Int =
            if (little) (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
            else ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

        fun u32(b: ByteArray, i: Int): Long =
            if (little) (u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16))
            else ((u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong())

        fun s32(b: ByteArray, i: Int): Int = u32(b, i).toInt()

        /** Reads one IFD: its tags, and the offset of the next IFD (0 at the end). */
        fun ifd(offset: Long): Pair<Map<Int, Entry>, Long> {
            val head = src.read(offset, 2)
            if (head.size < 2) return emptyMap<Int, Entry>() to 0L
            val n = u16(head, 0)
            val body = src.read(offset + 2, n * 12 + 4)
            if (body.size < n * 12) return emptyMap<Int, Entry>() to 0L
            val tags = HashMap<Int, Entry>()
            for (k in 0 until n) {
                val e = k * 12
                val tag = u16(body, e)
                val type = u16(body, e + 2)
                val count = u32(body, e + 4)
                val size = (typeSize[type] ?: continue) * count
                if (size > 1 shl 16) continue           // image data and the like: not needed here
                val data = if (size <= 4) body.copyOfRange(e + 8, e + 8 + size.toInt())
                else src.read(u32(body, e + 8), size.toInt())
                tags[tag] = Entry(type, count, data)
            }
            val next = if (body.size >= n * 12 + 4) u32(body, n * 12) else 0L
            return tags to next
        }

        fun longs(e: Entry): List<Long> = (0 until e.count.toInt()).map {
            when (e.type) {
                3 -> u16(e.data, it * 2).toLong()
                4 -> u32(e.data, it * 4)
                else -> 0L
            }
        }

        fun rationals(e: Entry): DoubleArray? {
            if (e.type != 5 && e.type != 10) return null
            return DoubleArray(e.count.toInt()) {
                val i = it * 8
                if (e.type == 10) {
                    val d = s32(e.data, i + 4)
                    if (d == 0) 0.0 else s32(e.data, i).toDouble() / d
                } else {
                    val d = u32(e.data, i + 4)
                    if (d == 0L) 0.0 else u32(e.data, i).toDouble() / d
                }
            }
        }

        fun text(e: Entry): String = String(e.data, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
    }

    /** Reads the colour tags. Throws if the file isn't a TIFF/DNG or has no colour matrix. */
    fun read(src: ByteSource): DngInfo {
        val header = src.read(0, 8)
        require(header.size == 8) { "file is too short" }
        val little = when {
            header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() -> true
            header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() -> false
            else -> throw IllegalArgumentException("not a DNG (no TIFF header)")
        }
        val r = Reader(src, little)
        require(r.u16(header, 2) == 42) { "not a DNG (bad TIFF magic number)" }

        // Every IFD reachable from IFD0: the chain, SubIFDs and the EXIF IFD. The colour tags belong
        // in IFD0, but some writers put them elsewhere.
        val tags = HashMap<Int, Entry>()
        val queue = ArrayDeque<Long>().apply { add(r.u32(header, 4)) }
        val seen = HashSet<Long>()
        while (queue.isNotEmpty() && seen.size < 64) {
            val off = queue.removeFirst()
            if (off <= 0 || !seen.add(off)) continue
            val (ifd, next) = r.ifd(off)
            for ((k, v) in ifd) tags.putIfAbsent(k, v)
            queue.add(next)
            ifd[SUB_IFDS]?.let { queue.addAll(r.longs(it)) }
            ifd[EXIF_IFD]?.let { queue.addAll(r.longs(it)) }
        }

        fun mat(tag: Int): Mat3? {
            val v = tags[tag]?.let { r.rationals(it) } ?: return null
            if (v.size != 9) return null
            return Array(3) { row -> DoubleArray(3) { col -> v[row * 3 + col] } }
        }
        fun illuminant(tag: Int): Int? = tags[tag]?.let { r.longs(it).firstOrNull()?.toInt() }

        val cm1 = mat(COLOR_MATRIX_1) ?: throw IllegalArgumentException("the DNG has no ColorMatrix1")
        return DngInfo(
            colour = DngColourData(
                colorMatrix1 = cm1,
                colorMatrix2 = mat(COLOR_MATRIX_2),
                forwardMatrix1 = mat(FORWARD_MATRIX_1),
                forwardMatrix2 = mat(FORWARD_MATRIX_2),
                calibration1 = mat(CAMERA_CALIBRATION_1),
                calibration2 = mat(CAMERA_CALIBRATION_2),
                illuminant1 = illuminant(CALIBRATION_ILLUMINANT_1) ?: 21,
                illuminant2 = illuminant(CALIBRATION_ILLUMINANT_2),
            ),
            cameraModel = tags[UNIQUE_CAMERA_MODEL]?.let { r.text(it) },
            make = tags[MAKE]?.let { r.text(it) },
            model = tags[MODEL]?.let { r.text(it) },
        )
    }

    fun read(bytes: ByteArray): DngInfo = read(ByteSource { offset, length ->
        if (offset >= bytes.size) ByteArray(0)
        else bytes.copyOfRange(offset.toInt(), minOf(bytes.size, (offset + length).toInt()))
    })
}

/** Saves and loads the colour data as plain text, one `key: values` per line (no JSON library needed). */
object DngColourText {
    fun write(d: DngColourData, source: String): String = buildString {
        appendLine("source: $source")
        fun m(name: String, v: Mat3?) { if (v != null) appendLine("$name: " + v.joinToString(" ") { it.joinToString(" ") }) }
        m("colorMatrix1", d.colorMatrix1)
        m("colorMatrix2", d.colorMatrix2)
        m("forwardMatrix1", d.forwardMatrix1)
        m("forwardMatrix2", d.forwardMatrix2)
        m("calibration1", d.calibration1)
        m("calibration2", d.calibration2)
        appendLine("illuminant1: ${d.illuminant1}")
        d.illuminant2?.let { appendLine("illuminant2: $it") }
    }

    fun read(text: String): Pair<DngColourData, String> {
        val kv = text.lines().mapNotNull { line ->
            val i = line.indexOf(':')
            if (i < 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.toMap()
        fun m(name: String): Mat3? = kv[name]?.split(' ')?.filter { it.isNotBlank() }?.map { it.toDouble() }
            ?.takeIf { it.size == 9 }?.let { v -> Array(3) { r -> DoubleArray(3) { c -> v[r * 3 + c] } } }
        val data = DngColourData(
            colorMatrix1 = requireNotNull(m("colorMatrix1")) { "no colorMatrix1" },
            colorMatrix2 = m("colorMatrix2"),
            forwardMatrix1 = m("forwardMatrix1"),
            forwardMatrix2 = m("forwardMatrix2"),
            calibration1 = m("calibration1"),
            calibration2 = m("calibration2"),
            illuminant1 = kv["illuminant1"]?.toIntOrNull() ?: 21,
            illuminant2 = kv["illuminant2"]?.toIntOrNull(),
        )
        return data to (kv["source"] ?: "")
    }
}
