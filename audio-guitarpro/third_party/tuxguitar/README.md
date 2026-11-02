# TuxGuitar GP3 reader and MIDI sequence subset

Upstream: https://github.com/helge17/tuxguitar
Revision: 74d67f3681322fddf7462a36450f56c80295450b
License: GNU LGPL 2.1, retained in LICENSE.html. Authors retained in AUTHORS.

Only the GP3 reader, score model/managers and MIDI sequencing dependency closure
are included (70 Java sources). No editor UI, audio device, SWT or prebuilt jar.
Build from source via the separate audio-guitarpro module. The full upstream tree
at the revision above supplies the corresponding original source.

Local hardening: GP3 measure/track/string/comment/beat/bend-point counts and
allocation lengths are bounded; expanded repeats are bounded and interruptible.
The Sound adapter enforces a 4 MiB input budget, strict EOF, a parsing deadline,
500,000 output events and a thirty-minute maximum synthesized score duration.
No additional file format is advertised by including shared model classes.
