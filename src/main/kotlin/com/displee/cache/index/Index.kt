package com.displee.cache.index

import com.displee.cache.CacheLibrary
import com.displee.cache.ProgressListener
import com.displee.cache.index.archive.Archive
import com.displee.cache.index.archive.ArchiveSector
import com.displee.compress.CompressionType
import com.displee.compress.compress
import com.displee.compress.decompress
import com.displee.compress.type.Compressor
import com.displee.compress.type.EmptyCompressor
import com.displee.io.impl.InputBuffer
import com.displee.io.impl.OutputBuffer
import com.displee.util.generateCrc
import com.displee.util.generateWhirlpool
import kotlinx.coroutines.*
import java.io.RandomAccessFile
import kotlin.coroutines.CoroutineContext

open class Index(origin: CacheLibrary, id: Int, val raf: RandomAccessFile) : ReferenceTable(origin, id), CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + SupervisorJob()

    var crc = 0
    var whirlpool: ByteArray? = null
    var compressionType: CompressionType = CompressionType.NONE
    var compressor: Compressor = EmptyCompressor
    private var cached = false
    protected var closed = false
    protected var autoUpdateRevision = true

    val info get() = "Index[id=" + id + ", archives=" + archives.size + ", compression=" + compressionType + "]"

    init {
        init()
    }

    protected open fun init() {
        if (id < 0 || id >= 255) {
            return
        }
        val archiveSector = origin.index255?.readArchiveSector(id) ?: return
        val archiveSectorData = archiveSector.data
        crc = archiveSectorData.generateCrc()
        whirlpool = archiveSectorData.generateWhirlpool(origin.whirlpool)
        read(InputBuffer(archiveSector.decompress(origin.compressors)))
        compressionType = archiveSector.compressionType
        compressor = archiveSector.compressor
    }

    fun cache() {
        check(!closed) { "Index is closed." }
        if (cached) {
            return
        }
        archives.values.forEach {
            try {
                archive(it.id, it.xtea, false)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
        cached = true
    }

    fun unCache() {
        for (archive in archives()) {
            archive.restore()
        }
        cached = false
    }

    @JvmOverloads
    open fun update(listener: ProgressListener? = null): Boolean = runBlocking {
        updateAsync(listener)
    }

    @JvmOverloads
    open suspend fun updateAsync(listener: ProgressListener? = null): Boolean {
        check(!closed) { "Index is closed." }
        val flaggedArchives = flaggedArchives()

        // Process archives in parallel
        val results = flaggedArchives.mapIndexed { index, archive ->
            async {
                val progress = index.toDouble() / flaggedArchives.size
                if (archive.autoUpdateRevision) {
                    archive.revision++
                }
                archive.unFlag()
                listener?.notify(progress * 0.80, "Repacking archive ${archive.id}...")
                val compressed = archive.write().compress(archive.compressionType, archive.compressor, archive.xtea, archive.revision)
                archive.crc = compressed.generateCrc(length = compressed.size - 2)
                archive.whirlpool = compressed.generateWhirlpool(origin.whirlpool, length = compressed.size - 2)
                val written = writeArchiveSectorAsync(archive.id, compressed)
                if (!written) {
                    throw IllegalStateException("Unable to write data to archive sector. Your cache may be corrupt.")
                }
                if (origin.clearDataAfterUpdate) {
                    archive.restore()
                }
                true
            }
        }

        // Wait for all archives to be processed
        results.awaitAll()

        listener?.notify(0.85, "Updating checksum table for index $id...")
        if (flaggedArchives.isNotEmpty() && !flagged()) {
            flag()
        }
        if (flagged()) {
            unFlag()
            if (autoUpdateRevision) {
                revision++
            }
            val indexData = write().compress(compressionType, compressor)
            crc = indexData.generateCrc()
            whirlpool = indexData.generateWhirlpool(origin.whirlpool)
            val written = origin.index255?.writeArchiveSector(this.id, indexData) ?: false
            check(written) { "Unable to write data to checksum table. Your cache may be corrupt." }
        }
        listener?.notify(1.0, "Successfully updated index $id.")
        return true
    }

    fun readArchiveSector(id: Int): ArchiveSector? = runBlocking {
        readArchiveSectorAsync(id)
    }

    suspend fun readArchiveSectorAsync(id: Int): ArchiveSector? = withContext(Dispatchers.IO) {
        check(!closed) { "Index is closed." }
        synchronized(origin.mainFile) {
            try {
                if (origin.mainFile.length() < INDEX_SIZE * id + INDEX_SIZE) {
                    return@synchronized null
                }
                val sectorData = ByteArray(SECTOR_SIZE)
                raf.seek(id.toLong() * INDEX_SIZE)
                raf.read(sectorData, 0, INDEX_SIZE)
                val bigSector = id > 65535
                val buffer = InputBuffer(sectorData)
                val archiveSector = ArchiveSector(bigSector, buffer.read24BitInt(), buffer.read24BitInt())
                if (archiveSector.size < 0 || archiveSector.position <= 0 || archiveSector.position > origin.mainFile.length() / SECTOR_SIZE) {
                    return@synchronized null
                }
                var read = 0
                var chunk = 0
                val sectorHeaderSize = if (bigSector) SECTOR_HEADER_SIZE_BIG else SECTOR_HEADER_SIZE_SMALL
                val sectorDataSize = if (bigSector) SECTOR_DATA_SIZE_BIG else SECTOR_DATA_SIZE_SMALL
                while (read < archiveSector.size) {
                    if (archiveSector.position == 0) {
                        return@synchronized null
                    }
                    var requiredToRead = archiveSector.size - read
                    if (requiredToRead > sectorDataSize) {
                        requiredToRead = sectorDataSize
                    }
                    origin.mainFile.seek(archiveSector.position.toLong() * SECTOR_SIZE)
                    origin.mainFile.read(buffer.raw(), 0, requiredToRead + sectorHeaderSize)
                    buffer.offset = 0
                    archiveSector.read(buffer)
                    if (!isIndexValid(archiveSector.index) || id != archiveSector.id || chunk != archiveSector.chunk) {
                        return@synchronized null
                    } else if (archiveSector.nextPosition < 0 || archiveSector.nextPosition > origin.mainFile.length() / SECTOR_SIZE) {
                        return@synchronized null
                    }
                    val bufferData = buffer.raw()
                    for (i in 0 until requiredToRead) {
                        archiveSector.data[read++] = bufferData[i + sectorHeaderSize]
                    }
                    archiveSector.position = archiveSector.nextPosition
                    chunk++
                }
                return@synchronized archiveSector
            } catch (exception: Exception) {
                return@synchronized null
            }
        }
    }

    fun writeArchiveSector(id: Int, data: ByteArray): Boolean = runBlocking {
        writeArchiveSectorAsync(id, data)
    }

    suspend fun writeArchiveSectorAsync(id: Int, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        check(!closed) { "Index is closed." }
        synchronized(origin.mainFile) {
            try {
                var position: Int
                var archive: Archive? = null
                var archiveSector: ArchiveSector? = readArchiveSector(id)
                if (this@Index.id != 255) {
                    archive = archive(id, null, true)
                }
                var overWrite = this@Index.id == 255 && archiveSector != null || archive?.new == false
                val sectorData = ByteArray(SECTOR_SIZE)
                val bigSector = id > 65535
                if (overWrite) {
                    if (INDEX_SIZE * id + INDEX_SIZE > raf.length()) {
                        return@synchronized false
                    }
                    raf.seek(id.toLong() * INDEX_SIZE)
                    raf.read(sectorData, 0, INDEX_SIZE)
                    val buffer = InputBuffer(sectorData)
                    buffer.offset += 3
                    position = buffer.read24BitInt()
                    if (position <= 0 || position > origin.mainFile.length() / SECTOR_SIZE) {
                        return@synchronized false
                    }
                } else {
                    position = ((origin.mainFile.length() + (SECTOR_SIZE - 1)) / SECTOR_SIZE).toInt()
                    if (position == 0) {
                        position = 1
                    }
                    archiveSector = ArchiveSector(bigSector, data.size, position, id, indexToWrite(this@Index.id))
                }
                archiveSector ?: return@synchronized false
                val buffer = OutputBuffer(6)
                buffer.write24BitInt(data.size)
                buffer.write24BitInt(position)
                raf.seek(id.toLong() * INDEX_SIZE)
                raf.write(buffer.array(), 0, INDEX_SIZE)
                var written = 0
                var chunk = 0
                val archiveHeaderSize = if (bigSector) SECTOR_HEADER_SIZE_BIG else SECTOR_HEADER_SIZE_SMALL
                val archiveDataSize = if (bigSector) SECTOR_DATA_SIZE_BIG else SECTOR_DATA_SIZE_SMALL
                while (written < data.size) {
                    var currentPosition = 0
                    if (overWrite) {
                        origin.mainFile.seek(position.toLong() * SECTOR_SIZE)
                        origin.mainFile.read(sectorData, 0, archiveHeaderSize)
                        archiveSector.read(InputBuffer(sectorData))
                        currentPosition = archiveSector.nextPosition
                        if (archiveSector.id != id || archiveSector.chunk != chunk || !isIndexValid(archiveSector.index)) {
                            return@synchronized false
                        }
                        if (currentPosition < 0 || origin.mainFile.length() / SECTOR_SIZE < currentPosition) {
                            return@synchronized false
                        }
                    }
                    if (currentPosition == 0) {
                        overWrite = false
                        currentPosition = ((origin.mainFile.length() + (SECTOR_SIZE - 1)) / SECTOR_SIZE).toInt()
                        if (currentPosition == 0) {
                            currentPosition++
                        }
                        if (currentPosition == position) {
                            currentPosition++
                        }
                    }
                    if (data.size - written <= archiveDataSize) {
                        currentPosition = 0
                    }
                    archiveSector.chunk = chunk
                    archiveSector.position = currentPosition
                    origin.mainFile.seek(position.toLong() * SECTOR_SIZE)
                    origin.mainFile.write(archiveSector.write(), 0, archiveHeaderSize)
                    var length = data.size - written
                    if (length > archiveDataSize) {
                        length = archiveDataSize
                    }
                    origin.mainFile.write(data, written, length)
                    written += length
                    position = currentPosition
                    chunk++
                }
                return@synchronized true
            } catch (t: Throwable) {
                t.printStackTrace()
                return@synchronized false
            }
        }
    }

    fun fixCRCs(update: Boolean) = runBlocking {
        fixCRCsAsync(update)
    }

    suspend fun fixCRCsAsync(update: Boolean) = withContext(Dispatchers.IO) {
        check(!closed) { "Index is closed." }
        if (is317()) {
            return@withContext
        }
        val archiveIds = archiveIds()
        var flag = false

        // Process archives in parallel
        val results = archiveIds.map { archiveId ->
            async {
                val sector = readArchiveSectorAsync(archiveId) ?: return@async false
                val correctCRC = sector.data.generateCrc(length = sector.data.size - 2)
                val archive = archive(archiveId) ?: return@async false
                val currentCRC = archive.crc
                if (currentCRC == correctCRC) {
                    return@async false
                }
                println("Incorrect CRC in index $id -> archive $archiveId, current_crc=$currentCRC, correct_crc=$correctCRC")
                archive.flag()
                return@async true
            }
        }

        // Check if any archive has incorrect CRC
        flag = results.awaitAll().any { it }

        val sectorData = origin.index255?.readArchiveSector(id)?.data ?: return@withContext
        val indexCRC = sectorData.generateCrc()
        if (crc != indexCRC) {
            flag = true
        }
        if (flag && update) {
            updateAsync()
        } else if (!flag) {
            println("No invalid CRCs found.")
            return@withContext
        }
        unCache()
    }

    /**
     * Clear the archives.
     */
    fun clear() {
        archives.clear()
        crc = 0
        whirlpool = ByteArray(WHIRLPOOL_SIZE)
    }

    override fun close() {
        if (closed) {
            return
        }
        raf.close()
        closed = true

        // Cancel all coroutines
        coroutineContext.cancel()
    }

    fun flaggedArchives(): Array<Archive> {
        return archives.values.filter { it.flagged() }.toTypedArray()
    }

    protected open fun isIndexValid(index: Int): Boolean {
        return this.id == index
    }

    protected open fun indexToWrite(index: Int): Int {
        return index
    }

    override fun toString(): String {
        return "Index $id"
    }

    companion object {
        const val INDEX_SIZE = 6
        const val SECTOR_HEADER_SIZE_SMALL = 8
        const val SECTOR_DATA_SIZE_SMALL = 512
        const val SECTOR_HEADER_SIZE_BIG = 10
        const val SECTOR_DATA_SIZE_BIG = 510
        const val SECTOR_SIZE = 520
        const val WHIRLPOOL_SIZE = 64
    }

}
