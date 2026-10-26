package org.motoai.runtime

/** Bounded, paged RAM transport. No files, mmap storage, saved state or prompt logging. */
internal class EphemeralOutput(val id: String, private val limit: Int = 16 * 1024 * 1024) {
    private val pages = ArrayList<ByteArray>()
    private var length = 0
    @Synchronized fun size(): Int = length
    @Synchronized fun append(bytes: ByteArray) {
        require(bytes.size <= limit - length) { UiText.get(R.string.ui_response_exceeds_in_memory_transport_budget_bytes, (limit)) }
        var offset = 0
        while (offset < bytes.size) {
            val page = length / PAGE
            val position = length % PAGE
            if (page == pages.size) pages.add(ByteArray(PAGE))
            val count = minOf(PAGE - position, bytes.size - offset)
            bytes.copyInto(pages[page], position, offset, offset + count)
            offset += count; length += count
        }
    }
    @Synchronized fun read(offset: Int, maximum: Int = 32768): ByteArray {
        require(offset in 0..length && maximum in 1..32768) { UiText.get(R.string.ui_invalid_response_range) }
        val output = ByteArray(minOf(maximum, length - offset))
        var copied = 0
        while (copied < output.size) {
            val position = offset + copied
            val count = minOf(PAGE - position % PAGE, output.size - copied)
            pages[position / PAGE].copyInto(output, copied, position % PAGE, position % PAGE + count)
            copied += count
        }
        return output
    }
    companion object { private const val PAGE = 4096 }
}
