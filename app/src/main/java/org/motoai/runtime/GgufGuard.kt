package org.motoai.runtime

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Streaming allocation guard before upstream metadata parsing. Weight bytes are never rewritten. */
internal object GgufGuard {
    fun validate(fd:ParcelFileDescriptor) {
        val fileBytes=fd.statSize;require(fileBytes in 24..64L*1024*1024*1024) {UiText.get(R.string.ui_invalid_gguf_size)}
        Os.lseek(fd.fileDescriptor,0,OsConstants.SEEK_SET)
        DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.fileDescriptor))).use {input->
            var consumed=0L;val budget=minOf(fileBytes,64L*1024*1024)
            fun charge(n:Long){require(n>=0 && n<=budget-consumed) {UiText.get(R.string.ui_gguf_metadata_exceeds_the_safe_64_mib)};consumed+=n}
            fun u32():Long {charge(4);return Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL}
            fun u64():Long {charge(8);return java.lang.Long.reverseBytes(input.readLong()).also {require(it>=0) {"GGUF integer exceeds supported bounds"}}}
            val buffer=ByteArray(16384)
            fun skip(n:Long){charge(n);var left=n;while(left>0){val count=minOf(left,buffer.size.toLong()).toInt();input.readFully(buffer,0,count);left-=count}}
            fun string(capture:Boolean=false):String {
                val n=u64();require(n<=if(capture)4096 else 8L*1024*1024) {"GGUF string exceeds safe bounds"}
                if(!capture){skip(n);return ""}
                charge(n);val bytes=ByteArray(n.toInt());input.readFully(bytes)
                return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            }
            require(u32()==0x46554747L && u32() in 2..3) {UiText.get(R.string.ui_gguf_v2_v3_supported)}
            val tensors=u64();val keys=u64();require(tensors in 1..32768 && keys in 1..65536) {"GGUF counts exceed phone parser bounds"}
            val primitive=mapOf(0L to 1,1L to 1,2L to 2,3L to 2,4L to 4,5L to 4,6L to 4,7L to 1,10L to 8,11L to 8,12L to 8)
            val names=hashSetOf<String>()
            repeat(keys.toInt()) {
                val key=string(true);require(key.isNotEmpty() && names.add(key)) {"Duplicate or empty GGUF key"}
                when(val type=u32()) {
                    8L -> string()
                    9L -> {
                        val element=u32();val count=u64();require(count<=1000000) {"GGUF array exceeds safe count"}
                        if(element==8L)repeat(count.toInt()){string()}
                        else {val width=primitive[element]?:error("Nested/unknown GGUF array type");require(count<=budget/width);skip(count*width)}
                    }
                    else -> skip((primitive[type]?:error("Unknown GGUF value type")).toLong())
                }
            }
            repeat(tensors.toInt()) {
                string(true);val dimensions=u32();require(dimensions in 1..4) {"GGUF tensor dimensions unsupported"}
                repeat(dimensions.toInt()){require(u64()>0) {"Zero GGUF dimension"}}
                u32();require(u64()<fileBytes) {"GGUF tensor offset exceeds file"}
            }
        }
        Os.lseek(fd.fileDescriptor,0,OsConstants.SEEK_SET)
        // The pinned upstream parser supplies the type/block-size calculations;
        // Java does not duplicate GGML quantization tables.
        NativeRuntime.inspect("/proc/self/fd/${fd.fd}")
    }
}
