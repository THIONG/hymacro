# The Fabric mod

The executable drives the game through `SendInput`, which feeds the global
Windows input queue. That queue always goes to the foreground window, so
Minecraft has to stay in front and the computer cannot be used for anything else
while it runs.

The mod removes that. It runs inside the game process, so there is no operating
system input to route anywhere: it sets the state of the keys the game already
reads every tick, and sends commands through the player's own connection. Window
focus stops being part of the problem.

The source is in [`mod/`](../mod), built by [`mod.yml`](../.github/workflows/mod.yml)
and released by [`mod-release.yml`](../.github/workflows/mod-release.yml).

## Building a route

A route is a list of points. A leg is the stretch between one point and the next,
and it carries the work to do along the way.

```
stand on the first block   /hymacro point
stand on the second block  /hymacro point
                           /hymacro hold d
                           /hymacro spam attack 4
                           F9
```

| Command | Effect |
|---------|--------|
| `/hymacro` | The whole list, in colour, in game |
| `/hymacro new <name>` | Start a macro |
| `/hymacro point` | Marks where you stand, facing where you face |
| `/hymacro hold <key>` | Hold a key on the way to the last point |
| `/hymacro spam <key> [ticks]` | Click it over and over |
| `/hymacro once <key>` | Click it once as the leg starts |
| `/hymacro look <yaw> <pitch>` | Aim that leg by numbers instead of by standing |
| `/hymacro move [n]` | Put that point where you stand |
| `/hymacro anchor [n]` | Carry the whole macro so that point lands on you |
| `/hymacro undo`, `clear` | Drop the last point, or start over |
| `/hymacro radius <blocks>` | How close counts as arrived |
| `/hymacro send <text>` | Put in chat on arriving at that point |
| `/hymacro show <true\|false>` | Draw the macro in the world |
| `/hymacro play`, `stop` | Also on F9 and F12 |

Actions land on the leg you just made, which is what you mean while walking a
macro out. Any of them can name a leg instead, because a mistake on leg two
should not need undoing back from leg six:

```
/hymacro leg 3               what leg 3 does
/hymacro leg 3 hold w        change it
/hymacro leg 3 clear         make it only walk
```

A leg's number is its end point's: leg 3 runs from point 2 to point 3. Leg 1 is
the one that closes the loop, from the last point back to the first, which is
also the leg a warp lands in.

A key is worked in one of three ways, and all three take a mouse button as
readily as a key: `attack` is left click, `use` is right click.

| Mode | What it does |
|------|--------------|
| `hold` | Down for the whole leg |
| `spam` | Down and up every few ticks, an autoclicker |
| `once` | A single click as the leg begins |

A point can only be marked once a macro exists. A point outside one means
nothing, and inventing an unnamed macro to hold it just hides the mistake.

## Seeing it rather than reading it

The macro is drawn where it happens. A box stands on every point with its number
above it and its leg's work under that, and arrows run along the ground pointing
the way it travels.

Arrows rather than a plain trail, because direction is the one thing a still
picture of a route cannot otherwise say, and it is the first thing anyone wants
to know. They are drawn in short repeated steps so a hundred block row reads as
flow rather than as one enormous arrowhead.

The leg from the last point back to the first is drawn faintly. It is real, the
macro loops, but it is the way back rather than more of the same work. When the
last point warps it is left out entirely, because then it is not walked at
all.

Colour carries the same answer at a distance the text cannot be read from:

| Colour | Meaning |
|--------|---------|
| Green | A key is held for the whole leg |
| Orange | Something is clicked repeatedly |
| Grey | Nothing set yet, just walking |

There is no command to print the route, because a list of coordinates is a poor
way to answer *where does this go*. Standing on the plot and looking at it is
the better one.

## Several routes

Plots differ, and a route built for one shape is wrong for another. Routes are
kept by name in `config/hymacro-routes.json`, one of them current at a time.

| Command | Effect |
|---------|--------|
| `/hymacro list` | List them, marking the current one |
| `/hymacro load <name>` | Switch to a saved one |
| `/hymacro rename <name>` | Rename the current one |
| `/hymacro delete <name>` | Remove one |

Everything is written as it changes, so there is no save step. A single route
left by an earlier version is carried over under the name `default`.

## Sending one to someone

`/hymacro share` puts the current macro on the clipboard as one line, and
`/hymacro import <name>` reads one back. `/hymacro share <name>` sends one you
are not on, so it need not be loaded only to be sent. Wherever a command names
an existing macro the name completes, since remembering exactly what you called
something is a poor thing to ask.

It travels as a code rather than as its own JSON because chat takes 256
characters and a macro of ten points is thousands. Compressed and encoded, the
same macro fits in a single message anywhere.

### Importing what a stranger wrote

Everything past the marker is somebody else's data, so none of it is trusted.
The code carries a format number and one from a later format is refused by name
rather than failing as corrupt. Beyond that the macro itself is checked field by
field: a point without coordinates, a coordinate that is not finite, an action
without a key, a list that is not a list, a claim of more points than any plot
could hold. Each is a sentence saying what is wrong. None of it may throw out of
a command, so the parse converts anything unexpected into the same kind of
message.

The part worth more than the parsing is what the macro will do. A shared macro
can type into your chat, which is somebody else's commands running under your
name. Import lists every line it would send, in red, before you ever press F9.

## Why a macro stays selected

Creating one selects it, and the one you were on is restored when the game
starts. Making you load a macro you just made is ceremony, and losing your place
on every restart costs more than it protects.

What that risks is playing the wrong macro after a restart, which deselecting
would not really fix either: loading the wrong one has the same ending. So the
check is where the damage would happen. F9 measures how far you are from point 1
and refuses if you are somewhere else entirely, because a macro started away
from its beginning holds keys towards a point it is not walking to and arrives
nowhere until it times out. Pressing F9 again within five seconds starts it
anyway, for when you meant it.

## Moving what is already built

A macro is a shape and a place, and the two come apart.

`/hymacro move 2` puts one point where you stand, keeping what it does and
where it faces. Rebuilding because a point sits a block out is a poor trade, and
undoing back to it throws away everything after it.

`/hymacro anchor` carries every point at once so that point 1 lands on you.
Another plot of the same shape is the same macro somewhere else, which should be
a move rather than an afternoon of remarking points. `/hymacro anchor 3` pivots
on point 3 instead, for when that is the corner you can find again.

Both leave the shape and the facing alone. Anchor reports the offset it applied,
so standing where the pivot used to be and anchoring again puts it back.

## Why sending is per leg

Warping used to be a property of the whole route, fired at the end of a lap.
That was one use of a general thing dressed up as its own feature, and being
tied to the end of a lap meant it could not happen anywhere else.

A point can now send a line of chat on arrival: `/hymacro send /warp garden`
does what the warp setting did, and `/hymacro leg 3 send hello` does what it
could not. A leading slash makes it a command, anything else is said out loud.

Sending is followed by a short pause, because a warp needs a moment to land and
starting the next leg mid teleport would hold keys wherever it came out. An old
`warpCommand` is carried over onto the last point, which is where it fired.

## Walking itself there

`/hymacro walk` makes a leg steer towards its point instead of holding a fixed
direction.

Holding a key and hoping works until something knocks the player off line. From
then on the key points somewhere the destination is not, and the leg arrives
nowhere until it times out. Steering works the keys out every tick, so a leg
corrects itself.

It steers by how far it has strayed from the line between the two points,
rather than by where the point lies from here. A keyboard expresses eight
directions and no more, so aiming straight at a point that is mostly sideways
presses forward as well and leaves at forty five degrees, wandering off the line
and curving back onto it. Holding the line keeps that to the width of the drift
it tolerates, about a third of a block.

The look direction is deliberately left alone. On a wall of crops the player
faces the wall and travels sideways, so turning to face the way they are walking
would aim the tool at nothing. Everything else still applies: the leg keeps its
yaw and pitch, and keeps spamming whatever it was told to.

It is off by default. Holding a key is what the macro has always done, and a
route built around it should not change under its author.

## Why legs end on arrival

The first version of the mod, and the executable before it, ran on a stopwatch:
hold a key for a measured number of seconds. That needs calibrating, and it
drifts. A tenth of a second of error is a wall out of position eight rows later.

A leg now ends when the player reaches its point. There is nothing to measure and
nothing to drift: falling short simply means holding the keys a moment longer.
Speed, buffs, lag and being shoved by a mob all stop mattering.

A leg that never arrives has to end somehow, because something wedged against a
block or teleported away must not hold a key down forever. That was a fixed
budget at first: ninety seconds, then give up.

It was the wrong measure. A budget cannot tell a long row from a stuck player,
and a row of nether wart honestly runs two minutes, so every one of them was cut
short at the ninety second mark. The keys released mid row and the macro carried
on to the next leg, which looked like the mining simply stopping.

What matters is not how long a leg takes but whether it is still getting
anywhere. A leg is now given up on only after twenty seconds of getting no
closer, which a long row never triggers and a wedged player triggers at once.
`/hymacro stall <seconds>` moves it.

Giving up also says so in chat. It used to go to the log, where nobody reads it
mid game, so the macro appeared to stop mining for no reason at all.

## Why declaring rather than recording

An earlier version recorded a route by watching the player walk it, working out
from the pattern of presses whether a button was held or spammed.

Declaring is better. A recording captures the hesitations along with the intent,
a single leg cannot be changed without walking the whole route again, and the
hold-or-spam guess could simply be wrong. Position still has to be captured by
standing somewhere, since there is no other way to name a spot, but everything
else is stated outright.

## Why the look direction is part of a leg

On a wall of crops the player faces the wall while moving sideways. A camera off
by a degree ruins a run as surely as a mistimed key, so yaw and pitch are
recorded with the point and restored when the leg begins.

Standing somewhere captures both at once, which is enough most of the time. When
it is not, `/hymacro look <yaw> <pitch>` sets them outright: a degree is finer
than anyone can hold a mouse, and the game already shows the exact numbers on
the debug screen. Marking a point prints what it captured, so the value to
adjust from is in front of you.

The direction belongs to the leg ending at that point, not to the point itself.
It is applied when the leg begins and held for the whole of it.

## Toolchain, verified against the official example mod

The Fabric example mod has a branch per Minecraft version, and its `26.1.2`
branch is the authority on how a build is declared.

| Item | Value |
|------|-------|
| Minecraft | 26.1.2 |
| Java | 25 |
| Fabric Loader | 0.19.3 |
| Fabric API | `0.155.2+26.1.2` |
| Loom | `1.17-SNAPSHOT`, resolving to 1.17.19 |
| Mappings | **none declared** |

That last row was the question that blocked the whole thing. Neither Yarn, which
stopped at 1.21.11, nor Mojang's published mappings, absent from the 26.1.2
metadata, cover this version. It turns out neither is needed: Loom handles naming
itself on 26.x, so the dependency block has no `mappings` line at all. Fabric
dependencies also use `implementation` rather than the `modImplementation` older
guides call for.

## What was given up, and why

The keys are not rebindable from the game's controls screen, and there is no
chat feedback outside of commands. A binding needs a category type whose shape
changed in this version, and `displayClientMessage` is not on `LocalPlayer` here.
Both were dropped to get the mod compiling at all: the first build had nine
errors, and cutting the Minecraft API surface down to what the compiler had
already confirmed took it to one.

Markers were particles at first for the same reason, since drawing a box used to
mean driving the render pipeline, which changes more between versions than
anything else and is the part this version publishes nothing to check against.

That turned out not to be the trade any more. 26.1.2 ships a gizmo system,
`net.minecraft.gizmos.Gizmos`, meant for exactly this: `cuboid` takes a box and
a style, `billboardTextOverBlock` writes in the world, and `setAlwaysOnTop`
draws through terrain. A point is two calls rather than twelve lines of matrix
arithmetic, and it rests on the API the game maintains for drawing debug shapes
instead of on the renderer's internals.

## Finding an API with no mappings

Three classes the drawing needed had moved, and there is nothing to look them up
in. Guessing a name costs a full round trip through CI, so the build was turned
into an instrument instead: it searches the jars it has already downloaded,
lists whole packages, and prints the members of a named class into the report.

That answered every question in four rounds:

| Was | Is |
|-----|----|
| `rendering.v1.WorldRenderEvents` | `rendering.v1.level.LevelRenderEvents` |
| `WorldRenderContext` | `level.LevelRenderContext` |
| `client.renderer.RenderType.lines()` | `client.renderer.rendertype.RenderTypes.lines()` |
| `LevelRenderer.renderLineBox` | gone; `Gizmos.cuboid` replaces it |

## Keeping the build short

Two things made a release slow, both of them work already done.

The release workflow rebuilt the commit CI had just built, paying for a second
cold Loom run to produce a jar that already existed. It now looks up the Mod
run for the same commit and downloads its jar, and only builds when there is
nothing to download.

The other is that `setup-gradle` caches dependency modules but not Loom's own
cache, which holds the remapped Minecraft. That is the expensive part of a cold
build, so it is now cached explicitly, keyed on the versions in
`gradle.properties` that decide its contents.

## Reading a failed build

Actions logs need a token to fetch, so a red badge alone says nothing about what
broke. On failure the workflow writes the compiler output to
[`mod/reports/last-build.txt`](../mod/reports/last-build.txt) and commits it,
where it can be read over plain HTTP. That loop is what took the build from nine
errors to zero without a JDK anywhere nearby.
