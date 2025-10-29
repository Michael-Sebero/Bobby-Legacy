package com.michaelsebero.bobby.mixin;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.settings.GameSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(GuiVideoSettings.class)
public abstract class GuiVideoSettingsMixin extends GuiScreen {
    @Shadow private GameSettings guiGameSettings;
    
    // This mixin is kept for future extensions but currently has no active injections
    // The render distance slider is modified via GameSettings$OptionsMixin instead
}
