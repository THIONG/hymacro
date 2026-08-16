package io.github.thiong.hymacro;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Holding, releasing and reading keys, addressed physically.
 *
 * <p>Setting the state of a physical key is what the game does when the player
 * presses one, so whatever binding sits on that key responds normally. Going
 * through the key rather than the binding keeps this to a couple of methods of
 * the Minecraft API, which matters on a version whose mappings are not
 * published.
 */
public final class Keys {
	private Keys() {
	}

	public static final String ATTACK = "attack";
	public static final String USE = "use";

	/** What a recording can capture. */
	public static final List<String> RECORDABLE =
		List.of("w", "a", "s", "d", "space", "shift", ATTACK, USE);

	private static int codeFor(String name) {
		return switch (name.toLowerCase()) {
			case "w" -> GLFW.GLFW_KEY_W;
			case "a" -> GLFW.GLFW_KEY_A;
			case "s" -> GLFW.GLFW_KEY_S;
			case "d" -> GLFW.GLFW_KEY_D;
			case "space" -> GLFW.GLFW_KEY_SPACE;
			case "shift" -> GLFW.GLFW_KEY_LEFT_SHIFT;
			case "ctrl" -> GLFW.GLFW_KEY_LEFT_CONTROL;
			default -> GLFW.GLFW_KEY_UNKNOWN;
		};
	}

	private static int mouseButtonFor(String name) {
		return switch (name.toLowerCase()) {
			case ATTACK -> GLFW.GLFW_MOUSE_BUTTON_LEFT;
			case USE -> GLFW.GLFW_MOUSE_BUTTON_RIGHT;
			default -> -1;
		};
	}

	public static boolean isKnown(String name) {
		return codeFor(name) != GLFW.GLFW_KEY_UNKNOWN || mouseButtonFor(name) >= 0;
	}

	private static boolean attackHeld;
	private static boolean useHeld;

	/** Whether the mod is holding the attack button down right now. */
	public static boolean attackHeld() {
		return attackHeld;
	}

	/** Whether the mod is holding the use button down right now. */
	public static boolean useHeld() {
		return useHeld;
	}

	public static void set(String name, boolean held) {
		// Remembered because the game will not always act on it: with the window
		// behind something else it reads the button as up whatever the binding
		// says, and something has to know what was actually meant.
		String plain = name.toLowerCase();
		if (ATTACK.equals(plain)) {
			attackHeld = held;
		} else if (USE.equals(plain)) {
			useHeld = held;
		}

		int button = mouseButtonFor(name);
		if (button >= 0) {
			KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(button), held);
			return;
		}
		int code = codeFor(name);
		if (code != GLFW.GLFW_KEY_UNKNOWN) {
			KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(code), held);
		}
	}

	/**
	 * Whether the key is physically down right now.
	 *
	 * <p>The window comes from GLFW rather than Minecraft: the client tick runs
	 * on the thread holding the game's GL context, and the accessor for the
	 * handle is not named the same in this version.
	 */
	public static boolean isHeld(String name) {
		long window = GLFW.glfwGetCurrentContext();
		if (window == 0L) {
			return false;
		}
		int button = mouseButtonFor(name);
		if (button >= 0) {
			return GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS;
		}
		int code = codeFor(name);
		return code != GLFW.GLFW_KEY_UNKNOWN && GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS;
	}

	public static boolean isKeyDown(int glfwCode) {
		long window = GLFW.glfwGetCurrentContext();
		return window != 0L && GLFW.glfwGetKey(window, glfwCode) == GLFW.GLFW_PRESS;
	}
}
