package com.emc_assistant;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(EMCAssistantMod.MOD_ID)
public class EMCAssistantMod {
    public static final String MOD_ID = "emcassistant";
    private static final Logger LOGGER = LogUtils.getLogger();

    public EMCAssistantMod() {
        LOGGER.info("[EMC Assistant] Mod initializing...");
        // RegistryScanner is auto-registered via @Mod.EventBusSubscriber,
        // no manual registration needed.
    }
}