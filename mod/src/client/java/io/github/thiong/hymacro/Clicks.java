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
		if (client.player == null || client.level == null || gameWillDoIt(client)) {
			return;
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
