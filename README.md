# Server Packs+

A Fabric client mod for Minecraft **1.21.11** that gives you real control over
server-forced resource packs — instead of vanilla's "accept once, reload
every time you rejoin" behavior.

## Features

- **Skip the vanilla prompt.** Server Packs+ intercepts the server's pack
  push and handles it directly, with a smarter one-time prompt on first join.
- **Remembers your choice per server** — accept & apply, always re-download
  fresh, or join without downloading — so you're not asked every single time.
- **Cache (keep applied).** Toggle a server's pack to stay applied when you
  disconnect, and even across a full game restart, so the textures don't
  reload every time you rejoin. Turn it off and the mod cleanly restores
  normal vanilla-style behavior.
- **Convert to a real, editable pack.** Turn a server's forced pack into an
  ordinary local resource pack in your `resourcepacks/` folder — pin it,
  reorder it, or edit it like any other pack, independent of the server.
  Shows a live extraction progress bar for larger packs.
- **Update from server** — re-download and refresh a pack (converted or not)
  without leaving/rejoining.
- **Revert / Forget** — drop back to the server's live pack, or wipe all
  remembered settings for a server so you're prompted fresh next time.
- **Per-server master switch** — disable Server Packs+ entirely for a
  specific server and let vanilla handle its prompt as normal.
- **Config screen** (`/serverpacksplus`, `/spplus`, or via Mod Menu) —
  smooth-scrolling server list, drag-to-reorder, and status badges
  (Enabled/Disabled, Converted, Active/Cached) for every server you've
  connected to.

## Requirements

- Minecraft **1.21.11**
- **Fabric Loader** 0.18.1+
- **Fabric API**
- **Java 21**
- Mod Menu (optional, for the in-game mod list entry)

## Installation

1. Grab the jar from Modrinth/GitHub (`serverpacksplus-1.0.jar`).
2. Drop it in your `mods` folder alongside Fabric Loader and Fabric API.
3. Launch the game. Join a server that pushes a resource pack, or run
   `/serverpacksplus` to open the config screen anytime.

## Usage

- On your first join to a new server with a forced pack, you'll get a
  one-time prompt: **Download & apply**, **Join without downloading**, or
  **Reject**. Your pick (except Reject) is remembered for next time.
- Open the config screen with `/serverpacksplus` (or `/spplus`) to review or
  change any server's settings, cache/uncache its pack, convert it to an
  editable local pack, update it, revert it, or forget it entirely.

## Building from source

1. Install **Java 21** (JDK) — [Temurin 21](https://adoptium.net) works well.
2. Install **IntelliJ IDEA** (Community Edition is fine) with the
   **Minecraft Development** plugin, or use the command line.
3. Open this folder in IntelliJ and let Gradle sync (first sync downloads
   Minecraft's libraries and decompiles the game — expect several minutes).
4. Run the **"Minecraft Client"** run configuration, or build a jar with:

```
$env:JAVA_HOME = "<path to a JDK 21 install>"
.\gradlew.bat build
```

The built jar lands in `build/libs/`.

## Project layout

```
src/main/java/com/rpplus/resourcepackplus/
  core/     ← version-agnostic logic (file I/O, config, pack merging, download/convert).
  compat/   ← thin bridge to Minecraft's actual classes (e.g. Mod Menu integration).
  mixin/    ← injection points into Minecraft internals (pack push handling, disconnect cleanup).
  gui/      ← config screen and the first-join prompt screen.
```

## License

MIT
