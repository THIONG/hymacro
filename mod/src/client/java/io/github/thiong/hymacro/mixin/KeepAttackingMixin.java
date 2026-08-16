package io.github.thiong.hymacro.mixin;

import io.github.thiong.hymacro.Clicks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Keeps a block being broken while the window is behind something else.
 *
 * <p>Calling the game's own method from the end of the tick was not enough, and
 * the reason is worth writing down. Each tick the game decides for itself
 * whether the attack button is down, and with the mouse released it decides no
 * and stops breaking whatever was being broken. Adding a call afterwards meant
 * one tick of progress being made and the next tick throwing it away: the
 * pickaxe swung for ever and nothing ever broke.
 *
 * <p>So the answer is not to call it again but to change the answer it gets.
 * Nothing else about the decision is touched, and when the mod is not running
 * this returns exactly what the game worked out for itself.
 */
@Mixin(Minecraft.class)
public class KeepAttackingMixin {
	@ModifyVariable(method = "continueAttack", at = @At("HEAD"), argsOnly = true)
	private boolean hymacro$keepBreaking(boolean pressed) {
		Clicks.reached();
		return pressed || Clicks.wantsAttack();
	}
}
