# Source provenance

## 2026-10-31 — reject substitute decoder audio

The owner reported a RealMedia file producing the test chord. The extracted
legacy advanced-audio, WMA, telephony and chiptune stubs synthesized fixed PCM
without decoding their inputs. Their playback registrations/substitute branches
are removed; compatibility stubs return unsupported. Existing DSD/ADX sample
conversion, MIDI/tracker note rendering and valid soundbank previews remain.
SFZ now decodes the actual referenced WAV and missing/invalid samples or empty
GUS patches fail. Regression expectations that formerly accepted substitute
chords are corrected. No upstream codec implementation was replaced or added.
Original extraction boundaries and upstream notices remain in README.md and
docs/third-party.md; original-code licensing remains pending owner selection.
