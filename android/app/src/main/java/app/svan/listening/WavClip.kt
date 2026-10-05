package app.svan.listening

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Local PCM/float WAV, bounded to an eight-second stereo excerpt. No decoder/network permission. */
data class WavClip(val rate: Int,val samples: FloatArray) {
    companion object {
        fun decode(bytes: ByteArray): WavClip {
            require(bytes.size in 44..12_000_000) { "Choose a WAV smaller than 12 MB" }
            val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun text(pos: Int)=String(bytes,pos,4,Charsets.US_ASCII)
            require(text(0)=="RIFF"&&text(8)=="WAVE") { "Choose a PCM or float WAV file" }
            var format=0;var channels=0;var rate=0;var bits=0;var align=0;var dataStart=0;var dataLength=0
            var pos=12
            while(pos+8<=bytes.size) {
                val length=b.getInt(pos+4);require(length>=0&&length.toLong()+pos+8<=bytes.size) {"WAV contains a truncated chunk"}
                if(text(pos)=="fmt ") {
                    require(length>=16);format=b.getShort(pos+8).toInt() and 65535;channels=b.getShort(pos+10).toInt() and 65535
                    rate=b.getInt(pos+12);align=b.getShort(pos+20).toInt() and 65535;bits=b.getShort(pos+22).toInt() and 65535
                    if(format==65534){require(length>=40);format=b.getShort(pos+32).toInt() and 65535}
                }
                if(text(pos)=="data"){dataStart=pos+8;dataLength=length}
                pos+=8+length+(length and 1)
            }
            require(channels in 1..2&&rate in 16000..96000&&bits in listOf(16,24,32)&&align==channels*bits/8&&
                (format==1||(format==3&&bits==32))&&dataLength>0&&dataLength%align==0) { "Use mono/stereo 16/24/32-bit PCM or 32-bit float WAV (16–96 kHz)" }
            val frames=minOf(dataLength/align,rate*8);require(frames>=rate*4) {"Choose at least four seconds of music"}
            val out=FloatArray(frames*2);b.position(dataStart)
            repeat(frames) {i ->
                repeat(channels) {ch ->
                    val value=when {
                        format==3 -> b.float
                        bits==16 -> b.short/32768f
                        bits==24 -> {val v=(b.get().toInt() and 255) or ((b.get().toInt() and 255) shl 8) or (b.get().toInt() shl 16);v/8388608f}
                        else -> b.int/2147483648f
                    }
                    require(value.isFinite()&&kotlin.math.abs(value)<=1.001) {"WAV contains invalid or overloaded samples"}
                    out[i*2+ch]=value
                }
                if(channels==1)out[i*2+1]=out[i*2]
            }
            return WavClip(rate,out)
        }
    }
}
