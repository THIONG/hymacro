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
| `/hymacro pests <true\|false>` | Mark pests in red, with a line to each |
| `/hymacro pests scan` | What is around you, and what it is called |
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

## Marking pests

A pest is the one thing that reliably ruins a run, and it is hard to see: small,
the colour of the ground, and usually behind a wall of wart. So they are found
wherever the client has been told about them and drawn in red.

The two halves of that drawing answer different questions, and are drawn
differently on purpose.

The outline answers *which of these is the pest*, and is depth tested like
anything else in the world. A wall hides it exactly as the wall hides the mob,
so a pest turns red as it comes into view and nothing appears on screen that was
not already there.

The line from the player to it answers *where is it*, which is the part that is
actually hard, and is drawn through terrain. Knowing a pest exists is no use
without a direction to walk in, and a direction you can only see once you have
already found the thing is not a direction. The label is the same answer in
numbers, for when the line is nearly end on.

Marking belongs to the book rather than to a route. It is about the ground you
are standing on, not the path you are walking, and loading another macro should
not change whether you can see what is eating your crops.

### Finding one

A pest is recognised by the name the server gives it, matched on whole words: a
player called Piratebay is not a rat, and the cost of getting that wrong is a red
box on somebody's back for the rest of the session.

The name is sometimes on the mob and sometimes on an invisible marker floating
over it, and which one it is has never been ours to decide. So a named thing
with no body worth drawing a box around is taken as a label, and the nearest
body under it is marked instead. A box around a nametag is a box around nothing.

There are fifteen of them, one per crop, plus a Field Mouse that eats anything
and a Lunar Moth that takes any of the three flowers. Two names contain another
— a Lunar Moth is a moth by the letters and something else entirely by the loot,
and a dragonfly is not a fly — so the longest match wins rather than the first.

The list will go out of date the day a new pest is added, and it is a list of
names on a wiki rather than of names seen in game.
`/hymacro pests scan` prints what is around you and what the server calls it,
nearest first, marking the ones that would be outlined. An unmarked pest is then
a name to add rather than a mystery.

### Ids rather than entities

What is kept between ticks is entity ids. Holding the mobs themselves would hold
the world they belong to, and a mod that quietly keeps every world you have
visited is a leak with no symptom until the game stops. An int cannot do that,
however badly the rest of it goes wrong.

### Four times a second, not a hundred

Searching the world and drawing it are different jobs at different rates. The
world is searched four times a second: every entity is looked at, its name read,
and a label matched to a body. Drawing happens on every frame, which is a
hundred times a second for as long as the game is open.

So the search leaves behind a list for the drawing to read, and the drawing
reads it rather than doing the search again. Nothing is allocated per frame to
say what to draw, and the pests are found by looking their ids up against the
world instead of walking it: there is normally one pest and there can be
hundreds of entities.

### Where it is, not where it was

A mob moves twenty times a second and is drawn a hundred, and the game covers
the gap by rendering it part of the way between its last two positions. An
outline built from the tick position alone therefore sits on where the mob was
rather than where it is, which on anything that moves reads as the box lagging
behind and shuddering while the mob glides.

The outline asks for the same interpolated position the mob itself is drawn at
and carries the collision box there, so the box is on the mob at whatever frame
rate the game is running.

A pest that dies between two searches is drawn for at most a quarter of a
second: the id stops resolving to anything, and a mark with no entity behind it
is skipped rather than drawn on its last known spot.

## Hunting rather than routing

The hunt is its own key and its own class, not a leg of a macro. A route is a
shape walked over and over, so it is worth recording; a pest is somewhere new
every time and there is nothing to record. They also never want to run at once,
since both work the movement keys and right click every tick, so starting a hunt
stops the macro and says so.

The vacuum reaches ten blocks and a plot is ninety six across, so most of the
work is getting there. Inside seven blocks it stops, finishes turning, and holds
the trigger. Seven rather than ten leaves room for a pest that drifts.

### Over things rather than around them

Flying, the way there is climb, cross, come down. Flying over an obstacle is
worth more than the cleverest way around one: there is nothing to work out, and
nothing to get wedged against. The barn, a plot wall and somebody's build are
all the same problem and all have the same answer.

How high comes from the world itself. Every column knows how tall it is, so the
line to the pest is sampled every few blocks and the crossing goes above the
worst of it with six blocks to spare, but no higher: climbing to the sky for a
pest across a flat plot would spend the whole trip going up and coming back
down. It climbs before it sets off, so the first thing it does is not fly into
the wall it was about to clear.

That height is how tall the world is, not how tall what is standing on it is. A
fence, a sign or a mob is not in it. So getting no closer for ten seconds adds
four blocks and starts again, up to twenty four: a recovery that needs no map
and no idea of what the obstacle was. Only when it runs out of room does it give
up, and then it says so, because a hunt that quietly stops looks exactly like
one that is working.

Direction and height are worked separately because the game works them
separately: flying forward is flat, and where the camera points only decides
which way, never whether up or down. Height is jump and sneak. That split is
also what leaves the aim free, since the camera is busy pointing at the pest and
cannot be spent on steering.

Whether any of this happens is not a setting. It asks whether the player is
flying, and on foot does what it always did: face the pest and hold forward.

It hunts remembered pests as well as live ones. Walking to where one was last
seen is right either way: it either comes back into range and gets vacuumed, or
arriving is exactly what proves it gone and drops the memory.

### Nothing counts how long it has vacuumed

The trigger goes down when the aim is on the pest and comes up when the pest is
no longer in the world. A timer would have to guess, and guessing short leaves a
pest on half health while the hunt walks away.

That makes the aim tolerance matter in a way it otherwise would not. A pest that
shuffles a degree out of line must not blink the trigger off and on, so starting
is fussy — eight degrees — and continuing is not: twenty. Vacuuming survives the
target moving; it ends when the target is gone.

Walking is the one part that can hang, so it is the one part with a clock: ten
seconds of getting no closer means a wall in the way, and it stops and says so
rather than holding forward into it forever.

## What is built every frame

The world drawing runs on every frame, for as long as a macro runs, which is
hours. Anything built there is built a hundred times a second.

Three things were being rebuilt that never change. The gizmo styles are pure
description, so they are made once now rather than three times per point per
frame. A leg's caption is a handful of string joins, so it is worked out once
and kept on the waypoint: a waypoint is replaced rather than edited, so a label
kept there cannot end up describing something the point no longer does.

The arrowheads are the expensive part, up to forty a leg, each of them two
points and a shape. Beyond a hundred and twenty eight blocks an arrow is a
couple of pixels and says nothing the line under it does not, so past that the
line is drawn and the heads are not. Nothing that could be read disappears.

None of this was a leak in the sense of something held forever. It was rubbish
made faster than it was needed, which looks the same from the outside: a heap
that climbs all session and a collector that keeps interrupting the frame.

## Reading somebody else's code

`/hymacro import` decompresses whatever is on the clipboard, and gzip expands.
A few hundred bytes of zeros come out as gigabytes, and reading it whole would
have taken the game down before any of the field by field checking ever ran. So
the unpacking stops at a megabyte and says so. The largest macro this mod can
hold, 512 points with everything set, is a fraction of that, so the limit
refuses only what was never a macro.

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

## How close is arrived

A leg ends within a radius of its point, and that radius used to be a whole
number of blocks for the whole macro, the smallest being one.

One block is far too loose where the direction changes. Stopping a block short
of a corner and turning puts every swing of the next leg one column off, which
looks like the mining being wrong rather than the stopping being early.

Precision is also not wanted evenly. Most of a row can end a block out without
anyone noticing, so a single figure for the whole macro has to be as tight as
its fussiest point and makes every other point fussy for nothing.

The radius now takes decimals, and `/hymacro leg <n> radius 0.3` sets one leg
alone. The steering is left exactly as it was: biasing it towards the facing
would fix a corner and break every macro that turns the other way.

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

## Hunting across the Garden

Entities exist only near you, so a pest three plots away cannot be looked for.
It can be travelled to: the tab list carries `Plots: 4, 5, 12, 15, 21` and
`Alive: 7` for pests the client has never been sent as things in the world.
That is the whole trick. The text says where, the entities say what.

Those lines are read by their shape rather than by their position, since the tab
list arrives as an unordered collection and anything counting lines or reading
under a heading would work right up until it quietly did not.

Turning a plot number into somewhere to fly needs the Garden's spiral, which the
game never tells the client. Written out from what the plot menu looks like, it
was wrong in twelve of its twenty five squares. The one in `GardenPlots` is
[SkyHanni's](https://github.com/hannibal002/SkyHanni), which has had it right for
years.

Two commands existed to answer those questions while this was being built: one
printed every place the server writes text, the other checked the grid against
the plot the server said you were standing on. Both were scaffolding. They found
the tab list and they proved the table, and keeping them afterwards would be
leaving the scaffolding up: `/hymacro pests` now answers what is worth knowing,
which is how many are about, in which plots, and which one you are on.

Standing in a named plot and finding nothing is not a failure, and it took two
goes to stop treating it as one. The first version skipped whichever plot you
were standing in, reasoning that being there and seeing nothing meant nothing
was there. That is true a minute after arriving and false at the instant of it,
which is the only instant it was ever asked: the hunt landed, wrote the plot
off, and left for the next one.

A plot is ninety six across and the server sends only what is near you, so its
far corners cannot be seen from the middle of it. It now stands in the middle
and then in each of the four quarters, five seconds apiece, and only calls a plot
empty once all five have shown nothing. When every plot has come up empty the
slate is cleared, because a pest that moved is not a pest that is gone.

## Finding a way through

Flying straight at a pest works until something is between the two of you, and
the things that get in the way are not one shape. A roof built so pests spawn on
top of it. A wall around a plot. The gaps between rows of cocoa. Each was met
with a rule, and each rule needed the next one: fly over it, unless there is a
ceiling, unless the pest is above the ceiling, in which case go sideways until
the sky is open.

The rules were answering a question they could not hold. It searches now: an
ordinary A* over blocks, which does not care which of those shapes it is looking
at.

Two things keep it honest inside a running game. It searches a box around the
two ends rather than the world, so the cost has a ceiling however far off the
pest is; and it stops after six thousand blocks looked at, so a sealed room
costs a known amount and then hands back nothing rather than hunting for ever.

A plot on the far side of the Garden is further than any box worth searching, so
what is searched for is a point along the way, as far ahead as it plans, worked
out again as it gets there. Skipping the search when the goal was far was the
same as having none, since being far away is exactly when there is a roof in the
way.
When it finds nothing, what happens is the climbing and crossing that came
before, and a pest that truly cannot be reached still ends in giving up out
loud.

Diagonal steps check the straight neighbours too, because two blocks meeting at
an edge leave a gap that is a gap on paper and a wall in the game. What comes
back is corners rather than steps: the staircase A* returns is pulled straight
wherever one corner can see the next, so long runs are flown as one line and
only the turns that matter are turned.

Height is worked by jump and sneak rather than by pointing the camera, because
flying forward in this game is flat: where you look decides which way, never
whether up or down.

Getting to height comes before setting off, so that the crossing happens over
the obstacle rather than into it. That reasoning has a hole in it: a height that
cannot be reached never arrives, and the forward key stays off for ever. It
showed up as the hunt rising to a ceiling and staring at a pest it could see,
because hovering three blocks above one is impossible in a room three blocks
tall.

Waiting is now only worth it while the climb is working. Half a second of
holding jump without rising is taken as an answer: this is as high as it goes,
get on with it.

## Sticking to one pest

The target used to be worked out fresh every tick, always the nearest. That
reads as a virtue: one that dies or one that turns out to be closer costs
nothing to switch to.

It costs everything when two are a similar distance away. Each step the player
takes swaps which is nearest, the aim turns towards whichever won this tick, and
the camera swings between the two and reaches neither. Two pests in one plot is
ordinary, so this was not a corner case.

It now stays on the one it picked until that pest is gone or another is nearer
by eight blocks. Finishing the one it chose beats being right about which was
closest.

## Being interrupted

A macro can be told to watch for something and act on it:

```
/hymacro when pests 3 hunt          pause, clear them, carry on
/hymacro when pests 3 send /warp garden
/hymacro when pests 3 stop
/hymacro when off
```

Three separate things on purpose: what is watched, the number that sets it off,
and what happens next. Pests are the first thing worth watching, not the only
shape this can ever have, so a second one later is a name in a list rather than
a rewrite.

It fires on the way past the number rather than for as long as it is above it.
A rule that fired every tick would stop, hunt, resume and stop again before the
macro took a step.

Coming back from a hunt starts at leg 1, from wherever the last pest was, with
no check of where that is. Pressing play checks, because a macro started far
from its beginning is usually the wrong macro; a hunt ending in a field is
exactly the case that check would wrongly refuse. Leg 1 is the way back to point
1, which is what it is for, so setting this up says so when leg 1 cannot walk
itself.

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
