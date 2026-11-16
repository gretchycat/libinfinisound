MIT License

Copyright (c) 2026 Gretchen Maculo

Scope: This license applies to original code authored for this project by Gretchen Maculo. It does not relicense third-party code, libraries, data, fonts, artwork, themes, or other assets that have their own copyright or license notices. Those components remain under their stated terms, which must be followed when distributing them.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Bundled codec notices

The Android module now depends on libVLC-Android 3.7.7 (LGPL-2.1-or-later),
including its FluidLite MIDI synthesizer, through a dynamic PCM-only adapter.
Its license and upstream source references are packaged under
`audio-processing/src/main/assets/third-party/libvlc-pcm/`.

No General MIDI SoundFont is bundled. Applications may allow users to import
their own SF2/SF3 banks; those files remain user-supplied content.

The optional FluidLite/GME/FFmpeg C APIs supplied by the same libVLC binary have
additional original notices under `third-party/fluidlite`,
`third-party/game-music-emu` and `third-party/ffmpeg`. Vendored FFmpeg public
headers retain their original copyright/license comments.
