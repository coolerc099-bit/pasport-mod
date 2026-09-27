package ru.passportmod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.server.level.ServerLevel;

/**
 * Единственное серверное хранилище данных паспорта.
 * Сохраняет документы, основной карман и cooldown смены ФИО между перезапусками.
 */
public final class PassportStorage extends SavedData {
    private String json;

    private static final Codec<PassportStorage> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(Codec.STRING.fieldOf("json").forGetter(storage -> storage.json))
                    .apply(instance, PassportStorage::new)
    );

    public static final SavedDataType<PassportStorage> TYPE = new SavedDataType<>(
            ResourceLocation.fromNamespaceAndPath(PassportMod.MOD_ID, "passport_data"),
            PassportStorage::new,
            CODEC,
            null
    );

    public PassportStorage() {
        this("{\"passports\":{},\"pockets\":{},\"cooldowns\":{}}");
    }

    public PassportStorage(String json) {
        this.json = json == null || json.isBlank()
                ? "{\"passports\":{},\"pockets\":{},\"cooldowns\":{}}"
                : json;
    }

    public static PassportStorage get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public JsonObject root() {
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            if (!object.has("passports")) object.add("passports", new JsonObject());
            if (!object.has("pockets")) object.add("pockets", new JsonObject());
            if (!object.has("cooldowns")) object.add("cooldowns", new JsonObject());
            return object;
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    public void root(JsonObject object) {
        this.json = object.toString();
        setDirty();
    }
}
