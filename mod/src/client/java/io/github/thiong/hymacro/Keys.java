package io.github.thiong.hymacro;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Holding and releasing keys, addressed by physical key rather than by binding.
 *
 * <p>Setting the state of a physical key is what the game itself does when the
 * player presses one, so whatever binding is attached to that key responds
 * normally. Going through the key rather than the binding keeps this to two
 * methods of the Minecraft API, which matters when the mappings for a version
 * cannot be consulted.
 */
public final class Keys {
	private Keys() {
	}

	public static final int ATTACK = -1;

	public static int codeFor(String name) {
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

	public static void set(int code, boolean held) {
		if (code == GLFW.GLFW_KEY_UNKNOWN) {
			return;
		}
		InputConstants.Key key = code == ATTACK
			? InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_LEFT)
			: InputConstants.Type.KEYSYM.getOrCreate(code);
		KeyMapping.set(key, held);
	}
}
