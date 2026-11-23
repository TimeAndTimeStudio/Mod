# timesmp-client

Fabric Client Mod for **Time SMP** (Minecraft 26.2 & 26.3, Java 25).

## Features
- **Custom / Offline Skins & Data URIs**: Mixes into vanilla Minecraft's `SkinManager` and `SkinTextureDownloader` to allow skin loading from custom server URLs, Ely.by, and `data:image/png;base64,...` URIs without Mojang domain restrictions or broken network calls.
- **Identical Rendering with TML Client**: Vanilla Java clients with `timesmp-client` installed see all offline and custom skins configured on Time SMP seamlessly.

## Build
```bash
./gradlew build
```
Build output: `build/libs/timesmp-client.jar`
