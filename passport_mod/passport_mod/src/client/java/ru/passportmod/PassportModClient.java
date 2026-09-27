package ru.passportmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.component.DataComponents;

public final class PassportModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(PassportMod.OpenGuiPayload.TYPE, (payload, context) ->
                context.client().execute(() -> Minecraft.getInstance().setScreenAndShow(PassportScreens.create(payload.screen(), payload.data()))));
        ClientPlayNetworking.registerGlobalReceiver(PassportMod.NoticePayload.TYPE, (payload, context) ->
                context.client().execute(() -> Minecraft.getInstance().player.sendSystemMessage(
                        Component.literal(payload.message()).withStyle(ChatFormatting.YELLOW))));

        ItemTooltipCallback.EVENT.register((stack, context, type, tooltip) -> {
            if (!(stack.getItem() instanceof PassportItem)) return;
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            tooltip.add(Component.literal("Паспорт RP").withStyle(ChatFormatting.GOLD));
            tooltip.add(Component.literal("ПКМ: открыть просмотр").withStyle(ChatFormatting.GRAY));
            if (data != null && data.copyTag().getBooleanOr("issued", false)) {
                var tag = data.copyTag();
                tooltip.add(Component.literal("Серия " + tag.getStringOr("series", "—")
                        + "  № " + tag.getStringOr("number", "—")).withStyle(ChatFormatting.GRAY));
                tooltip.add(Component.literal("Статус: " + PassportMod.statusLabel(tag.getStringOr("status", "VALID")))
                        .withStyle(ChatFormatting.GRAY));
            } else {
                tooltip.add(Component.literal("Чистый бланк").withStyle(ChatFormatting.DARK_GRAY));
            }
        });
    }

    static void send(String action, String data) {
        ClientPlayNetworking.send(new PassportMod.ActionPayload(action, data == null ? "" : data));
    }

    static void send(String action) {
        send(action, "");
    }

    public static void openPocket() {
        send("OPEN_POCKET");
    }
}
