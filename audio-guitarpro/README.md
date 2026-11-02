# audio-guitarpro

Optional JVM GP3 score reader and MIDI-timeline adapter, consumed by Android
Sound's existing MIDI synthesizer. No UI or output device is opened here.

Source build: `gradle :audio-guitarpro:assemble --offline` from libinfinisound.
Public entry points: `GuitarPro3.matches(bytes)` and `GuitarPro3.read(AudioInput)`.
The latter returns score tags and a bounded `MidiTimeline`. Unsupported versions,
truncation, excessive score expansion and cancellation remain errors.

The minimal TuxGuitar source subset, its pinned revision, LGPL 2.1 license,
authors and local hardening changes are documented under `third_party/tuxguitar`.
