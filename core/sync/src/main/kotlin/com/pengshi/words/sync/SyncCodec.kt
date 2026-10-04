package com.pengshi.words.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

/** A versioned, length-prefixed codec so event payloads are deterministic and delimiter-safe. */
object SyncCodec {
    private const val VERSION = 1

    fun encodeEvent(event: SyncEventRecord): ByteArray = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(VERSION)
            data.writeUTF(event.eventId)
            data.writeUTF(event.deviceId)
            data.writeLong(event.sequence)
            data.writeUTF(event.kind.name)
            data.writeLong(event.occurredAtUtc.toEpochMilli())
            writeNullable(data, event.planKey)
            writeNullable(data, event.wordKey)
            data.writeUTF(event.payload)
            writeNullable(data, event.replacesEventId)
        }
        output.toByteArray()
    }

    fun decodeEvent(encoded: ByteArray): SyncEventRecord = DataInputStream(ByteArrayInputStream(encoded)).use { data ->
        require(data.readInt() == VERSION) { "Unsupported sync event version" }
        SyncEventRecord(
            eventId = data.readUTF(),
            deviceId = data.readUTF(),
            sequence = data.readLong(),
            kind = SyncEventKind.valueOf(data.readUTF()),
            occurredAtUtc = Instant.ofEpochMilli(data.readLong()),
            planKey = readNullable(data),
            wordKey = readNullable(data),
            payload = data.readUTF(),
            replacesEventId = readNullable(data),
        )
    }

    fun encodeEvents(events: List<SyncEventRecord>): ByteArray = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(VERSION)
            data.writeInt(events.size)
            events.forEach { event ->
                val encoded = encodeEvent(event)
                data.writeInt(encoded.size)
                data.write(encoded)
            }
        }
        output.toByteArray()
    }

    fun decodeEvents(encoded: ByteArray): List<SyncEventRecord> = DataInputStream(ByteArrayInputStream(encoded)).use { data ->
        require(data.readInt() == VERSION) { "Unsupported sync event chunk version" }
        val size = data.readInt()
        require(size in 0..100_000) { "Invalid sync event chunk size: $size" }
        buildList(size) {
            repeat(size) {
                val eventSize = data.readInt()
                require(eventSize in 1..10_000_000) { "Invalid sync event size: $eventSize" }
                add(decodeEvent(data.readNBytes(eventSize)))
            }
        }
    }

    fun encodePayloadV2(payload: SyncPayloadV2Model): String = SyncPayloadV2.encode(payload)

    fun decodePayloadV2(encoded: String): SyncPayloadV2Model = SyncPayloadV2.decode(encoded)

    private fun writeNullable(data: DataOutputStream, value: String?) {
        data.writeBoolean(value != null)
        if (value != null) data.writeUTF(value)
    }

    private fun readNullable(data: DataInputStream): String? = if (data.readBoolean()) data.readUTF() else null
}
