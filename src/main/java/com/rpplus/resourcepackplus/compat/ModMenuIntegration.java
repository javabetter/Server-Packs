package com.rpplus.resourcepackplus.compat;

import com.rpplus.resourcepackplus.gui.ConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Lets ModMenu show a "config" button for this mod that opens our settings screen.
 * Only ever loaded/called by ModMenu itself — if ModMenu isn't installed, this class
 * is simply never touched, so it's safe even though we don't hard-depend on ModMenu.
 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ConfigScreen::new;
    }
}
