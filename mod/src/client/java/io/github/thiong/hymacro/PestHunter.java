package io.github.thiong.hymacro;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Walking up to pests and vacuuming them, one at a time.
 *
 * <p>Separate from the macro on purpose, and on its own key. A route is a shape
 * you walk over and over; a pest is somewhere new every time, so there is
 * nothing to record and the two never want to be running at once. They would
 * also be fighting over the same keys.
 *
 * <p>The vacuum reaches ten blocks and a plot is ninety six across, so most of
 * the work is getting there. In the air that is climb, cross, come down: get
 * above everything in the way, fly over it, and settle onto the pest. Flying
 * over an obstacle is worth far more than the cleverest way around one, because
 * there is nothing to work out and nothing to get wedged against. The path is
 * only as high as it has to be, read from the ground under the line it takes.
 *
 * <p>On foot it does what it did before, which is face the pest and hold
 * forward. Which of the two it is doing is not a setting: it asks whether the
 * player is flying.
 *
 * <p>It hunts what {@link Pests} has found, including the ones only remembered.
 * Walking to where one was last seen is right whether or not it is still there:
 * either it comes back into range and gets vacuumed, or arriving is what proves
 * it gone and drops the memory.
 */
public final class PestHunter {
	/** The vacuum is in the first slot, which is slot zero to everything here. */
	private static final int SLOT = 0;

	/**
	 * How close before it stops walking.
	 *
	 * <p>The vacuum reaches ten. Stopping at seven leaves room for a pest that
	 * drifts and for the moment between deciding and standing still.
	 */
	private static final double REACH = 7.0;

	/** Degrees a tick, so the camera turns rather than snaps. */
	private static final float TURN = 18.0f;

	/** How near the aim has to be before the trigger is worth holding. */
	private static final float AIMED = 8.0f;

	/**
	 * How far the aim may wander before the trigger is let go again.
	 *
	 * <p>Wider than what it takes to start, on purpose. Nothing here counts
	 * seconds of vacuuming: the trigger is held until the pest is gone from the
	 * world, because that is the only thing that actually says it is dead. A
	 * pest that shuffles a degree out of line must not blink the trigger off and
	 * on, so starting is fussy and continuing is not.
	 */
	private static final float STILL_AIMED = 20.0f;

	/** Getting this much closer counts as progress and clears the stall. */
	private static final double PROGRESS = 0.5;

	/**
	 * How much nearer another pest must be before it is worth abandoning this one.
	 *
	 * <p>Without it, two pests a similar distance away swap places as nearest
	 * every time the player moves, and the aim turns towards whichever won this
	 * tick. The result is a camera swinging between the two and arriving at
	 * neither. Finishing the one it picked is faster than being right about which
	 * was closest.
	 */
	private static final double WORTH_SWITCHING = 8.0;

	/** Ten seconds of getting no closer means something is in the way. */
	private static final int STALL_TICKS = 200;

	/** How far above the tallest thing on the way it crosses. */
	private static final double CLEARANCE = 6.0;

	/** Where it settles to vacuum: above the pest, looking down at it. */
	private static final double HOVER = 3.0;

	/** Near enough overhead to stop crossing and start coming down. */
	private static final double OVERHEAD = 4.0;

	/**
	 * How far it may drift back out before crossing starts again.
	 *
	 * <p>Three of them fly, on a bat underneath, and a bat does not travel in
	 * straight lines. One hovering right on the edge of arriving would otherwise
	 * flip between crossing and settling several times a second, which is
	 * forward tapped on and off and a player that shudders in place.
	 */
	private static final double STILL_OVERHEAD = 7.0;

	/**
	 * How far the vacuum may reach before the trigger is let go.
	 *
	 * <p>It really reaches ten. Closing to seven and holding out to nine and a
	 * half means a pest that wanders while being vacuumed keeps being vacuumed,
	 * instead of the trigger blinking every time it crosses the line it was
	 * caught on.
	 */
	private static final double KEEP_REACH = 9.5;

	/** Height is near enough when this close, or it hunts up and down forever. */
	private static final double HEIGHT_ENOUGH = 0.6;

	/** Below this much still to climb, it is close enough to set off. */
	private static final double CLIMB_FIRST = 2.0;

	/** Close enough to the ground to stop flying without falling. */
	private static final double LANDED = 1.5;

	/**
	 * How far off the ground it stays while it means to keep flying.
	 *
	 * <p>Touching down cancels flight, which the game does for you and is
	 * ordinarily what you want. It is not what you want mid hunt: the way through
	 * is worked out in blocks and a corner at ground level is a corner on the
	 * floor, so following one exactly meant flying for a second and then walking
	 * the rest of the Garden.
	 */
	private static final double FLY_CLEARANCE = 1.6;

	/**
	 * Ticks the jump key stays up before it may go down again.
	 *
	 * <p>Two taps of jump is how a player tells the game to stop flying, and the
	 * game does not care that a mod did the tapping. Holding it by a threshold
	 * means letting go the moment the height is nearly right and grabbing it
	 * again a tick later, which is a double tap however it was meant. Vanilla
	 * counts seven ticks; this leaves ten.
	 */
	private static final int TAP_GUARD = 10;

	/** Let go only once well past the height, so the key does not chatter. */
	private static final double STOP_RISING = 0.15;

	/** Ticks of holding jump and going nowhere before the height is given up on. */
	private static final int CLIMB_PATIENCE = 10;

	/** Less rise than this in a tick is not rising. */
	private static final double RISING = 0.05;

	/** How often the way through is worked out again, and how near counts as reached. */
	private static final int REPLAN_TICKS = 20;
	private static final double CORNER_REACHED = 1.6;

	/** How often the ground under the way is read, and at most how many times. */
	private static final double SAMPLE_EVERY = 4.0;
	private static final int SAMPLES = 40;

	/**
	 * What it adds to the crossing height when it stops getting anywhere.
	 *
	 * <p>The ground being read says how tall the world is, not how tall what is
	 * standing on it is. When something turns out to be in the way anyway, going
	 * higher is the answer that needs no map, and it either works or it runs out
	 * of room and says so.
	 */
	private static final double MORE_LIFT = 4.0;
	private static final double MOST_LIFT = 24.0;

	private final Pests pests;

	/** Near enough into a plot to be told about what lives there. */
	private static final double PLOT_ARRIVED = 20.0;

	/** How long to wait at one spot in a plot before moving along it. */
	private static final int SETTLE_TICKS = 100;

	/**
	 * Where to stand in a plot, in order.
	 *
	 * <p>A plot is ninety six across and the server only sends what is near you,
	 * so its far corners are outside what standing in the middle can see. The
	 * pest is somewhere in it; this walks the middle and the four quarters until
	 * something is sent, which is what a person does when told a plot has one.
	 */
	private static final double[][] SWEEP = {
		{0.0, 0.0}, {-24.0, -24.0}, {24.0, -24.0}, {24.0, 24.0}, {-24.0, 24.0},
	};

	/** How far ahead it plans when the pest is further off than that. */
	private static final double PATH_AHEAD = 48.0;

	private final java.util.Set<Integer> emptyPlots = new java.util.HashSet<>();
	private int settledTicks;
	private int sweepingPlot = -1;
	private int sweepStep;

	private boolean on;
	private double closestYet;
	private int stalledTicks;
	private boolean waiting;
	private boolean firing;
	private boolean closingIn;
	private double lift;
	private int travellingTo = -1;

	/** The one it settled on: an entity id, or a remembered spot. */
	private int lockedId = -1;
	private double[] lockedMark;

	private List<Vec3> path;
	private int pathAge;
	private double lastY = Double.MAX_VALUE;
	private int blockedTicks;
	private boolean jumpHeld;
	private int jumpGuard;

	/** Whether the flying was ours to turn off again afterwards. */
	private boolean weStartedFlying;
	private boolean landing;

	public PestHunter(Pests pests) {
		this.pests = pests;
	}

	public boolean isOn() {
		return on;
	}

	/** On, but with nothing left to go at: the moment a macro may take over. */
	public boolean isIdle() {
		return on && waiting;
	}

	/** Hunting, or still coming down from having hunted. */
	public boolean isBusy() {
		return on || landing;
	}

	/** Starts without the message, for when a macro is doing the starting. */
	public void start() {
		on = true;
		waiting = false;
		landing = false;
		forgetTarget();
		clearStall();
		takeOff(Minecraft.getInstance());
	}

	/**
	 * Flies if it may, and remembers whether that was its doing.
	 *
	 * <p>Whether flight is allowed is not a thing to work out from a cookie or a
	 * bowl of soup. Hypixel grants it, and granting it is a flag on the player
	 * that the client is told about: asking that is asking the game rather than
	 * guessing at the reason.
	 *
	 * <p>Flying is preferred because a pest four plots away is a minute of
	 * walking and a few seconds of flight, and because the way there is over
	 * things rather than round them.
	 */
	private void takeOff(Minecraft client) {
		if (client.player == null) {
			return;
		}
		if (client.player.getAbilities().flying || !client.player.getAbilities().mayfly) {
			weStartedFlying = false;
			return;
		}
		client.player.getAbilities().flying = true;
		client.player.onUpdateAbilities();
		weStartedFlying = true;
		Chat.clientNote("Flying to get round faster. It lands again when it is done.");
	}

	/**
	 * Puts the player back on the ground, then back to walking.
	 *
	 * <p>A macro that walks its route cannot be handed back a player in the air,
	 * and switching flight off up there is a fall rather than a landing. So it
	 * comes down first and only stops flying once there is nothing to fall.
	 */
	private void land(Minecraft client) {
		if (client.player == null) {
			landing = false;
			weStartedFlying = false;
			return;
		}

		boolean low = client.player.getY()
			- groundAt(client, client.player.getX(), client.player.getZ()) <= LANDED;
		if (!client.player.getAbilities().flying || low) {
			if (client.player.getAbilities().flying) {
				client.player.getAbilities().flying = false;
				client.player.onUpdateAbilities();
			}
			Keys.set("shift", false);
			landing = false;
			weStartedFlying = false;
			return;
		}
		Keys.set("shift", true);
	}

	public void toggle() {
		if (on) {
			stop("Stopped hunting.");
			return;
		}
		on = true;
		waiting = false;
		landing = false;
		clearStall();
		emptyPlots.clear();
		settledTicks = 0;
		forgetTarget();
		Chat.client("Hunting pests with slot 1.", false);
		Chat.clientNote("It goes to the plots the tab list names, then vacuums what it finds.");
		takeOff(Minecraft.getInstance());
	}

	/** Releases everything. Stopping must never leave the trigger held. */
	public void stop(String why) {
		boolean wasOn = on;
		on = false;
		waiting = false;
		firing = false;
		closingIn = false;
		lift = 0.0;
		forgetTarget();
		path = null;
		sweepingPlot = -1;
		sweepStep = 0;
		release();
		if (wasOn && why != null) {
			Chat.client(why, false);
		}
		// Walking is how it was found, so walking is how it is handed back.
		landing = weStartedFlying;
	}

	public void tick(Minecraft client) {
		if (landing) {
			land(client);
			return;
		}
		if (!on) {
			return;
		}
		if (client.player == null || client.level == null) {
			stop(null);
			return;
		}

		// Turning flight on while stood on the ground does not stick: touching
		// down cancels it, and it was on the ground when it was told to fly. So
		// it is asserted every tick until it is actually off the floor.
		if (weStartedFlying && !client.player.getAbilities().flying
			&& client.player.getAbilities().mayfly) {
			client.player.getAbilities().flying = true;
			client.player.onUpdateAbilities();
		}

		Vec3 pest = nearest(client);
		Vec3 target = pest == null ? plotToTry(client) : pest;
		boolean chasingPest = pest != null;
		if (chasingPest) {
			// Something turned up, so every plot is worth trying again.
			emptyPlots.clear();
			settledTicks = 0;
		}
		if (target == null) {
			// Nothing to do yet. It stays armed rather than turning itself off,
			// because the next pest is the whole reason it is on.
			firing = false;
			closingIn = false;
			release();
			clearStall();
			if (!waiting) {
				waiting = true;
				Chat.clientNote("No pests in sight. Waiting.");
			}
			return;
		}
		waiting = false;

		if (client.player.getInventory().getSelectedSlot() != SLOT) {
			client.player.getInventory().setSelectedSlot(SLOT);
		}

		boolean flying = client.player.getAbilities().flying;

		// Nothing between here and there: go at it. Something in the way: work
		// out a way through, which handles a roof, a wall and the gaps between
		// rows of cocoa without knowing which of them it is looking at.
		Vec3 corner = flying ? corner(client, target) : null;
		Vec3 heading = corner == null ? target : corner;

		float off = aim(client, heading);
		double away = client.player.getEyePosition().distanceTo(target);
		double flat = flatTo(client, target);

		// Which half of the job it is on. Crossing until it is overhead, and it
		// has to drift well back out before that is undone: three of the pests
		// fly, and one hovering on the line would otherwise flip between the
		// two several times a second.
		closingIn = corner != null
			? false
			: closingIn ? flat <= STILL_OVERHEAD : flat <= OVERHEAD;

		// Far off: get to it. Over the ground between if something is in the way,
		// straight at it if nothing is.
		if (!closingIn) {
			firing = false;
			Keys.set("use", false);
			boolean overTheTop = false;
			if (corner != null) {
				// Following the way through. Forward is flat in this game, so
				// the corner's height is worked by jump and sneak rather than by
				// pointing the camera at it.
				boolean climbing = hold(client, corner.y);
				go(!climbing);
				creep(client.player.getEyePosition().distanceTo(corner), false);
				return;
			}
			if (flying) {
				overTheTop = climbNeeded(client, target);
				// A plot has no height of its own: the one it carries is the
				// player's, read again every tick. Holding three above it would
				// be holding three above wherever the climb had got to, which
				// climbs for ever and never sets off.
				double wantedY;
				if (!chasingPest) {
					wantedY = crossingHeight(client, target);
				} else if (overTheTop) {
					wantedY = Math.max(crossingHeight(client, target), target.y + HOVER);
				} else {
					wantedY = target.y + HOVER;
				}
				boolean climbing = hold(client, wantedY);
				go(!climbing);
				if (climbing) {
					// Going up is not going nowhere. The clock starts once it
					// is actually on its way.
					clearProgress();
					return;
				}
			} else {
				go(true);
				level();
			}
			creep(away, overTheTop);
			return;
		}

		// Overhead. Come down to hovering height and vacuum from above it.
		go(false);
		if (flying && chasingPest) {
			hold(client, target.y + HOVER);
		} else {
			level();
		}

		// Standing in a plot the tab list named, waiting for it to tell us what
		// is in it. Entities arrive a moment after the chunks do, so this waits
		// rather than deciding at once, and gives up on a plot instead of
		// hovering over it forever.
		if (!chasingPest) {
			firing = false;
			Keys.set("use", false);
			clearStall();
			if (flat <= PLOT_ARRIVED && ++settledTicks > SETTLE_TICKS) {
				settledTicks = 0;
				if (++sweepStep >= SWEEP.length && travellingTo > 0) {
					emptyPlots.add(travellingTo);
					sweepStep = 0;
					Chat.clientNote("Nothing found in plot " + travellingTo + ". Trying the next.");
				}
			}
			return;
		}

		// Closes to seven, holds out to nine and a half. Same reasoning as the
		// aim: getting started is fussy, staying on it is not, because what
		// ends a vacuum is the pest being gone and nothing else.
		if (away > (firing ? KEEP_REACH : REACH)) {
			firing = false;
			Keys.set("use", false);
			creep(away, false);
			return;
		}

		clearStall();

		// Fussy about starting, forgiving about continuing. Nothing counts how
		// long it has been vacuuming: the trigger goes down when the aim is on
		// the pest and comes up when the pest is no longer in the world, which
		// is the only honest sign that it is dead.
		firing = firing ? off <= STILL_AIMED : off <= AIMED;
		Keys.set("use", firing);
	}

	/**
	 * Works the two keys that change height, and says whether it is worth still
	 * waiting to get there before setting off.
	 *
	 * <p>Flying forward in this game is flat: where the camera points decides
	 * which way, never whether up or down. Height is jump and sneak, and it is
	 * left to this so that aiming stays free to point at the pest.
	 *
	 * <p>Waiting is only worth it while the climb is working. Hovering three
	 * above a pest is impossible under a low ceiling, and holding the forward key
	 * off until an impossible height is reached is a player pressing jump into a
	 * roof for ever. Half a second of jumping without rising is taken as an
	 * answer: this is as high as it goes, get on with it.
	 */
	private boolean hold(Minecraft client, double wantedY) {
		double now = client.player.getY();
		double aim = wantedY;
		if (client.player.getAbilities().flying) {
			aim = Math.max(aim,
				groundAt(client, client.player.getX(), client.player.getZ()) + FLY_CLEARANCE);
		}
		double rise = aim - now;
		rise(rise > (jumpHeld ? STOP_RISING : HEIGHT_ENOUGH));
		Keys.set("shift", rise < -HEIGHT_ENOUGH);

		if (rise <= CLIMB_FIRST) {
			blockedTicks = 0;
			lastY = now;
			return false;
		}
		if (now > lastY + RISING || lastY == Double.MAX_VALUE) {
			blockedTicks = 0;
		} else {
			blockedTicks++;
		}
		lastY = now;
		return blockedTicks < CLIMB_PATIENCE;
	}

	/**
	 * The next corner of a way through, or null when straight there will do.
	 *
	 * <p>Worked out afresh a few times a second rather than every tick: the
	 * search is cheap for a plot and not free, and pests do not move far in a
	 * second. A corner is dropped once it is reached, and when the last one goes
	 * the flying is straight again.
	 *
	 * <p>When no way is found at all this hands back nothing, and what happens
	 * next is the climbing and crossing that came before it. A sealed room ends
	 * in giving up either way; this only means it is not tried for ever.
	 */
	private Vec3 corner(Minecraft client, Vec3 target) {
		Vec3 eye = client.player.getEyePosition();
		if (clear(client, eye, target)) {
			path = null;
			return null;
		}
		// A plot is hundreds of blocks off, which used to mean no searching at
		// all and straight back to climbing into whatever was overhead. It plans
		// as far ahead as it can instead, and plans again as it gets there.
		Vec3 goal = target;
		double away = eye.distanceTo(target);
		if (away > PATH_AHEAD) {
			goal = eye.add(target.subtract(eye).normalize().scale(PATH_AHEAD));
		}

		while (path != null && !path.isEmpty()
			&& eye.distanceTo(path.get(0)) < CORNER_REACHED) {
			path.remove(0);
		}
		if (path != null && path.isEmpty()) {
			path = null;
		}
		if (path == null || ++pathAge >= REPLAN_TICKS) {
			pathAge = 0;
			path = Flightpath.between(client.level, eye, goal);
		}
		return path == null || path.isEmpty() ? null : path.get(0);
	}

	/**
	 * Whether getting there means going over something.
	 *
	 * <p>Climbing above the world is right in the open and wrong indoors. The
	 * height comes from the heightmap, which inside a building is the roof, so
	 * the crossing aims for a point on the far side of the ceiling, never
	 * arrives, and the answer to being stuck was to climb further into it.
	 *
	 * <p>Two things say not to climb: a clear line to the pest, which means there
	 * is nothing to climb over, and a ceiling overhead, which means there is
	 * nowhere to climb to.
	 */
	private static boolean climbNeeded(Minecraft client, Vec3 target) {
		Vec3 eye = client.player.getEyePosition();
		if (clear(client, eye, target)) {
			return false;
		}
		return clear(client, eye, eye.add(0.0, CLEARANCE, 0.0));
	}

	/** Whether nothing solid stands between two points. */
	private static boolean clear(Minecraft client, Vec3 from, Vec3 to) {
		return client.level.clip(new ClipContext(
			from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player))
			.getType() == HitResult.Type.MISS;
	}

	/**
	 * How high to cross, read from the ground along the way.
	 *
	 * <p>The barn, a plot wall and a treehouse are all the same problem, and
	 * flying over all of them needs no idea of what any of them are. The world
	 * already knows how tall it is at any column, so the line to the pest is
	 * sampled every few blocks and the crossing goes above the worst of it.
	 *
	 * <p>Only as high as it has to be. Climbing to the sky for a pest across a
	 * flat plot would spend the whole trip going up and coming back down.
	 *
	 * <p>Read from the world and nothing else. It used to be floored at the
	 * target's own height, which is right for a pest and ruinous for a plot,
	 * whose height is the player's read afresh each tick: the floor rose with
	 * every block climbed and the climb never ended.
	 */
	private double crossingHeight(Minecraft client, Vec3 target) {
		double px = client.player.getX();
		double pz = client.player.getZ();
		double dx = target.x - px;
		double dz = target.z - pz;
		double flat = Math.sqrt(dx * dx + dz * dz);

		int steps = (int) Math.min(SAMPLES, Math.max(1.0, flat / SAMPLE_EVERY));
		int highest = groundAt(client, px, pz);
		for (int i = 1; i <= steps; i++) {
			double along = (double) i / steps;
			highest = Math.max(highest, groundAt(client, px + dx * along, pz + dz * along));
		}
		return highest + CLEARANCE + lift;
	}

	private static int groundAt(Minecraft client, double x, double z) {
		return client.level.getHeight(
			Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
	}

	private static double flatTo(Minecraft client, Vec3 target) {
		double dx = target.x - client.player.getX();
		double dz = target.z - client.player.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	/**
	 * Getting no closer, and what to do about it.
	 *
	 * <p>The ground says how tall the world is, not how tall what is standing on
	 * it is: a fence, a sign, somebody's build. So the first answer is to go
	 * higher and try again, which needs no map and no idea of what the obstacle
	 * was. Only when it runs out of room does it give up, and then it says so,
	 * because a hunt that quietly stops looks exactly like one that is working.
	 */
	private void creep(double away, boolean overTheTop) {
		if (away < closestYet - PROGRESS) {
			closestYet = away;
			stalledTicks = 0;
			return;
		}
		if (++stalledTicks < STALL_TICKS) {
			return;
		}
		// Going higher is only an answer when it was going over something to
		// begin with. Under a roof it is the reason it is stuck.
		if (overTheTop && lift + MORE_LIFT <= MOST_LIFT) {
			lift += MORE_LIFT;
			clearProgress();
			Chat.clientNote("Something in the way. Going " + (int) MORE_LIFT + " blocks higher.");
			return;
		}
		stop("Could not get to that pest, stopping.");
		Chat.clientNote("It is walled in, or too far. Move somewhere clearer and press F10 again.");
	}

	/** Starts the clock again without forgetting how high it has had to go. */
	/**
	 * Works the jump key without ever tapping it twice.
	 *
	 * <p>Everything that presses jump goes through here, because the rule it
	 * keeps is about the gaps between presses rather than about any one of them.
	 */
	private void rise(boolean wanted) {
		if (jumpGuard > 0) {
			jumpGuard--;
			wanted = false;
		}
		if (jumpHeld && !wanted) {
			jumpGuard = TAP_GUARD;
		}
		jumpHeld = wanted;
		Keys.set("space", wanted);
	}

	/**
	 * Lets go of the height keys, for when there is no flying to do.
	 *
	 * <p>Landing part way through a hunt would otherwise leave jump or sneak
	 * held from the last tick that was in the air.
	 */
	private void level() {
		rise(false);
		Keys.set("shift", false);
	}

	/** Every key this touches, up. Nothing may be left held. */
	private void release() {
		Keys.set("w", false);
		Keys.set("use", false);
		Keys.set("shift", false);
		Keys.set("ctrl", false);
		rise(false);
	}

	/** Forward, and at a run. Sprinting is faster on foot and in the air alike. */
	private static void go(boolean forward) {
		Keys.set("w", forward);
		Keys.set("ctrl", forward);
	}

	/**
	 * Starts the distance clock again.
	 *
	 * <p>Deliberately says nothing about the climb. Climbing calls this every
	 * tick, since going up is not going nowhere, so anything reset here is reset
	 * before it can count to anything. The climb has its own clock, cleared when
	 * it reaches the height it was after.
	 */
	private void clearProgress() {
		closestYet = Double.MAX_VALUE;
		stalledTicks = 0;
	}

	private void forgetClimb() {
		blockedTicks = 0;
		lastY = Double.MAX_VALUE;
	}

	/** Arrived: the clock stops and the height it needed is no longer owed. */
	private void clearStall() {
		clearProgress();
		forgetClimb();
		lift = 0.0;
	}

	/**
	 * The plot to head for when nothing is in sight but the server says there is.
	 *
	 * <p>Entities only exist near you, so a pest three plots away cannot be
	 * looked for, only travelled to. The tab list names the plots; going to one
	 * loads it, and then the ordinary searching takes over. That is the whole
	 * trick: the text says where, the entities say what.
	 */
	private Vec3 plotToTry(Minecraft client) {
		travellingTo = -1;
		List<Integer> plots = Pests.plotsWithPests(client);
		if (plots.isEmpty()) {
			emptyPlots.clear();
			return null;
		}
		if (emptyPlots.size() >= plots.size()) {
			// Every one has been stood in and come up empty. They may have moved
			// since, so the slate is cleared rather than the hunt giving up.
			emptyPlots.clear();
		}

		double px = client.player.getX();
		double pz = client.player.getZ();
		int standingIn = GardenPlots.plotAt(px, pz);

		Vec3 best = null;
		double nearest = Double.MAX_VALUE;
		for (int plot : plots) {
			if (emptyPlots.contains(plot)) {
				continue;
			}
			double[] centre = GardenPlots.centreOf(plot);
			if (centre == null) {
				continue;
			}
			double dx = centre[0] - px;
			double dz = centre[1] - pz;
			double away = plot == standingIn ? -1.0 : Math.sqrt(dx * dx + dz * dz);
			if (away < nearest) {
				nearest = away;
				best = new Vec3(centre[0], client.player.getY(), centre[1]);
				travellingTo = plot;
			}
		}
		if (best == null) {
			return null;
		}

		// Being in the plot is when to start looking round it, not when to
		// decide it is empty: nothing has been sent yet at the moment of
		// arriving.
		if (travellingTo != sweepingPlot) {
			sweepingPlot = travellingTo;
			sweepStep = 0;
			settledTicks = 0;
		}
		double[] corner = SWEEP[Math.min(sweepStep, SWEEP.length - 1)];
		return new Vec3(best.x + corner[0], best.y, best.z + corner[1]);
	}

	/**
	 * The nearest pest worth walking at, live or remembered.
	 *
	 * <p>Recomputed every tick rather than held onto. A target that dies, or one
	 * that turns out to be nearer, then costs nothing to change to, and there is
	 * no stale reference to a mob that no longer exists.
	 */
	private Vec3 nearest(Minecraft client) {
		Vec3 eye = client.player.getEyePosition();

		Vec3 best = null;
		double nearest = Double.MAX_VALUE;
		int bestId = -1;
		double[] bestMark = null;

		List<Pests.Tracked> tracked = pests.tracked();
		for (int i = 0; i < tracked.size(); i++) {
			int id = tracked.get(i).id();
			Entity entity = client.level.getEntity(id);
			if (entity == null) {
				continue;
			}
			Vec3 at = middleOf(entity);
			double away = eye.distanceTo(at);
			if (away < nearest) {
				nearest = away;
				best = at;
				bestId = id;
				bestMark = null;
			}
		}

		List<Pests.Mark> marks = pests.remembered();
		for (int i = 0; i < marks.size(); i++) {
			Pests.Mark mark = marks.get(i);
			Vec3 at = new Vec3(mark.x(), mark.y() + 0.3, mark.z());
			double away = eye.distanceTo(at);
			if (away < nearest) {
				nearest = away;
				best = at;
				bestId = -1;
				bestMark = new double[] {mark.x(), mark.y(), mark.z()};
			}
		}

		// Stay on the one it picked unless something is clearly better. Two of
		// them a similar way off would otherwise trade places as nearest every
		// time the player shifts, and the aim would chase the swap rather than
		// either pest.
		Vec3 held = lockedPosition(client);
		if (held != null && nearest > eye.distanceTo(held) - WORTH_SWITCHING) {
			return held;
		}

		lockedId = bestId;
		lockedMark = bestMark;
		return best;
	}

	/** Where the pest it settled on is now, or null if it is gone. */
	private Vec3 lockedPosition(Minecraft client) {
		if (lockedId >= 0) {
			Entity entity = client.level.getEntity(lockedId);
			return entity == null ? null : middleOf(entity);
		}
		if (lockedMark == null) {
			return null;
		}
		for (Pests.Mark mark : pests.remembered()) {
			if (Math.abs(mark.x() - lockedMark[0]) < 0.001
				&& Math.abs(mark.z() - lockedMark[2]) < 0.001) {
				return new Vec3(mark.x(), mark.y() + 0.3, mark.z());
			}
		}
		return null;
	}

	private static Vec3 middleOf(Entity entity) {
		AABB box = entity.getBoundingBox();
		return new Vec3(
			(box.minX + box.maxX) / 2.0,
			(box.minY + box.maxY) / 2.0,
			(box.minZ + box.maxZ) / 2.0);
	}

	private void forgetTarget() {
		lockedId = -1;
		lockedMark = null;
	}

	/**
	 * Turns towards the target, and answers how far off it still is.
	 *
	 * <p>A limited number of degrees a tick rather than the exact angle at once.
	 * Snapping the camera is both unpleasant to sit behind and unnecessary: the
	 * pest is not going anywhere in the fifth of a second this takes.
	 */
	private static float aim(Minecraft client, Vec3 target) {
		Vec3 eye = client.player.getEyePosition();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double flat = Math.sqrt(dx * dx + dz * dz);

		float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, flat));

		float yawOff = wrap(wantYaw - client.player.getYRot());
		float pitchOff = wrap(wantPitch - client.player.getXRot());

		client.player.setYRot(client.player.getYRot() + clamp(yawOff, TURN));
		client.player.setXRot(Math.max(-90.0f,
			Math.min(90.0f, client.player.getXRot() + clamp(pitchOff, TURN))));

		return Math.max(Math.abs(yawOff), Math.abs(pitchOff));
	}

	private static float clamp(float value, float limit) {
		return Math.max(-limit, Math.min(limit, value));
	}

	/** Into -180 to 180, so turning takes the short way round. */
	private static float wrap(float degrees) {
		float wrapped = degrees % 360.0f;
		if (wrapped >= 180.0f) {
			wrapped -= 360.0f;
		}
		if (wrapped < -180.0f) {
			wrapped += 360.0f;
		}
		return wrapped;
	}
}
