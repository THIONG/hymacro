package io.github.thiong.hymacro;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
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

	private boolean on;
	private double closestYet;
	private int stalledTicks;
	private boolean waiting;
	private boolean firing;
	private boolean closingIn;
	private double lift;

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

	/** Starts without the message, for when a macro is doing the starting. */
	public void start() {
		on = true;
		waiting = false;
		clearStall();
	}

	public void toggle() {
		if (on) {
			stop("Stopped hunting.");
			return;
		}
		on = true;
		waiting = false;
		clearStall();
		Chat.client("Hunting pests with slot 1.", false);
		Chat.clientNote("It walks up to each one and holds right click. F10 again to stop.");
	}

	/** Releases everything. Stopping must never leave the trigger held. */
	public void stop(String why) {
		boolean wasOn = on;
		on = false;
		waiting = false;
		firing = false;
		closingIn = false;
		lift = 0.0;
		release();
		if (wasOn && why != null) {
			Chat.client(why, false);
		}
	}

	public void tick(Minecraft client) {
		if (!on) {
			return;
		}
		if (client.player == null || client.level == null) {
			stop(null);
			return;
		}

		Vec3 target = nearest(client);
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

		float off = aim(client, target);
		boolean flying = client.player.getAbilities().flying;
		double away = client.player.getEyePosition().distanceTo(target);
		double flat = flatTo(client, target);

		// Which half of the job it is on. Crossing until it is overhead, and it
		// has to drift well back out before that is undone: three of the pests
		// fly, and one hovering on the line would otherwise flip between the
		// two several times a second.
		closingIn = closingIn ? flat <= STILL_OVERHEAD : flat <= OVERHEAD;

		// Far off: cross the ground between here and there. In the air that
		// means over it, at a height read from what is underneath.
		if (!closingIn) {
			firing = false;
			Keys.set("use", false);
			if (flying) {
				boolean climbing = hold(client, crossingHeight(client, target));
				Keys.set("w", !climbing);
				if (climbing) {
					// Going up is not going nowhere. The clock starts once it
					// is actually on its way.
					clearProgress();
					return;
				}
			} else {
				Keys.set("w", true);
				level();
			}
			creep(away);
			return;
		}

		// Overhead. Come down to hovering height and vacuum from above it.
		Keys.set("w", false);
		if (flying) {
			hold(client, target.y + HOVER);
		} else {
			level();
		}

		// Closes to seven, holds out to nine and a half. Same reasoning as the
		// aim: getting started is fussy, staying on it is not, because what
		// ends a vacuum is the pest being gone and nothing else.
		if (away > (firing ? KEEP_REACH : REACH)) {
			firing = false;
			Keys.set("use", false);
			creep(away);
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
	 * Works the two keys that change height, and says whether it is still
	 * climbing towards what it was given.
	 *
	 * <p>Flying forward in this game is flat: where the camera points decides
	 * which way, never whether up or down. Height is jump and sneak, and it is
	 * left to this so that aiming stays free to point at the pest.
	 */
	private static boolean hold(Minecraft client, double wantedY) {
		double rise = wantedY - client.player.getY();
		Keys.set("space", rise > HEIGHT_ENOUGH);
		Keys.set("shift", rise < -HEIGHT_ENOUGH);
		return rise > CLIMB_FIRST;
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
		return Math.max(highest + CLEARANCE + lift, target.y + HOVER);
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
	private void creep(double away) {
		if (away < closestYet - PROGRESS) {
			closestYet = away;
			stalledTicks = 0;
			return;
		}
		if (++stalledTicks < STALL_TICKS) {
			return;
		}
		if (lift + MORE_LIFT <= MOST_LIFT) {
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
	 * Lets go of the height keys, for when there is no flying to do.
	 *
	 * <p>Landing part way through a hunt would otherwise leave jump or sneak
	 * held from the last tick that was in the air.
	 */
	private static void level() {
		Keys.set("space", false);
		Keys.set("shift", false);
	}

	/** Every key this touches, up. Nothing may be left held. */
	private static void release() {
		Keys.set("w", false);
		Keys.set("use", false);
		Keys.set("space", false);
		Keys.set("shift", false);
	}

	private void clearProgress() {
		closestYet = Double.MAX_VALUE;
		stalledTicks = 0;
	}

	/** Arrived: the clock stops and the height it needed is no longer owed. */
	private void clearStall() {
		clearProgress();
		lift = 0.0;
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

		List<Pests.Tracked> tracked = pests.tracked();
		for (int i = 0; i < tracked.size(); i++) {
			Entity entity = client.level.getEntity(tracked.get(i).id());
			if (entity == null) {
				continue;
			}
			AABB box = entity.getBoundingBox();
			Vec3 at = new Vec3(
				(box.minX + box.maxX) / 2.0,
				(box.minY + box.maxY) / 2.0,
				(box.minZ + box.maxZ) / 2.0);
			double away = eye.distanceTo(at);
			if (away < nearest) {
				nearest = away;
				best = at;
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
			}
		}
		return best;
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
