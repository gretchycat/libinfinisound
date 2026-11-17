# libinfinisound agent guide

Owns shared audio processing and PCM APIs, portable decoders and synthesis, Android media adapters, and audio capability discovery. Consuming applications own playback UI, queues, and app state.

## Component-specific rules

- Build and release this component independently from its own checkout, using only its declared dependencies.
- Keep public interfaces and reusable functionality in this repository's documented ownership boundary.
- Do not depend on sibling application source, private files, build outputs, or machine-specific workspace paths.
- Record actual supported behavior and keep planned or incomplete work clearly labeled.

## Ecosystem workflow for agents

1. Read `docs/ECOSYSTEM.md` first to understand the component map and ownership boundaries.
2. Before changing a public interface, read this repository's architecture and API documentation listed below and inspect its compatibility notes.
3. Check the owning shared library repository before implementing behavior that may already exist elsewhere. Do not conclude a capability is unavailable just because its implementation is outside the current repository.
4. Keep application responsibilities, shared library APIs, and other applications' private data/source separate.
5. Before a cross-repository change, check the exact dependency pin and the relevant interoperability contract. Dependency versions and protocol versions are separate; do not infer protocol incompatibility from a library-version mismatch.
6. Update the local documentation index and relevant API/behavior documentation when public behavior changes. `docs/ECOSYSTEM.md` is generated from the central manifest and must not be edited locally.

## Documentation index

- `README.md` — project purpose, prerequisites, and standalone build instructions.
- `AGENTS.md` — repository-specific development rules and this ecosystem workflow.
- `docs/README.md` — local documentation index.
- `docs/ECOSYSTEM.md` — generated ecosystem-wide inventory, boundaries, dependencies, versions, and protocols.
- `CAPABILITIES.md` — runtime capability registration and compatibility contract.
- `docs/architecture.md` — public API and architecture documentation; read before changing interfaces or behavior.
- `docs/third-party.md` — public API and architecture documentation; read before changing interfaces or behavior.
- `SOURCE_PROVENANCE.md` — source provenance, licensing, and attribution.