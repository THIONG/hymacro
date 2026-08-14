# Running unattended: notes on a Fabric mod

HyMacro drives the game through `SendInput`, which feeds the global Windows input
queue. That queue always goes to the foreground window, so Minecraft has to stay
in front and the computer cannot be used for anything else while the macro runs.

This document records what was investigated about solving that with a mod, so the
work does not have to be repeated.

## What was tried and rejected

**Posting messages to the game window** (`PostMessage` with `WM_KEYDOWN` and
friends, aimed at the Minecraft window instead of the global queue). This was
implemented and tested. The messages are accepted by Windows and the window is
found correctly, but Minecraft does not act on them while it is unfocused. It was
removed in 1.2.0 rather than kept as an option that promises something it cannot
deliver.

**A virtual machine.** Reliable in principle, but modern Minecraft needs OpenGL
3.2 or newer and a VM without GPU passthrough does not provide it. For 26.1.2 the
game is unlikely to start at all.

**A second computer.** Works, needs a second computer.

## Why a mod solves it

A mod runs inside the game process, so there is no operating system input to
route anywhere. Instead of simulating a key press it sets the key state the game
already reads every tick, and sends commands through the player's own network
connection. Window focus stops being part of the problem.

The same rules apply either way: automating gameplay is automating gameplay,
whether it is driven from outside the process or inside it.

## Toolchain, verified 2026-08-14

| Item | Value | Source |
|------|-------|--------|
| Minecraft 26.1.2 | release | Mojang version manifest |
| Java required | **25** (`java-runtime-epsilon`) | version metadata |
| Fabric Loader | 0.19.3 | `meta.fabricmc.net` |
| Fabric API | `0.155.2+26.1.2` | Modrinth, 1.7M downloads |
| Yarn mappings | **none for 26.x**, newest is 1.21.11 | `meta.fabricmc.net` |
| Mojang mappings | **no `client_mappings` entry** | version metadata |

The last two rows are the open question. Fabric API having over a million
downloads on this version proves the ecosystem builds against it, so a mapping
scheme exists; it just is not either of the two obvious ones. Resolving that is
the first task, and it has to be done on a machine with a JDK, by generating a
scaffold with Fabric's own tooling and reading the build file it produces.

## Shape of the mod

Client side only. Four keybinds registered through Fabric so they show up in the
game's own controls screen and can be rebound there.

The route is a state machine driven by **client ticks rather than wall clock**.
Ticks run at a fixed 20 per second regardless of frame rate, so 120 seconds is
2400 ticks and the timing cannot drift with performance. Movement works by
setting the state of the key mappings the game already reads, which means the
packets leaving the client are the same ones a player produces.

Configuration should reuse the field names this project already uses
(`forward_seconds`, `return_seconds`, `step_seconds`, `routes_per_warp`, `keys`)
so calibrated values carry across without translation.

The focus and mouse failsafes stop being meaningful, since the whole point is
that the window is not in front and the mouse is in use. The stop key and the
session limit still are.

## Build and release

Same shape as the executable pipeline in this repository: a CI job that compiles
on every push, and a release job on a tag that checks the declared version
matches the tag, publishes the jar from `build/libs` excluding the `-sources` and
`-dev` variants, and attaches a SHA256.

If the mod lives in this repository it needs its own tag prefix, so a jar release
and an executable release do not trigger each other.

## What is not done

Nothing of the mod is written. It needs an environment with a JDK to compile
against, which is also what is required to answer the mappings question. Writing
Java against an API whose names cannot be checked would produce code that does
not build and cannot be verified.
