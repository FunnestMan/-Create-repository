package com.zot.fallindicator;

import net.minecraftforge.fml.common.Mod;

// Главный класс мода — точка входа Forge
@Mod(FallIndicatorMod.MOD_ID)
public class FallIndicatorMod {

    public static final String MOD_ID = "fallindicator";

    public FallIndicatorMod() {
        // Клиентские события регистрируются через @Mod.EventBusSubscriber(value = Dist.CLIENT)
        // Ничего дополнительно регистрировать не нужно
    }
}
