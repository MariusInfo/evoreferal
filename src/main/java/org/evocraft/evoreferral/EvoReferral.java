package org.evocraft.evoreferral;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod("evoreferal")
public class EvoReferral {

    public EvoReferral() {
        // Înregistrăm clasa pentru a asculta evenimentele Forge
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        // Ne asigurăm că rulăm logica doar pe server, nu și pe client
        if (!event.getEntity().level().isClientSide()) {
            System.out.println("[EvoReferral] Verificăm recompensele pentru: " + event.getEntity().getName().getString());
            ReferralManager.checkAndReward(event.getEntity());
        }
    }
}