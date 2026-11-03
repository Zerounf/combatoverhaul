# Combat Overhaul

Minecraft 1.20.1 Forge skeletal animation editor / combat framework.

## Build

Requires Java 17 and the Forge 1.20.1 MDK dependencies.

```bash
./gradlew build --offline
```

The compiled mod jar is written to `build/libs/combatoverhaul-1.0.0.jar`.

## Local dependency

`damageoverhaul` is a compile-time dependency and its jar is not committed.
Place the matching `damageoverhaul-1.0.0.jar` in `libs/` before building.

## Main features

- model import / editor
- CPU linear-blend skinning
- point rig and semantic bone binding
- Scratch-style event graphs
- layered animation and combat stance work in progress

See [`HANDOFF.md`](HANDOFF.md) for the detailed implementation state.
