package ru.passportmod.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.passportmod.PassportModClient;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
    @Shadow protected int leftPos;
    @Shadow protected int topPos;
    @Shadow protected abstract <T extends GuiEventListener> T addRenderableWidget(T widget);

    @Inject(method = "init", at = @At("TAIL"))
    private void passportmod$addPocketButton(CallbackInfo ci) {
        this.addRenderableWidget(Button.builder(Component.literal("P"), button -> PassportModClient.openPocket())
                .bounds(leftPos - 24, topPos + 4, 20, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Карман для паспорта")))
                .build());
    }
}
