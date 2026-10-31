# FFmpeg public header snapshot

Unmodified public headers from the installed Termux `ffmpeg 8.1.2-5` package:
libavutil 60.26.102, libavcodec 62.28.102 and libavformat 62.12.102.
Upstream: https://ffmpeg.org/ and https://github.com/FFmpeg/FFmpeg.

The native adapter dynamically resolves this API from the existing libVLC binary.
It checks AVClass's exact header version before accessing structs. Missing API or
mismatched ABI falls back to libVLC's existing public PCM callbacks. Headers do
not add a second codec binary. Each header retains its original copyright and
license text; the LGPL text and provenance are also packaged as Android assets
under `third-party/ffmpeg`. Original adapter licensing follows the project.
