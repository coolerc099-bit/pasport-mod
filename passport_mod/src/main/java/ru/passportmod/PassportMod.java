package ru.passportmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BlockBehaviour;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class PassportMod implements ModInitializer {
    public static final String MOD_ID = "passportmod";

    public static final String PASSPORT_NAME = "passport";
    public static final String[] EXTRA_COVER_COLORS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "black"
    };
    public static final Map<String, PassportItem> PASSPORT_VARIANTS = new LinkedHashMap<>();
    public static final PassportItem PASSPORT;

    public static final ResourceKey<Block> PASSPORT_DESK_KEY =
            ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(MOD_ID, "passport_desk"));
    public static final PassportDeskBlock PASSPORT_DESK = registerBlock(
            PASSPORT_DESK_KEY,
            PassportDeskBlock::new,
            BlockBehaviour.Properties.of().strength(2.5F).sound(SoundType.WOOD)
    );
    public static final ResourceKey<Item> PASSPORT_DESK_ITEM_KEY =
            ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "passport_desk"));
    public static final BlockItem PASSPORT_DESK_ITEM = registerBlockItem(
            PASSPORT_DESK_ITEM_KEY, PASSPORT_DESK, new Item.Properties()
    );

    static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-zА-Яа-яЁё-]{2,32}");
    static final Pattern SEX_PATTERN = Pattern.compile("[МмЖж]");
    static final int DESK_RADIUS = 4;
    static final long FIO_COOLDOWN_MILLIS = 30L * 60L * 1000L;
    static final long SHOW_REQUEST_TIMEOUT_MILLIS = 15_000L;
    static final long VOTE_DURATION_MILLIS = 60_000L;

    private static VoteSession activeVote;
    private static final Map<UUID, PendingShow> pendingShows = new LinkedHashMap<>();

    static {
        PASSPORT = registerPassportVariant("passport");
        PASSPORT_VARIANTS.put("red", PASSPORT);
        for (String color : EXTRA_COVER_COLORS) {
            PASSPORT_VARIANTS.put(color, registerPassportVariant("passport_" + color));
        }
    }

    public record ActionPayload(String action, String data) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
        public static final Type<ActionPayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(MOD_ID, "action")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ActionPayload::action,
                ByteBufCodecs.STRING_UTF8, ActionPayload::data,
                ActionPayload::new
        );

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record OpenGuiPayload(String screen, String data) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
        public static final Type<OpenGuiPayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(MOD_ID, "open_gui")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenGuiPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, OpenGuiPayload::screen,
                ByteBufCodecs.STRING_UTF8, OpenGuiPayload::data,
                OpenGuiPayload::new
        );

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record NoticePayload(String message) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
        public static final Type<NoticePayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(MOD_ID, "notice")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, NoticePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, NoticePayload::message,
                NoticePayload::new
        );

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static PassportItem registerPassportVariant(String path) {
        ResourceKey<Item> key = ResourceKey.create(
                Registries.ITEM,
                Identifier.fromNamespaceAndPath(MOD_ID, path)
        );
        return register(key, PassportItem::new, new Item.Properties().stacksTo(1));
    }

    private static <T extends Item> T register(
            ResourceKey<Item> key,
            Function<Item.Properties, T> factory,
            Item.Properties properties
    ) {
        T item = factory.apply(properties.setId(key));
        return net.minecraft.core.Registry.register(BuiltInRegistries.ITEM, key, item);
    }

    private static <T extends Block> T registerBlock(
            ResourceKey<Block> key,
            Function<BlockBehaviour.Properties, T> factory,
            BlockBehaviour.Properties properties
    ) {
        T block = factory.apply(properties.setId(key));
        return net.minecraft.core.Registry.register(BuiltInRegistries.BLOCK, key, block);
    }

    private static BlockItem registerBlockItem(ResourceKey<Item> key, Block block, Item.Properties properties) {
        return net.minecraft.core.Registry.register(
                BuiltInRegistries.ITEM,
                key,
                new BlockItem(block, properties.setId(key))
        );
    }

    @Override
    public void onInitialize() {
        registerNetworkTypes();
        registerNetworkReceiver();
        registerCommands();
        registerDeskInteraction();
        registerCreativeEntries();
        ServerTickEvents.END_SERVER_TICK.register(PassportMod::serverTick);
    }

    private static void registerNetworkTypes() {
        PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OpenGuiPayload.TYPE, OpenGuiPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(NoticePayload.TYPE, NoticePayload.CODEC);
    }

    private static void registerNetworkReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> handleAction(player, payload.action(), payload.data()));
        });
    }

    private static void registerCreativeEntries() {
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.INGREDIENTS).register(entries -> {
            for (PassportItem item : PASSPORT_VARIANTS.values()) entries.accept(item);
            entries.accept(PASSPORT_DESK_ITEM);
        });
    }

    private static void registerDeskInteraction() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            BlockState state = world.getBlockState(hitResult.getBlockPos());
            if (!(state.getBlock() instanceof PassportDeskBlock)) return InteractionResult.PASS;
            if (world.isClientSide()) return InteractionResult.SUCCESS;
            if (player instanceof ServerPlayer serverPlayer) {
                openDesk(serverPlayer);
            }
            return InteractionResult.SUCCESS;
        });
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("passport")
                    .then(Commands.literal("help").executes(context -> help(context.getSource().getPlayerOrException())))
                    .then(Commands.literal("show")
                            .then(Commands.argument("player", EntityArgument.player())
                                    .executes(context -> showPassport(
                                            context.getSource().getPlayerOrException(),
                                            EntityArgument.getPlayer(context, "player")
                                    )))));

            dispatcher.register(Commands.literal("y").executes(context -> acceptShow(context.getSource().getPlayerOrException())));
            dispatcher.register(Commands.literal("n").executes(context -> declineShow(context.getSource().getPlayerOrException())));
        });
    }

    private static int help(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("ПАСПОРТ RP").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.literal("ПКМ по паспортному столу — открыть полный интерфейс паспорта.").withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(Component.literal("Паспорт хранится в отдельном «Кармане для паспорта» и не занимает обычный слот.").withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(Component.literal("В инвентаре нажми кнопку P/П, чтобы открыть карман.").withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(Component.literal("/passport show <player> — запросить показ паспорта ближайшему игроку.").withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(Component.literal("Получатель подтверждает показ: /y или /n. Максимальная дистанция — 4 блока и один мир.").withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int showPassport(ServerPlayer sender, ServerPlayer target) {
        if (sender == target) {
            PassportRecord record = currentRecord(sender);
            if (record == null) {
                notice(sender, "У тебя нет действующего паспорта в кармане.");
                return 0;
            }
            openViewer(sender, record);
            return 1;
        }
        if (!sameWorld(sender, target)) {
            notice(sender, "Показ возможен только игроку в том же мире.");
            return 0;
        }
        if (sender.distanceToSqr(target) > DESK_RADIUS * DESK_RADIUS) {
            notice(sender, "Игрок должен находиться не дальше 4 блоков.");
            return 0;
        }
        PassportRecord record = currentRecord(sender);
        if (record == null) {
            notice(sender, "В кармане нет твоего основного паспорта.");
            return 0;
        }
        if (!record.ownerUuid().equals(sender.getUUID().toString())) {
            notice(sender, "Сервер отклонил показ: владелец паспорта не совпадает с UUID игрока.");
            return 0;
        }
        pendingShows.put(target.getUUID(), new PendingShow(sender.getUUID(), target.getUUID(), record.id(), System.currentTimeMillis()));
        target.sendSystemMessage(Component.literal("Игрок " + sender.getName().getString()
                        + " хочет показать тебе свой паспорт. Напиши /y, чтобы принять, или /n, чтобы отказаться.")
                .withStyle(ChatFormatting.YELLOW));
        notice(sender, "Запрос отправлен. Ждём подтверждение получателя (/y или /n).");
        return 1;
    }

    private static int acceptShow(ServerPlayer target) {
        PendingShow request = pendingShows.remove(target.getUUID());
        if (request == null) {
            notice(target, "Нет ожидающего запроса на показ паспорта.");
            return 0;
        }
        ServerPlayer sender = target.server.getPlayerList().getPlayer(request.senderUuid());
        if (sender == null || !sameWorld(sender, target) || sender.distanceToSqr(target) > DESK_RADIUS * DESK_RADIUS) {
            notice(target, "Показ отменён: игрок больше не рядом. Требуется тот же мир и максимум 4 блока.");
            return 0;
        }
        PassportRecord record = currentRecord(sender);
        if (record == null || !record.id().equals(request.passportId())) {
            notice(target, "Показ отменён: паспорт изменился или больше не является основным.");
            return 0;
        }
        openViewer(sender, record);
        openViewer(target, record);
        notice(sender, "Получатель принял запрос и открыл просмотр твоего паспорта.");
        notice(target, "Паспорт принят. Открыт просмотр документа игрока " + sender.getName().getString() + ".");
        return 1;
    }

    private static int declineShow(ServerPlayer target) {
        PendingShow request = pendingShows.remove(target.getUUID());
        if (request == null) {
            notice(target, "Нет ожидающего запроса на показ паспорта.");
            return 0;
        }
        ServerPlayer sender = target.server.getPlayerList().getPlayer(request.senderUuid());
        if (sender != null) notice(sender, "Игрок отказался от просмотра паспорта.");
        notice(target, "Запрос отклонён.");
        return 1;
    }

    private static void serverTick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        pendingShows.entrySet().removeIf(entry -> {
            PendingShow request = entry.getValue();
            if (now - request.createdAt() <= SHOW_REQUEST_TIMEOUT_MILLIS) return false;
            ServerPlayer player = server.getPlayerList().getPlayer(request.targetUuid());
            if (player != null) notice(player, "Запрос на показ паспорта истёк.");
            return true;
        });

        if (activeVote != null && now - activeVote.startedAt() >= VOTE_DURATION_MILLIS) {
            finishVote(server, false);
        }
    }

    static boolean isNearDesk(ServerPlayer player) {
        BlockPos center = player.blockPosition();
        for (int x = -DESK_RADIUS; x <= DESK_RADIUS; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -DESK_RADIUS; z <= DESK_RADIUS; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (player.level().getBlockState(pos).getBlock() instanceof PassportDeskBlock) {
                        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 25.0;
                    }
                }
            }
        }
        return false;
    }

    static void openDesk(ServerPlayer player) {
        JsonObject payload = new JsonObject();
        PassportRecord record = currentRecord(player);
        payload.addProperty("hasPassport", record != null);
        if (record != null) payload.add("record", record.toJson());
        payload.addProperty("cooldownMs", Math.max(0L, nameCooldownRemaining(player)));
        sendGui(player, "desk", payload);
    }

    static void openPocket(ServerPlayer player) {
        JsonObject payload = new JsonObject();
        String itemPath = pocketItemPath(player);
        PassportRecord record = currentRecord(player);
        payload.addProperty("occupied", itemPath != null);
        payload.addProperty("itemPath", itemPath == null ? "" : itemPath);
        if (record != null && itemPath != null) payload.add("record", record.toJson());
        sendGui(player, "pocket", payload);
    }

    static void openViewer(ServerPlayer player, PassportRecord record) {
        sendGui(player, "viewer", record.toJson());
    }

    static void openNameChange(ServerPlayer player) {
        PassportRecord record = currentRecord(player);
        if (record == null) {
            notice(player, "Сначала установи паспорт в карман.");
            return;
        }
        JsonObject payload = record.toJson();
        payload.addProperty("cooldownMs", Math.max(0L, nameCooldownRemaining(player)));
        sendGui(player, "namechange", payload);
    }

    private static void sendGui(ServerPlayer player, String screen, JsonObject data) {
        ServerPlayNetworking.send(player, new OpenGuiPayload(screen, data.toString()));
    }

    static void notice(ServerPlayer player, String message) {
        ServerPlayNetworking.send(player, new NoticePayload(message));
    }

    private static void handleAction(ServerPlayer player, String action, String packed) {
        switch (action) {
            case "OPEN_POCKET" -> openPocket(player);
            case "OPEN_DESK" -> {
                if (isNearDesk(player)) openDesk(player);
                else notice(player, "Паспортный стол должен находиться рядом.");
            }
            case "POCKET_PUT" -> pocketPut(player);
            case "POCKET_TAKE" -> pocketTake(player);
            case "POCKET_VIEW" -> {
                PassportRecord record = currentRecord(player);
                if (record == null) notice(player, "В кармане нет действующего паспорта.");
                else openViewer(player, record);
            }
            case "DESK_ISSUE" -> issueFromDesk(player, decodeFields(packed));
            case "DESK_REMOVE" -> {
                if (!isNearDesk(player)) {
                    notice(player, "Сначала подойди к паспортному столу.");
                    return;
                }
                pocketTake(player);
                openDesk(player);
            }
            case "OPEN_NAMECHANGE" -> {
                if (isNearDesk(player)) openNameChange(player);
                else notice(player, "Смена ФИО доступна только у паспортного стола.");
            }
            case "NAME_PROPOSE" -> proposeNameChange(player, decodeFields(packed));
            case "VOTE" -> castVote(player, packed);
            case "REG_UPDATE" -> updateRegistration(player, decodeFields(packed));
            case "MARRIAGE_ADD" -> addMarriage(player, decodeFields(packed));
            case "CHILD_ADD" -> addChild(player, decodeFields(packed));
            case "MILITARY_ADD" -> addMilitary(player, decodeFields(packed));
            case "EXTRA_ADD" -> addExtra(player, decodeFields(packed));
            case "SET_STATUS" -> setStatus(player, packed);
            case "REPLACE" -> replacePassport(player);
            case "VIEW_SELF" -> {
                PassportRecord record = currentRecord(player);
                if (record == null) notice(player, "В кармане нет паспорта.");
                else openViewer(player, record);
            }
            default -> notice(player, "Неизвестное действие интерфейса.");
        }
    }

    private static void issueFromDesk(ServerPlayer player, String[] fields) {
        if (!isNearDesk(player)) {
            notice(player, "Оформление доступно только у паспортного стола.");
            return;
        }
        if (fields.length < 8) {
            notice(player, "Не заполнены все поля паспорта.");
            return;
        }
        String itemPath = pocketItemPath(player);
        if (itemPath == null) {
            notice(player, "Сначала положи бланк в карман для паспорта.");
            return;
        }
        if (!itemPath.startsWith("passport")) {
            notice(player, "В кармане находится не паспорт.");
            return;
        }
        if (currentRecord(player) != null) {
            notice(player, "У тебя уже оформлен основной паспорт.");
            return;
        }
        String surname = fields[0].trim();
        String name = fields[1].trim();
        String patronymic = fields[2].trim();
        String sex = fields[3].trim().toUpperCase();
        String birthDate = fields[4].trim();
        String birthPlace = fields[5].trim();
        String citizenship = fields[6].trim();
        String registration = fields[7].trim();

        String error = validateIssueFields(surname, name, patronymic, sex, birthDate, birthPlace, citizenship, registration);
        if (error != null) {
            notice(player, error);
            return;
        }

        PassportStorage storage = PassportStorage.get(player.serverLevel());
        JsonObject root = storage.root();
        JsonObject passports = root.getAsJsonObject("passports");
        String ownerUuid = player.getUUID().toString();
        if (passports.has(ownerUuid)) {
            notice(player, "Сервер уже хранит паспорт для этого UUID.");
            return;
        }

        String series;
        String number;
        do {
            series = String.format("%04d", ThreadLocalRandom.current().nextInt(1, 10_000));
            number = String.format("%06d", ThreadLocalRandom.current().nextInt(1, 1_000_000));
        } while (passportNumberExists(root, series, number));

        String today = LocalDate.now().format(DATE_FORMAT);
        String id = UUID.randomUUID().toString();
        PassportRecord record = PassportRecord.blankIssued(
                id,
                ownerUuid,
                player.getName().getString(),
                series,
                number,
                surname,
                name,
                patronymic,
                sex,
                birthDate,
                birthPlace,
                citizenship,
                registration,
                today,
                "Паспортный стол RP",
                "770-001"
        );
        passports.add(ownerUuid, record.toJson());
        storage.root(root);
        openDesk(player);
        notice(player, "Паспорт оформлен: серия " + series + " № " + number + ".");
    }

    private static String validateIssueFields(
            String surname, String name, String patronymic, String sex,
            String birthDate, String birthPlace, String citizenship, String registration
    ) {
        if (!NAME_PATTERN.matcher(surname).matches() || !NAME_PATTERN.matcher(name).matches()
                || !NAME_PATTERN.matcher(patronymic).matches()) {
            return "Фамилия, имя и отчество: 2–32 символа, только буквы и дефис.";
        }
        if (!SEX_PATTERN.matcher(sex).matches()) return "Пол укажи как М или Ж.";
        LocalDate birth = parseDate(birthDate);
        if (birth == null) return "Дата рождения должна быть в формате ДД.ММ.ГГГГ.";
        if (birth.isAfter(LocalDate.now())) return "Дата рождения не может быть в будущем.";
        if (birthPlace.length() < 2 || birthPlace.length() > 120) return "Место рождения: 2–120 символов.";
        if (citizenship.length() < 2 || citizenship.length() > 80) return "Гражданство: 2–80 символов.";
        if (registration.length() < 3 || registration.length() > 120) return "Регистрация: 3–120 символов.";
        return null;
    }

    private static void pocketPut(ServerPlayer player) {
        if (pocketItemPath(player) != null) {
            notice(player, "Карман для паспорта уже занят.");
            return;
        }
        int slot = findPassportInventorySlot(player);
        if (slot < 0) {
            notice(player, "В инвентаре нет паспорта или бланка паспорта.");
            return;
        }
        ItemStack stack = player.getInventory().getItem(slot);
        if (!(stack.getItem() instanceof PassportItem)) {
            notice(player, "Сюда можно поместить только паспорт.");
            return;
        }
        String itemId = PassportData.get(stack, "passport_id");
        String itemOwner = PassportData.get(stack, "owner_uuid");
        if (!itemId.isBlank() || !itemOwner.isBlank() || PassportData.isIssued(stack)) {
            PassportRecord current = currentRecord(player);
            if (current == null || itemId.isBlank() || !current.id().equals(itemId)
                    || !current.ownerUuid().equals(player.getUUID().toString())
                    || !current.series().equals(PassportData.get(stack, "series"))
                    || !current.number().equals(PassportData.get(stack, "number"))) {
                notice(player, "Этот паспорт не принадлежит тебе по серверной записи.");
                return;
            }
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        setPocketItemPath(player, id.getPath());
        player.getInventory().removeItem(slot, 1);
        notice(player, "Паспорт помещён в «Карман для паспорта». Он не занимает обычный слот.");
        openPocket(player);
    }

    private static void pocketTake(ServerPlayer player) {
        String path = pocketItemPath(player);
        if (path == null) {
            notice(player, "Карман для паспорта пуст.");
            return;
        }
        if (!hasFreeInventorySlot(player)) {
            notice(player, "Освободи место в инвентаре, чтобы забрать паспорт из кармана.");
            return;
        }
        ItemStack stack = createPassportStack(path, player);
        if (!player.getInventory().add(stack)) {
            notice(player, "Не удалось вернуть паспорт в инвентарь.");
            return;
        }
        setPocketItemPath(player, null);
        notice(player, "Паспорт забран из кармана.");
        openPocket(player);
    }

    private static int findPassportInventorySlot(ServerPlayer player) {
        // Сначала основной и дополнительный слот руки.
        if (player.getMainHandItem().getItem() instanceof PassportItem) return player.getInventory().selected;
        if (player.getOffhandItem().getItem() instanceof PassportItem) return 40;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).getItem() instanceof PassportItem) return i;
        }
        return -1;
    }

    private static boolean hasFreeInventorySlot(ServerPlayer player) {
        for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).isEmpty()) return true;
        return false;
    }

    private static ItemStack createPassportStack(String path, ServerPlayer owner) {
        Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, path);
        Item item = BuiltInRegistries.ITEM.getValue(id);
        ItemStack stack = new ItemStack(item);
        PassportRecord record = currentRecord(owner);
        if (record != null) PassportData.writeRecord(stack, record);
        return stack;
    }

    private static void proposeNameChange(ServerPlayer player, String[] fields) {
        if (!isNearDesk(player)) {
            notice(player, "Заявка на смену ФИО подаётся только у паспортного стола.");
            return;
        }
        if (activeVote != null) {
            notice(player, "Сейчас уже идёт другое голосование. Дождись его завершения.");
            return;
        }
        if (fields.length < 4) {
            notice(player, "Не заполнены данные заявки.");
            return;
        }
        long remain = nameCooldownRemaining(player);
        if (remain > 0) {
            notice(player, "До следующей смены ФИО осталось " + formatCooldown(remain) + ".");
            return;
        }
        PassportRecord record = currentRecord(player);
        if (record == null) {
            notice(player, "В кармане нет основного паспорта.");
            return;
        }
        if (!record.status().equals(PassportStatus.VALID.id)) {
            notice(player, "Менять ФИО можно только у действующего паспорта.");
            return;
        }
        String scope = fields[0].trim().toUpperCase();
        String surname = fields[1].trim();
        String name = fields[2].trim();
        String patronymic = fields[3].trim();
        if (!List.of("SURNAME", "NAME", "PATRONYMIC", "ALL").contains(scope)) {
            notice(player, "Неизвестный режим изменения ФИО.");
            return;
        }
        if ((scope.equals("SURNAME") || scope.equals("ALL")) && !NAME_PATTERN.matcher(surname).matches()) {
            notice(player, "Новая фамилия некорректна.");
            return;
        }
        if ((scope.equals("NAME") || scope.equals("ALL")) && !NAME_PATTERN.matcher(name).matches()) {
            notice(player, "Новое имя некорректно.");
            return;
        }
        if ((scope.equals("PATRONYMIC") || scope.equals("ALL")) && !NAME_PATTERN.matcher(patronymic).matches()) {
            notice(player, "Новое отчество некорректно.");
            return;
        }

        PassportStorage storage = PassportStorage.get(player.serverLevel());
        JsonObject root = storage.root();
        JsonObject cooldowns = root.getAsJsonObject("cooldowns");
        cooldowns.addProperty(player.getUUID().toString(), System.currentTimeMillis());
        storage.root(root);

        List<UUID> voters = new ArrayList<>();
        for (ServerPlayer online : player.server.getPlayerList().getPlayers()) voters.add(online.getUUID());
        activeVote = new VoteSession(player.getUUID(), record.id(), scope, record.surname(), record.name(), record.patronymic(),
                surname, name, patronymic, System.currentTimeMillis(), voters);
        for (ServerPlayer online : player.server.getPlayerList().getPlayers()) {
            JsonObject voteJson = activeVote.toJson();
            sendGui(online, "vote", voteJson);
        }
        notice(player, "Голосование за смену ФИО запущено на 60 секунд.");
    }

    private static void castVote(ServerPlayer player, String value) {
        if (activeVote == null) {
            notice(player, "Сейчас нет активного голосования.");
            return;
        }
        if (!activeVote.voters().contains(player.getUUID())) {
            notice(player, "Ты не участвовал в голосовании, которое уже было начато.");
            return;
        }
        if (activeVote.voted().contains(player.getUUID())) {
            notice(player, "Твой голос уже принят.");
            return;
        }
        if ("YES".equalsIgnoreCase(value)) activeVote.yes().add(player.getUUID());
        else if ("NO".equalsIgnoreCase(value)) activeVote.no().add(player.getUUID());
        else {
            notice(player, "Некорректный голос.");
            return;
        }
        activeVote.voted().add(player.getUUID());
        notice(player, "Голос принят.");
        if (activeVote.voted().size() >= activeVote.voters().size()) finishVote(player.server, true);
        else if (activeVote.yes().size() * 2 > activeVote.voters().size()) finishVote(player.server, true);
        else if (activeVote.no().size() * 2 >= activeVote.voters().size()) finishVote(player.server, false);
    }

    private static void finishVote(MinecraftServer server, boolean earlyFinish) {
        if (activeVote == null) return;
        VoteSession vote = activeVote;
        boolean passed = vote.yes().size() * 2 > vote.voters().size();
        ServerPlayer owner = server.getPlayerList().getPlayer(vote.ownerUuid());
        if (passed) {
            PassportStorage storage = PassportStorage.get(server.overworld());
            JsonObject root = storage.root();
            JsonObject passports = root.getAsJsonObject("passports");
            JsonElement stored = passports.get(vote.ownerUuid().toString());
            PassportRecord record = stored != null && stored.isJsonObject() ? PassportRecord.fromJson(stored.getAsJsonObject()) : null;
            if (record != null && record.id().equals(vote.passportId())) {
                PassportRecord changed = record.withFio(vote.surname(), vote.name(), vote.patronymic(), vote.scope());
                passports.add(vote.ownerUuid().toString(), changed.toJson());
                storage.root(root);
                if (owner != null) syncAllPlayerPassports(owner, changed);
                if (owner != null) notice(owner, "Голосование завершено: изменение ФИО одобрено.");
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (vote.voters().contains(player.getUUID()) && !player.getUUID().equals(vote.ownerUuid())) {
                        notice(player, "Голосование за смену ФИО завершено: решение принято.");
                    }
                }
            } else if (owner != null) {
                notice(owner, "Голосование отменено: серверная запись паспорта изменилась.");
            }
        } else if (owner != null) {
            notice(owner, "Голосование завершено: заявка на смену ФИО не набрала большинство.");
        }
        activeVote = null;
    }

    private static void updateRegistration(ServerPlayer player, String[] fields) {
        if (!isNearDesk(player)) {
            notice(player, "Регистрацию можно менять только у паспортного стола.");
            return;
        }
        PassportRecord record = currentRecord(player);
        if (record == null) {
            notice(player, "У тебя нет основного паспорта в кармане.");
            return;
        }
        if (!record.status().equals(PassportStatus.VALID.id)) {
            notice(player, "Изменять регистрацию можно только у действующего паспорта.");
            return;
        }
        if (fields.length == 0 || fields[0].trim().length() < 3 || fields[0].trim().length() > 120) {
            notice(player, "Адрес регистрации должен быть длиной 3–120 символов.");
            return;
        }
        String value = fields[0].trim();
        PassportRecord changed = record.withRegistration(value);
        putCurrentRecord(player, changed);
        syncAllPlayerPassports(player, changed);
        notice(player, "Регистрация изменена. Изменение занесено в историю документа.");
        openDesk(player);
    }

    private static void addMarriage(ServerPlayer player, String[] fields) {
        if (!isNearDesk(player)) { notice(player, "Работа с браком доступна у паспортного стола."); return; }
        PassportRecord record = requireCurrentRecord(player); if (record == null) return;
        if (fields.length < 3) { notice(player, "Заполни супруг(у), дату и статус."); return; }
        String spouse = fields[0].trim(), date = fields[1].trim(), state = fields[2].trim();
        if (spouse.length() < 2 || spouse.length() > 80 || parseDate(date) == null || state.length() < 2) {
            notice(player, "Проверь данные брака."); return;
        }
        putCurrentRecord(player, record.addMarriage(spouse, date, state));
        notice(player, "Запись о браке добавлена.");
        openDesk(player);
    }

    private static void addChild(ServerPlayer player, String[] fields) {
        if (!isNearDesk(player)) { notice(player, "Записи о детях доступны у паспортного стола."); return; }
        PassportRecord record = requireCurrentRecord(player); if (record == null) return;
        if (fields.length < 2) { notice(player, "Заполни имя ребёнка и дату рождения."); return; }
        String child = fields[0].trim(), date = fields[1].trim();
        if (child.length() < 2 || child.length() > 80 || parseDate(date) == null) {
            notice(player, "Проверь данные ребёнка."); return;
        }
        putCurrentRecord(player, record.addChild(child, date));
        notice(player, "Запись о ребёнке добавлена.");
        openDesk(player);
    }

    private static void addMilitary(ServerPlayer player, String[] fields) {
        if (!isNearDesk(player)) { notice(player, "Военная служба оформляется у паспортного стола."); return; }
        PassportRecord record = requireCurrentRecord(player); if (record == null) return;
        if (fields.length < 3) { notice(player, "Заполни статус, период и подразделение."); return; }
        String status = fields[0].trim(), period = fields[1].trim(), unit = fields[2].trim();
        if (status.length() < 2 || period.length() < 2 || unit.length() < 2 || status.length() > 80 || period.length() > 80 || unit.length() > 80) {
            notice(player, "Проверь данные военной службы."); return;
        }
        putCurrentRecord(player, record.addMilitary(status, period, unit));
        notice(player, "Запись о военной службе добавлена.");
        openDesk(player);
    }

    private static void addExtra(ServerPlayer player, String[] fields) {
        if (!isNearDesk(player)) { notice(player, "Дополнительные сведения заполняются у паспортного стола."); return; }
        PassportRecord record = requireCurrentRecord(player); if (record == null) return;
        if (fields.length == 0 || fields[0].trim().length() < 2 || fields[0].trim().length() > 300) {
            notice(player, "Дополнительная запись должна быть длиной 2–300 символов."); return;
        }
        putCurrentRecord(player, record.addExtra(fields[0].trim()));
        notice(player, "Дополнительная отметка добавлена.");
        openDesk(player);
    }

    private static void setStatus(ServerPlayer player, String value) {
        if (!isNearDesk(player)) { notice(player, "Статус паспорта меняется у паспортного стола."); return; }
        PassportRecord record = requireCurrentRecord(player); if (record == null) return;
        if (!List.of(PassportStatus.VALID.id, PassportStatus.LOST.id, PassportStatus.INVALID.id).contains(value)) {
            notice(player, "Недопустимый статус."); return;
        }
        PassportRecord changed = record.withStatus(value, "Изменён статус: " + statusLabel(value));
        putCurrentRecord(player, changed);
        syncAllPlayerPassports(player, changed);
        notice(player, "Статус паспорта: " + statusLabel(value) + ".");
        openDesk(player);
    }

    private static void replacePassport(ServerPlayer player) {
        if (!isNearDesk(player)) { notice(player, "Замена паспорта выполняется у паспортного стола."); return; }
        PassportRecord current = requireCurrentRecord(player); if (current == null) return;
        PassportStorage storage = PassportStorage.get(player.serverLevel());
        JsonObject root = storage.root();
        JsonObject archive = root.has("archive") ? root.getAsJsonObject("archive") : new JsonObject();
        PassportRecord old = current.withStatus(PassportStatus.REPLACED.id, "Документ заменён новым паспортом");
        archive.add(old.id(), old.toJson());

        String series, number;
        do {
            series = String.format("%04d", ThreadLocalRandom.current().nextInt(1, 10_000));
            number = String.format("%06d", ThreadLocalRandom.current().nextInt(1, 1_000_000));
        } while (passportNumberExists(root, series, number));
        PassportRecord replacement = old.replacement(UUID.randomUUID().toString(), series, number, LocalDate.now().format(DATE_FORMAT));
        root.getAsJsonObject("passports").add(player.getUUID().toString(), replacement.toJson());
        root.add("archive", archive);
        storage.root(root);
        syncAllPlayerPassports(player, replacement);
        notice(player, "Паспорт заменён. Старый документ получил статус «Заменён».");
        openDesk(player);
    }

    @Nullable
    private static PassportRecord requireCurrentRecord(ServerPlayer player) {
        PassportRecord record = currentRecord(player);
        if (record == null) notice(player, "Основной паспорт не найден в кармане.");
        return record;
    }

    @Nullable
    static PassportRecord currentRecord(ServerPlayer player) {
        String path = pocketItemPath(player);
        if (path == null) return null;
        PassportStorage storage = PassportStorage.get(player.serverLevel());
        JsonObject passports = storage.root().getAsJsonObject("passports");
        JsonElement element = passports.get(player.getUUID().toString());
        if (element == null || !element.isJsonObject()) return null;
        return PassportRecord.fromJson(element.getAsJsonObject());
    }

    @Nullable
    static PassportRecord recordForStack(ServerPlayer player, ItemStack stack) {
        if (!(stack.getItem() instanceof PassportItem)) return null;
        String ownerUuid = PassportData.get(stack, "owner_uuid");
        String id = PassportData.get(stack, "passport_id");
        if (ownerUuid.isBlank() || id.isBlank() || !ownerUuid.equals(player.getUUID().toString())) return null;
        PassportStorage storage = PassportStorage.get(player.serverLevel());
        JsonObject root = storage.root();
        JsonObject passport = root.getAsJsonObject("passports").getAsJsonObject(player.getUUID().toString());
        if (passport != null && id.equals(passport.get("id").getAsString())) {
            PassportRecord record = PassportRecord.fromJson(passport);
            if (record.ownerUuid().equals(ownerUuid)
                    && record.series().equals(PassportData.get(stack, "series"))
                    && record.number().equals(PassportData.get(stack, "number"))) return record;
            return null;
        }
        if (root.has("archive") && root.getAsJsonObject("archive").has(id)) {
            PassportRecord record = PassportRecord.fromJson(root.getAsJsonObject("archive").getAsJsonObject(id));
            if (record.ownerUuid().equals(ownerUuid)
                    && record.series().equals(PassportData.get(stack, "series"))
                    && record.number().equals(PassportData.get(stack, "number"))) return record;
        }
        return null;
    }

    private static void putCurrentRecord(ServerPlayer player, PassportRecord record) {
        PassportStorage storage = PassportStorage.get(player.serverLevel());
        JsonObject root = storage.root();
        root.getAsJsonObject("passports").add(player.getUUID().toString(), record.toJson());
        storage.root(root);
    }

    private static void syncAllPlayerPassports(ServerPlayer player, PassportRecord record) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() instanceof PassportItem && PassportData.get(stack, "passport_id").equals(record.id())) {
                PassportData.writeRecord(stack, record);
            }
        }
        if (player.getOffhandItem().getItem() instanceof PassportItem
                && PassportData.get(player.getOffhandItem(), "passport_id").equals(record.id())) {
            PassportData.writeRecord(player.getOffhandItem(), record);
        }
    }

    private static boolean passportNumberExists(JsonObject root, String series, String number) {
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("passports").entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject o = entry.getValue().getAsJsonObject();
            if (series.equals(o.get("series").getAsString()) && number.equals(o.get("number").getAsString())) return true;
        }
        if (root.has("archive")) {
            for (JsonElement element : root.getAsJsonObject("archive").asMap().values()) {
                if (!element.isJsonObject()) continue;
                JsonObject o = element.getAsJsonObject();
                if (series.equals(o.get("series").getAsString()) && number.equals(o.get("number").getAsString())) return true;
            }
        }
        return false;
    }

    private static String pocketItemPath(ServerPlayer player) {
        JsonObject root = PassportStorage.get(player.serverLevel()).root();
        JsonElement value = root.getAsJsonObject("pockets").get(player.getUUID().toString());
        return value == null || value.getAsString().isBlank() ? null : value.getAsString();
    }

    private static void setPocketItemPath(ServerPlayer player, @Nullable String path) {
        PassportStorage storage = PassportStorage.get(player.serverLevel());
        JsonObject root = storage.root();
        JsonObject pockets = root.getAsJsonObject("pockets");
        String uuid = player.getUUID().toString();
        if (path == null || path.isBlank()) pockets.remove(uuid);
        else pockets.addProperty(uuid, path);
        storage.root(root);
    }

    private static long nameCooldownRemaining(ServerPlayer player) {
        JsonObject root = PassportStorage.get(player.serverLevel()).root();
        JsonElement value = root.getAsJsonObject("cooldowns").get(player.getUUID().toString());
        if (value == null) return 0L;
        long left = FIO_COOLDOWN_MILLIS - (System.currentTimeMillis() - value.getAsLong());
        return Math.max(0L, left);
    }

    private static String formatCooldown(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private static boolean sameWorld(ServerPlayer a, ServerPlayer b) {
        return a.level().dimension().equals(b.level().dimension());
    }

    @Nullable
    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value, DATE_FORMAT);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    static String statusLabel(String value) {
        return switch (value) {
            case "VALID" -> "Действителен";
            case "LOST" -> "Утерян";
            case "INVALID" -> "Недействителен";
            case "REPLACED" -> "Заменён";
            default -> "Неизвестен";
        };
    }

    static String encodeFields(String... values) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append('.');
            out.append(encoder.encodeToString(values[i].getBytes(StandardCharsets.UTF_8)));
        }
        return out.toString();
    }

    static String[] decodeFields(String packed) {
        if (packed == null) return new String[0];
        Base64.Decoder decoder = Base64.getUrlDecoder();
        String[] parts = packed.split("\\.", -1);
        String[] result = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = new String(decoder.decode(parts[i]), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                result[i] = "";
            }
        }
        return result;
    }

    enum PassportStatus {
        VALID("VALID"), LOST("LOST"), INVALID("INVALID"), REPLACED("REPLACED");
        final String id;
        PassportStatus(String id) { this.id = id; }
    }

    record PendingShow(UUID senderUuid, UUID targetUuid, String passportId, long createdAt) {}

    static final class VoteSession {
        private final UUID ownerUuid;
        private final String passportId;
        private final String scope;
        private final String oldSurname;
        private final String oldName;
        private final String oldPatronymic;
        private final String surname;
        private final String name;
        private final String patronymic;
        private final long startedAt;
        private final List<UUID> voters;
        private final List<UUID> voted = new ArrayList<>();
        private final List<UUID> yes = new ArrayList<>();
        private final List<UUID> no = new ArrayList<>();

        VoteSession(UUID ownerUuid, String passportId, String scope, String oldSurname, String oldName, String oldPatronymic,
                    String surname, String name, String patronymic, long startedAt, List<UUID> voters) {
            this.ownerUuid = ownerUuid;
            this.passportId = passportId;
            this.scope = scope;
            this.oldSurname = oldSurname;
            this.oldName = oldName;
            this.oldPatronymic = oldPatronymic;
            this.surname = surname;
            this.name = name;
            this.patronymic = patronymic;
            this.startedAt = startedAt;
            this.voters = new ArrayList<>(voters);
        }

        UUID ownerUuid() { return ownerUuid; }
        String passportId() { return passportId; }
        String scope() { return scope; }
        String surname() { return surname; }
        String name() { return name; }
        String patronymic() { return patronymic; }
        long startedAt() { return startedAt; }
        List<UUID> voters() { return voters; }
        List<UUID> voted() { return voted; }
        List<UUID> yes() { return yes; }
        List<UUID> no() { return no; }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("ownerName", oldSurname + " " + oldName + " " + oldPatronymic);
            o.addProperty("scope", scope);
            o.addProperty("oldSurname", oldSurname);
            o.addProperty("oldName", oldName);
            o.addProperty("oldPatronymic", oldPatronymic);
            o.addProperty("surname", surname);
            o.addProperty("name", name);
            o.addProperty("patronymic", patronymic);
            o.addProperty("voters", voters.size());
            o.addProperty("yes", yes.size());
            o.addProperty("no", no.size());
            o.addProperty("startedAt", startedAt);
            return o;
        }
    }

    static final class PassportRecord {
        private final String id;
        private final String ownerUuid;
        private final String ownerName;
        private final String series;
        private final String number;
        private final String surname;
        private final String name;
        private final String patronymic;
        private final String sex;
        private final String birthDate;
        private final String birthPlace;
        private final String citizenship;
        private final String registration;
        private final String issueDate;
        private final String issuingAuthority;
        private final String unitCode;
        private final String status;
        private final List<String> registrationHistory;
        private final List<String> marriageHistory;
        private final List<String> children;
        private final List<String> military;
        private final List<String> extra;
        private final List<String> history;

        private PassportRecord(String id, String ownerUuid, String ownerName, String series, String number,
                               String surname, String name, String patronymic, String sex, String birthDate,
                               String birthPlace, String citizenship, String registration, String issueDate,
                               String issuingAuthority, String unitCode, String status,
                               List<String> registrationHistory, List<String> marriageHistory, List<String> children,
                               List<String> military, List<String> extra, List<String> history) {
            this.id = id;
            this.ownerUuid = ownerUuid;
            this.ownerName = ownerName;
            this.series = series;
            this.number = number;
            this.surname = surname;
            this.name = name;
            this.patronymic = patronymic;
            this.sex = sex;
            this.birthDate = birthDate;
            this.birthPlace = birthPlace;
            this.citizenship = citizenship;
            this.registration = registration;
            this.issueDate = issueDate;
            this.issuingAuthority = issuingAuthority;
            this.unitCode = unitCode;
            this.status = status;
            this.registrationHistory = List.copyOf(registrationHistory);
            this.marriageHistory = List.copyOf(marriageHistory);
            this.children = List.copyOf(children);
            this.military = List.copyOf(military);
            this.extra = List.copyOf(extra);
            this.history = List.copyOf(history);
        }

        static PassportRecord blankIssued(String id, String ownerUuid, String ownerName, String series, String number,
                                          String surname, String name, String patronymic, String sex, String birthDate,
                                          String birthPlace, String citizenship, String registration, String issueDate,
                                          String issuingAuthority, String unitCode) {
            return new PassportRecord(id, ownerUuid, ownerName, series, number, surname, name, patronymic, sex,
                    birthDate, birthPlace, citizenship, registration, issueDate, issuingAuthority, unitCode,
                    PassportStatus.VALID.id,
                    List.of(registration), List.of(), List.of(), List.of(), List.of(),
                    List.of("Паспорт впервые оформлен " + issueDate));
        }

        static PassportRecord fromJson(JsonObject o) {
            return new PassportRecord(
                    str(o, "id"), str(o, "ownerUuid"), str(o, "ownerName"), str(o, "series"), str(o, "number"),
                    str(o, "surname"), str(o, "name"), str(o, "patronymic"), str(o, "sex"), str(o, "birthDate"),
                    str(o, "birthPlace"), str(o, "citizenship"), str(o, "registration"), str(o, "issueDate"),
                    str(o, "issuingAuthority"), str(o, "unitCode"), strDefault(o, "status", PassportStatus.VALID.id),
                    list(o, "registrationHistory"), list(o, "marriageHistory"), list(o, "children"),
                    list(o, "military"), list(o, "extra"), list(o, "history")
            );
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id); o.addProperty("ownerUuid", ownerUuid); o.addProperty("ownerName", ownerName);
            o.addProperty("series", series); o.addProperty("number", number); o.addProperty("surname", surname);
            o.addProperty("name", name); o.addProperty("patronymic", patronymic); o.addProperty("sex", sex);
            o.addProperty("birthDate", birthDate); o.addProperty("birthPlace", birthPlace); o.addProperty("citizenship", citizenship);
            o.addProperty("registration", registration); o.addProperty("issueDate", issueDate); o.addProperty("issuingAuthority", issuingAuthority);
            o.addProperty("unitCode", unitCode); o.addProperty("status", status);
            o.add("registrationHistory", array(registrationHistory)); o.add("marriageHistory", array(marriageHistory));
            o.add("children", array(children)); o.add("military", array(military)); o.add("extra", array(extra)); o.add("history", array(history));
            return o;
        }

        String id() { return id; }
        String ownerUuid() { return ownerUuid; }
        String ownerName() { return ownerName; }
        String series() { return series; }
        String number() { return number; }
        String surname() { return surname; }
        String name() { return name; }
        String patronymic() { return patronymic; }
        String sex() { return sex; }
        String birthDate() { return birthDate; }
        String birthPlace() { return birthPlace; }
        String citizenship() { return citizenship; }
        String registration() { return registration; }
        String issueDate() { return issueDate; }
        String issuingAuthority() { return issuingAuthority; }
        String unitCode() { return unitCode; }
        String status() { return status; }
        List<String> registrationHistory() { return registrationHistory; }
        List<String> marriageHistory() { return marriageHistory; }
        List<String> children() { return children; }
        List<String> military() { return military; }
        List<String> extra() { return extra; }
        List<String> history() { return history; }

        PassportRecord withFio(String newSurname, String newName, String newPatronymic, String scope) {
            String s = "SURNAME".equals(scope) || "ALL".equals(scope) ? newSurname : surname;
            String n = "NAME".equals(scope) || "ALL".equals(scope) ? newName : name;
            String p = "PATRONYMIC".equals(scope) || "ALL".equals(scope) ? newPatronymic : patronymic;
            List<String> h = new ArrayList<>(history);
            h.add("Смена ФИО (" + scope + "): " + surname + " " + name + " " + patronymic + " → " + s + " " + n + " " + p);
            return copy(s, n, p, registration, status, registrationHistory, marriageHistory, children, military, extra, h);
        }

        PassportRecord withRegistration(String value) {
            List<String> registrations = new ArrayList<>(registrationHistory);
            registrations.add(value);
            List<String> h = new ArrayList<>(history);
            h.add("Изменена регистрация: " + registration + " → " + value);
            return copy(surname, name, patronymic, value, status, registrations, marriageHistory, children, military, extra, h);
        }

        PassportRecord withStatus(String value, String historyEntry) {
            List<String> h = new ArrayList<>(history); h.add(historyEntry);
            return copy(surname, name, patronymic, registration, value, registrationHistory, marriageHistory, children, military, extra, h);
        }

        PassportRecord addMarriage(String spouse, String date, String state) {
            List<String> values = new ArrayList<>(marriageHistory);
            values.add(date + " — " + state + "; супруг(а): " + spouse);
            return copy(surname, name, patronymic, registration, status, registrationHistory, values, children, military, extra, history);
        }

        PassportRecord addChild(String child, String date) {
            List<String> values = new ArrayList<>(children);
            values.add(child + " — дата рождения: " + date);
            return copy(surname, name, patronymic, registration, status, registrationHistory, marriageHistory, values, military, extra, history);
        }

        PassportRecord addMilitary(String serviceStatus, String period, String unit) {
            List<String> values = new ArrayList<>(military);
            values.add(serviceStatus + "; " + period + "; подразделение: " + unit);
            return copy(surname, name, patronymic, registration, status, registrationHistory, marriageHistory, children, values, extra, history);
        }

        PassportRecord addExtra(String value) {
            List<String> values = new ArrayList<>(extra); values.add(value);
            return copy(surname, name, patronymic, registration, status, registrationHistory, marriageHistory, children, military, values, history);
        }

        PassportRecord replacement(String newId, String newSeries, String newNumber, String newIssueDate) {
            List<String> h = new ArrayList<>(history);
            h.add("Документ выдан взамен предыдущего; новая серия " + newSeries + " № " + newNumber);
            return new PassportRecord(newId, ownerUuid, ownerName, newSeries, newNumber, surname, name, patronymic,
                    sex, birthDate, birthPlace, citizenship, registration, newIssueDate,
                    "Паспортный стол RP — замена", unitCode, PassportStatus.VALID.id,
                    registrationHistory, marriageHistory, children, military, extra, h);
        }

        private PassportRecord copy(String s, String n, String p, String reg, String st,
                                    List<String> regHist, List<String> marriage, List<String> kids,
                                    List<String> army, List<String> extras, List<String> hist) {
            return new PassportRecord(id, ownerUuid, ownerName, series, number, s, n, p, sex, birthDate, birthPlace,
                    citizenship, reg, issueDate, issuingAuthority, unitCode, st, regHist, marriage, kids, army, extras, hist);
        }

        private static String str(JsonObject o, String key) { return o.has(key) ? o.get(key).getAsString() : ""; }
        private static String strDefault(JsonObject o, String key, String def) { return o.has(key) ? o.get(key).getAsString() : def; }
        private static List<String> list(JsonObject o, String key) {
            List<String> result = new ArrayList<>();
            if (!o.has(key) || !o.get(key).isJsonArray()) return result;
            for (JsonElement e : o.getAsJsonArray(key)) result.add(e.getAsString());
            return result;
        }
        private static JsonArray array(List<String> values) {
            JsonArray a = new JsonArray(); values.forEach(a::add); return a;
        }
    }

    static final class PassportData {
        private PassportData() {}

        static boolean isPassport(ItemStack stack) { return stack.getItem() instanceof PassportItem; }
        static boolean isIssued(ItemStack stack) { return getBool(stack, "issued"); }

        static String get(ItemStack stack, String key) {
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            return data == null ? "" : data.copyTag().getStringOr(key, "");
        }
        static boolean getBool(ItemStack stack, String key) {
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            return data != null && data.copyTag().getBooleanOr(key, false);
        }

        static void writeRecord(ItemStack stack, PassportRecord record) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
                tag.putBoolean("issued", true);
                tag.putString("passport_id", record.id());
                tag.putString("owner_uuid", record.ownerUuid());
                tag.putString("owner_name", record.ownerName());
                tag.putString("series", record.series());
                tag.putString("number", record.number());
                tag.putString("surname", record.surname());
                tag.putString("name", record.name());
                tag.putString("patronymic", record.patronymic());
                tag.putString("sex", record.sex());
                tag.putString("birth_date", record.birthDate());
                tag.putString("birth_place", record.birthPlace());
                tag.putString("citizenship", record.citizenship());
                tag.putString("registration", record.registration());
                tag.putString("issue_date", record.issueDate());
                tag.putString("issuing_authority", record.issuingAuthority());
                tag.putString("unit_code", record.unitCode());
                tag.putString("status", record.status());
            });
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("Паспорт · " + record.surname() + " " + record.name()));
        }
    }
}
