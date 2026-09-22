# Server Packs+

A client-side Fabric mod for Minecraft **26.1.2** and **26.2** that overhauls
mandatory server resource packs.

## Features

- **Reject server packs but still join the server** — Server Packs+ blocks the
  server pack and allows you to join the server without ever downloading and
  applying the pack.
- **Remembers your choice per server** — Keep the vanilla behavior, or choose to
  reject or cache a pack on join!
- **Cache a server pack** — Allows you to keep a server pack applied even if you
  disconnect or even quit the game.
- **Convert a server pack to a regular resource pack** — Allows you to
  automatically convert and apply a server resource pack into a regular,
  toggleable and editable `.zip` resource pack.
- **Update server packs** — Grab new server pack files if it was updated, and
  pop them into your own converted pack!
- **Per-server mod enabling/disabling** — Allows you to toggle whether or not
  you want the mod to interact with packs at all on a per-server basis.
- **Config screen** — Can be accessed by running `/serverpacksplus` or
  `/spplus`, or through finding the mod in Mod Menu and clicking the config
  button.

## Requirements

- **Minecraft** — Whichever version of the mod you are downloading, make sure it
  matches your Minecraft version
- **Fabric Loader** 0.19.3+
- **Fabric API**
- **Java 25**
- **Mod Menu** (optional, but needed in order to utilize the config menu)

## Usage

- When you join a server for the first time, it will ask you to accept and join,
  reject and join, or reject and disconnect. Your choice will be remembered and
  the server will be added to the list in the config screen.
- Open the config screen to choose how you want the mod to behave on a
  per-server basis.

## Building from source

1. Install **Java 25** (JDK) — [Temurin 25](https://adoptium.net) works well.
2. Open this folder in IntelliJ IDEA with the **Minecraft Development** plugin
   and let Gradle sync (the first sync downloads Minecraft's libraries — expect
   several minutes), or build from the command line:

```
$env:JAVA_HOME = "<path to a JDK 25 install>"
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
