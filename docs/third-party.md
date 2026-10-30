# Codec implementations

- MPEG audio: `javazoom:jlayer:1.0.1`, the existing JLayer dependency, now used for MP3 as well as Layer II. Its types remain private to the adapter.
- AAC synthesis: `com.tianscar.javasound:jaad:0.9.4`, Apache License 2.0. The adapter uses JAAD's `Decoder`/`Receiver` interface, without calling its optional Java Sound playback integration. The Apache license text is included at `META-INF/libinfinisound/JAAD-LICENSE.txt` in the core JAR. The dependency's [published metadata](https://central.sonatype.com/artifact/com.tianscar.javasound/jaad/0.9.4) and Maven sources identify the upstream implementation.
- AAC transport/container parsing: libinfinisound's ADTS and ISO-BMFF sample-table/edit-list readers. Codec selection and conversion are independent of the output device.
- FLAC: libinfinisound's parser and decoder, implemented from [RFC 9639](https://www.rfc-editor.org/rfc/rfc9639.html), with header/frame CRC verification, constant/verbatim/fixed/LPC reconstruction, Rice and escape residuals, wasted bits and stereo decorrelation.
- Tracker rendering: the existing libopenmpt JNI bridge and Kotlin fallback; packaging and load names are retained.
- ALAC: the existing bundled Peter McQuillan implementation with its source notices retained.

FLAC test fixtures use FFmpeg/libFLAC as independent encoders; those tools are not runtime dependencies.
