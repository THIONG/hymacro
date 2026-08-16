package io.github.thiong.hymacro;

import net.minecraft.client.Minecraft;

/**
 * Clicking when the game has decided not to.
 *
 * <p>Minecraft works the attack and use buttons only while no screen is open and
 * the mouse is captured. That is right for a person: a click aimed at a menu is
 * not a click aimed at the world, and a game left in the background should not
 * be swinging at things. It is wrong for this, because the route keeping going
 * while the computer is used for something else is the whole reason the mod
 * exists, and a macro that walks the plot without breaking a single crop is
 * worse than one that stops, since it looks like it is working.
 *
 * <p>Movement never had the problem. The keys are read straight out of the
 * bindings, so alt tabbing away left a player walking the rows and mining
 * nothing.
 *
 * <p>Only called on the ticks the game skipped. When the window is in front the
 * game does it, and doing it twice would mine at double speed and be obvious to
 * anybody watching.
 */
public final class Clicks {
	/**
	 * Ticks between uses of the held item.
	 *
	 * <p>The game spaces them out itself when it is doing the pressing. Calling
	 * it every tick instead would use whatever is in hand twenty times a second,
	 * which is not a person holding a button down and does not look like one.
	 */
	private static final int USE_EVERY = 4;

	private static int useIn;
	private static boolean wanted;

	/**
	 * What actually happened, counted rather than reasoned about.
	 *
	 * <p>Two explanations for the clicking stopping have now been wrong, both of
	 * them plausible and neither testable from here. Counting is what the game
	 * will answer honestly: how often it was asked, how often it agreed, and what
	 * it was looking at when it did.
	 */
	private static int sampled;
	private static int askedFor;
	private static int reached;
	private static int aimedAtBlock;
	private static int paused;
	private static int screened;
	private static int ungrabbed;
	private static int lastMiss;

	/** Counted by the mixin every time the game gets round to asking. */
	public static void reached() {
		reached++;
	}

	private Clicks() {
	}

	/**
	 * Whether the mod is asking for the attack button to be down.
	 *
	 * <p>Read by the one thing that has to overrule the game's own answer, and
	 * false whenever nothing of ours is running, so that outside a macro the
	 * game decides on its own exactly as it always did.
	 */
	public static boolean wantsAttack() {
		return wanted;
	}

	/** Notes the state of every question that could be stopping a swing. */
	private static void watch(Minecraft client, boolean attack) {
		sampled++;
		if (attack) {
			askedFor++;
		}
		if (client.isPaused()) {
			paused++;
		}
		if (client.screen != null) {
			screened++;
		}
		if (!client.mouseHandler.isMouseGrabbed()) {
			ungrabbed++;
		}
		if (client.hitResult != null
			&& client.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
			aimedAtBlock++;
		}
		lastMiss = client.missTime;
	}

	/** What has happened since this was last asked, then starts counting again. */
	public static java.util.List<String> report() {
		java.util.List<String> lines = new java.util.ArrayList<>();
		lines.add(sampled + " ticks watched");
		lines.add(askedFor + " of them wanted to attack");
		lines.add(reached + " reached the game's own attack step");
		lines.add(aimedAtBlock + " were looking at a block");
		lines.add(paused + " were paused, " + screened + " had a screen open, "
			+ ungrabbed + " had the mouse loose");
		lines.add("miss timer last read " + lastMiss);
		sampled = 0;
		askedFor = 0;
		reached = 0;
		aimedAtBlock = 0;
		paused = 0;
		screened = 0;
		ungrabbed = 0;
		return lines;
	}

	/** Nothing of ours is running, so nothing of ours is asking. */
	public static void idle() {
		wanted = false;
		useIn = 0;
	}

	/**
	 * Whether the game is going to work the buttons itself this tick.
	 *
	 * <p>The same two questions it asks: is a screen in the way, and is the mouse
	 * still captured. Asking them the same way is what keeps this from ever
	 * doubling up on what the game already did.
	 */
	private static boolean gameWillDoIt(Minecraft client) {
		return client.screen == null && client.mouseHandler.isMouseGrabbed();
	}

	/**
	 * Presses on with whatever is being held down, if the game will not.
	 *
	 * @param attack whether the macro is holding the attack button
	 * @param use whether it is holding the use button
	 */
	public static void carryOn(Minecraft client, boolean attack, boolean use) {
		wanted = attack;
		watch(client, attack);
		if (client.player == null || client.level == null || gameWillDoIt(client)) {
			return;
		}
		// The timer the game sets to ten thousand whenever a screen opens, so the
		// click that closed a menu does not also swing at the world. It counts
		// down one a tick, inside the step that only runs when no screen is
		// open, so with one open it never counts down at all and every attack
		// is refused before it starts. Cleared only while a macro is asking.
		if (attack && client.missTime > 0) {
			client.missTime = 0;
		}

		if (attack) {
			client.continueAttack(true);
		} else {
			// Telling it the button is up matters as much as telling it the
			// button is down: what it holds otherwise is a block half broken.
			client.continueAttack(false);
		}
		if (!use) {
			useIn = 0;
			return;
		}
		if (useIn > 0) {
			useIn--;
			return;
		}
		client.startUseItem();
		useIn = USE_EVERY;
	}
}
