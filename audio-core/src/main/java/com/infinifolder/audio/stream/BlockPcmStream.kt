package com.infinifolder.audio.stream

import com.infinifolder.audio.PcmStream

/** Adapters produce at most a codec frame at a time, independent of track duration. */
abstract class BlockPcmStream : PcmStream {
    private var block = IntArray(0)
    private var position = 0
    protected abstract fun nextBlock(): IntArray?
    final override fun read(samples: IntArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= samples.size - length)
        require(length % format.channels == 0)
        if (length == 0) return 0
        checkDecodeCancellation()
        while (position == block.size) {
            block = nextBlock() ?: return -1
            require(block.size % format.channels == 0) { "Incomplete PCM frame" }
            position = 0
            checkDecodeCancellation()
        }
        val count = minOf(length, block.size - position)
        block.copyInto(samples, offset, position, position + count)
        position += count
        return count
    }
}
