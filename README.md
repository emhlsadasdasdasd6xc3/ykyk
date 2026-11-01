# Team Tracker (Fabric, Minecraft 1.21.4)

Client-side mod. Works on any server; every teammate needs the mod installed.

## Commands
- `/team add <username>` / `/team remove <username>` / `/team list` / `/team clear`
- `/team clearpings` removes all active pings

## Features
- Teammates get a glow outline (visible through walls).
- HUD list: direction arrow, distance and height difference.
- **Ping key (default G, rebindable in Controls):** pings the block you're looking at (up to 256 blocks).
  Teammates see a gold arrow pointing to it, with distance, even when you're out of their range.
  Pings expire after 5 minutes or when reached.

## How pings are sent
Each ping is sent as a private `/msg` whisper containing a short marker. The mod hides these
messages on the receiving end (and your own echo), so nobody sees them. Anyone without the mod
on the receiving end would see the raw marker text, so only add teammates who have the mod.

## Building
Push to GitHub: the **Build** workflow runs automatically. Download the jar from the run's
*Artifacts*. Push a tag like `v1.0.0` to publish it under *Releases*.
Locally: install JDK 21 + Gradle 8.12, then `gradle build` (jar in `build/libs/`).
