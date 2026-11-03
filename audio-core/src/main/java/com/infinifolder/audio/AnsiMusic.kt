package com.infinifolder.audio

import java.io.IOException
import java.io.InterruptedIOException
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.PI

/** BBS ANSI Music / BASIC PLAY melody syntax with a softened PC-speaker timbre. */
object AnsiMusic {
    private const val RATE=44100
    private const val TABLE_SIZE=2048
    // A sine with restrained odd harmonics keeps the square wave's character
    // without its sharp edges. The retained harmonics stay below Nyquist throughout
    // the parser's supported note range. Normalize once to the existing peak.
    private val waveform by lazy {
        val table=DoubleArray(TABLE_SIZE) {index ->
            val angle=2*PI*index/TABLE_SIZE
            sin(angle)+0.20*sin(3*angle)+0.06*sin(5*angle)
        }
        val peak=table.maxOf {abs(it)}
        for(index in table.indices)table[index]/=peak
        table
    }
    private data class Tone(val start:Long,val end:Long,val gate:Long,val frequency:Double)
    fun open(strings:List<String>):PcmStream {
        val tones=ArrayList<Tone>()
        var tempo=120;var octave=4;var defaultLength=4;var gate=7.0/8;var clock=0.0
        fun check() {if(Thread.currentThread().isInterrupted)throw InterruptedIOException("ANSI music cancelled")}
        for(string in strings) {
            var at=0
            fun number(default:Int):Int {
                var n=0;val start=at
                while(at<string.length && string[at].isDigit()) {
                    if(at-start>=6)throw IOException("Invalid ANSI music number")
                    n=n*10+(string[at++]-'0')
                }
                return if(at==start)default else n
            }
            fun note(key:Int?,length:Int) {
                if(length !in 1..64)throw IOException("Invalid ANSI music note length")
                var multiplier=1.0;var part=0.5
                while(at<string.length && string[at]=='.') {multiplier+=part;part*=0.5;at++}
                val duration=RATE*240.0/tempo/length*multiplier
                val start=clock.roundToLong();clock+=duration
                if(clock>RATE*1800.0 || tones.size>=100000)throw IOException("ANSI music exceeds playback limit")
                val end=clock.roundToLong()
                tones.add(Tone(start,end,start+(duration*gate).roundToLong(),key?.let {440.0*2.0.pow((it-69)/12.0)} ?: 0.0))
            }
            while(at<string.length) {
                check()
                when(val c=string[at++].uppercaseChar()) {
                    ' ','\r','\n','\t' -> {}
                    'T' -> tempo=number(120).coerceIn(32,255)
                    'O' -> octave=number(4).coerceIn(0,6)
                    'L' -> {defaultLength=number(4);if(defaultLength !in 1..64)throw IOException("Invalid ANSI music length")}
                    '<' -> octave=(octave-1).coerceAtLeast(0)
                    '>' -> octave=(octave+1).coerceAtMost(6)
                    'M' -> when(string.getOrNull(at++)?.uppercaseChar()) {
                        'N' -> gate=7.0/8
                        'L' -> gate=1.0
                        'S' -> gate=3.0/4
                        'F','B' -> {} // Foreground/background affect terminal scheduling, not melody pitches.
                        else -> throw IOException("Invalid ANSI music articulation")
                    }
                    'N' -> {val n=number(-1);if(n !in 0..84)throw IOException("Invalid ANSI music note");note(if(n==0)null else n+11,defaultLength)}
                    'P' -> note(null,number(defaultLength))
                    in 'A'..'G' -> {
                        var key=12+octave*12+when(c){'C'->0;'D'->2;'E'->4;'F'->5;'G'->7;'A'->9;else->11}
                        when(string.getOrNull(at)) {'#','+'->{key++;at++};'-'->{key--;at++}}
                        note(key,number(defaultLength))
                    }
                    else -> throw IOException("Unsupported ANSI music command")
                }
            }
        }
        if(tones.none {it.frequency>0})throw IOException("No playable ANSI music")
        return object:PcmStream {
            override val format=PcmFormat(RATE,1,clock.roundToLong(),16)
            override val decoderName="ANSI Music PC speaker"
            override val sourceFormat="ANSI Music"
            private var frame=0L;private var index=0;private var phase=0.0;private var closed=false
            @Synchronized override fun read(samples:IntArray,offset:Int,length:Int):Int {
                require(offset>=0 && length>=0 && length<=samples.size-offset)
                if(closed)throw IOException("ANSI music stream closed")
                check()
                if(length==0)return 0
                if(frame>=format.totalFrames!!)return -1
                val count=minOf(length.toLong(),format.totalFrames!!-frame).toInt()
                val table=waveform
                for(i in 0 until count) {
                    if(i and 1023==0)check()
                    while(index<tones.lastIndex && frame>=tones[index].end){index++;phase=0.0}
                    val tone=tones[index]
                    samples[offset+i]=if(tone.frequency>0 && frame<tone.gate) {
                        val position=phase*TABLE_SIZE
                        val slot=position.toInt()
                        val fraction=position-slot
                        val wave=table[slot]+(table[(slot+1)%TABLE_SIZE]-table[slot])*fraction
                        // Keep the existing gate/end frames, including short notes
                        // and rests; soften only the active samples at each edge.
                        val active=tone.gate-tone.start
                        val attack=minOf(RATE/200L,active/2) // Up to 5 ms.
                        val release=minOf(RATE/125L,active/2) // Up to 8 ms.
                        val fadeIn=if(attack>0)minOf(1.0,(frame-tone.start).toDouble()/attack) else 1.0
                        val fadeOut=if(release>0)minOf(1.0,(tone.gate-frame-1).toDouble()/release) else 1.0
                        val sample=(4096*wave*minOf(fadeIn,fadeOut)).roundToInt().coerceIn(-4096,4096)
                        phase+=tone.frequency/RATE;phase-=kotlin.math.floor(phase)
                        sample shl 16
                    } else 0
                    frame++
                }
                return count
            }
            @Synchronized override fun close(){closed=true}
        }
    }
}
