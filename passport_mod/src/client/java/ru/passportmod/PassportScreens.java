package ru.passportmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class PassportScreens {
    private PassportScreens() {}

    static Screen create(String kind, String rawData) {
        JsonObject data;
        try {
            data = JsonParser.parseString(rawData == null || rawData.isBlank() ? "{}" : rawData).getAsJsonObject();
        } catch (Exception ignored) {
            data = new JsonObject();
        }
        return switch (kind) {
            case "desk" -> new DeskScreen(data);
            case "pocket" -> new PocketScreen(data);
            case "viewer" -> new PassportViewerScreen(data);
            case "namechange" -> new NameChangeScreen(data);
            case "vote" -> new VoteScreen(data);
            case "registration" -> new EntryFormScreen("REGISTRATION", "Регистрация", List.of("Новый адрес регистрации"), List.of(data.get("registration") == null ? "" : data.get("registration").getAsString()));
            case "marriage" -> new EntryFormScreen("MARRIAGE_ADD", "Запись о браке", List.of("Супруг(а)", "Дата (ДД.ММ.ГГГГ)", "Статус"), List.of("", "", "Состоит в браке"));
            case "children" -> new EntryFormScreen("CHILD_ADD", "Запись о ребёнке", List.of("Имя ребёнка", "Дата рождения (ДД.ММ.ГГГГ)"), List.of("", ""));
            case "military" -> new EntryFormScreen("MILITARY_ADD", "Военная служба", List.of("Статус", "Период", "Подразделение"), List.of("", "", ""));
            case "extra" -> new EntryFormScreen("EXTRA_ADD", "Дополнительные сведения", List.of("Текст дополнительной отметки"), List.of(""));
            default -> new SimpleInfoScreen("Passport RP", "Интерфейс не найден.");
        };
    }

    static void send(String action, String... fields) {
        PassportModClient.send(action, PassportMod.encodeFields(fields));
    }

    static String value(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsString() : "";
    }

    private abstract static class BaseScreen extends Screen {
        protected int left;
        protected int top;

        protected BaseScreen(Component title) {
            super(title);
        }

        @Override
        protected void init() {
            left = Math.max(8, (width - 360) / 2);
            top = Math.max(8, (height - 260) / 2);
        }

        protected Button button(String text, int x, int y, int w, int h, Runnable action) {
            Button b = Button.builder(Component.literal(text), button -> action.run())
                    .bounds(x, y, w, h)
                    .build();
            addRenderableWidget(b);
            return b;
        }

        protected Button label(String text, int x, int y, int w, int h) {
            Button b = button(text, x, y, w, h, () -> {});
            b.active = false;
            return b;
        }

        protected EditBox edit(String text, int x, int y, int w, String initial, int maxLength) {
            EditBox box = new EditBox(font, x, y, w, 20, Component.literal(text));
            box.setMaxLength(maxLength);
            box.setValue(initial == null ? "" : initial);
            addRenderableWidget(box);
            return box;
        }

        protected void commonFooter(boolean close) {
            if (close) button("Закрыть", left + 230, top + 232, 110, 20, this::onClose);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            extractMenuBackground(graphics);
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
    }

    private static final class SimpleInfoScreen extends BaseScreen {
        private final String message;
        SimpleInfoScreen(String title, String message) {
            super(Component.literal(title));
            this.message = message;
        }
        @Override protected void init() {
            super.init();
            label("Passport RP", left, top + 10, 360, 26);
            label(message, left + 20, top + 70, 320, 40);
            commonFooter(true);
        }
    }

    private static final class DeskScreen extends BaseScreen {
        private final JsonObject data;
        private boolean form;
        private EditBox surname, name, patronymic, sex, birthDate, birthPlace, citizenship, registration;

        DeskScreen(JsonObject data) {
            super(Component.literal("Паспортный стол"));
            this.data = data;
            this.form = !data.has("record");
        }

        @Override
        protected void init() {
            super.init();
            clearWidgets();
            label(form ? "Оформление паспорта" : "Управление паспортом", left, top + 6, 360, 26);
            if (form) {
                surname = edit("Фамилия", left + 10, top + 40, 165, "", 32);
                name = edit("Имя", left + 185, top + 40, 165, "", 32);
                patronymic = edit("Отчество", left + 10, top + 68, 165, "", 32);
                sex = edit("Пол: М/Ж", left + 185, top + 68, 165, "М", 1);
                birthDate = edit("Дата рождения", left + 10, top + 96, 165, "01.01.2000", 10);
                birthPlace = edit("Место рождения", left + 185, top + 96, 165, "", 120);
                citizenship = edit("Гражданство", left + 10, top + 124, 165, "Российская Федерация", 80);
                registration = edit("Регистрация", left + 185, top + 124, 165, "", 120);
                label("Серия, номер, ID, UUID, дата выдачи и орган выдачи", left + 10, top + 155, 340, 20);
                label("создаются и проверяются только сервером.", left + 10, top + 177, 340, 20);
                button("ШТАМПОВАТЬ ПАСПОРТ", left + 10, top + 204, 340, 24, () -> send(
                        "DESK_ISSUE", surname.getValue(), name.getValue(), patronymic.getValue(), sex.getValue(),
                        birthDate.getValue(), birthPlace.getValue(), citizenship.getValue(), registration.getValue()
                ));
                button("Карман для паспорта", left + 10, top + 232, 170, 20, () -> PassportModClient.openPocket());
                button("Закрыть", left + 180, top + 232, 170, 20, this::onClose);
            } else {
                String status = PassportMod.statusLabel(value(data.getAsJsonObject("record"), "status"));
                label("Статус: " + status, left + 10, top + 38, 340, 22);
                label("Серия " + value(data.getAsJsonObject("record"), "series") + "  № " + value(data.getAsJsonObject("record"), "number"), left + 10, top + 62, 340, 22);
                button("Просмотреть паспорт", left + 10, top + 90, 165, 24, () -> PassportModClient.send("VIEW_SELF"));
                button("Изменить ФИО", left + 185, top + 90, 165, 24, () -> PassportModClient.send("OPEN_NAMECHANGE"));
                button("Регистрация", left + 10, top + 118, 165, 24, () -> {
                    JsonObject record = data.getAsJsonObject("record");
                    JsonObject payload = record.deepCopy();
                    Minecraft.getInstance().setScreen(PassportScreens.create("registration", payload.toString()));
                });
                button("Брак", left + 185, top + 118, 165, 24, () -> Minecraft.getInstance().setScreen(PassportScreens.create("marriage", "{}")));
                button("Дети", left + 10, top + 146, 165, 24, () -> Minecraft.getInstance().setScreen(PassportScreens.create("children", "{}")));
                button("Военная служба", left + 185, top + 146, 165, 24, () -> Minecraft.getInstance().setScreen(PassportScreens.create("military", "{}")));
                button("Дополнительные сведения", left + 10, top + 174, 165, 24, () -> Minecraft.getInstance().setScreen(PassportScreens.create("extra", "{}")));
                if ("VALID".equals(value(data.getAsJsonObject("record"), "status"))) {
                    button("Отметить как утерянный", left + 185, top + 174, 165, 24, () -> PassportModClient.send("SET_STATUS", PassportMod.encodeFields("LOST")));
                } else {
                    button("Сделать действительным", left + 185, top + 174, 165, 24, () -> PassportModClient.send("SET_STATUS", PassportMod.encodeFields("VALID")));
                }
                button("Признать недействительным", left + 10, top + 202, 165, 24, () -> PassportModClient.send("SET_STATUS", PassportMod.encodeFields("INVALID")));
                button("Заменить паспорт", left + 185, top + 202, 165, 24, () -> PassportModClient.send("REPLACE"));
                button("Карман для паспорта", left + 10, top + 232, 165, 20, PassportModClient::openPocket);
                button("Закрыть", left + 185, top + 232, 165, 20, this::onClose);
            }
        }
    }

    private static final class PocketScreen extends BaseScreen {
        private final JsonObject data;
        PocketScreen(JsonObject data) { super(Component.literal("Карман для паспорта")); this.data = data; }
        @Override protected void init() {
            super.init();
            clearWidgets();
            label("Карман для паспорта", left, top + 8, 360, 28);
            label("Отдельный слот. Он не занимает обычные 36 мест инвентаря.", left + 10, top + 39, 340, 20);
            Button slot = button(data.get("occupied") != null && data.get("occupied").getAsBoolean() ? "[ ПАСПОРТ УСТАНОВЛЕН ]" : "[ СЛОТ ПАСПОРТА ПУСТ ]",
                    left + 60, top + 70, 240, 40, () -> {
                        if (data.get("occupied") != null && data.get("occupied").getAsBoolean()) PassportModClient.send("POCKET_VIEW");
                        else PassportModClientClientShim.noop();
                    });
            slot.setTooltip(Tooltip.create(Component.literal("Сюда можно поместить только паспорт")));
            if (data.get("occupied") == null || !data.get("occupied").getAsBoolean()) {
                button("Положить паспорт из инвентаря", left + 60, top + 116, 240, 24, () -> PassportModClient.send("POCKET_PUT"));
            } else {
                JsonObject record = data.has("record") ? data.getAsJsonObject("record") : null;
                if (record != null) {
                    label("Серия " + value(record, "series") + " № " + value(record, "number"), left + 10, top + 120, 340, 20);
                    label("Статус: " + PassportMod.statusLabel(value(record, "status")), left + 10, top + 143, 340, 20);
                }
                button("Открыть паспорт", left + 60, top + 164, 115, 24, () -> PassportModClient.send("POCKET_VIEW"));
                button("Забрать в инвентарь", left + 185, top + 164, 115, 24, () -> PassportModClient.send("POCKET_TAKE"));
            }
            label("Основной паспорт определяется именно этим слотом.", left + 10, top + 201, 340, 20);
            button("Закрыть", left + 60, top + 226, 240, 22, this::onClose);
        }
    }

    private static final class PassportViewerScreen extends BaseScreen {
        private final JsonObject data;
        private int page = 1;
        PassportViewerScreen(JsonObject data) { super(Component.literal("Паспорт")); this.data = data; }

        @Override protected void init() { super.init(); rebuildPage(); }

        private void rebuildPage() {
            clearWidgets();
            label("ПАСПОРТ RP  •  СТРАНИЦА " + page + "/12", left, top + 6, 360, 26);
            JsonObject r = data;
            switch (page) {
                case 1 -> pageMain(r);
                case 2 -> pageTechnical(r);
                case 3 -> pageRegistration(r, false);
                case 4 -> pageRegistration(r, true);
                case 5 -> pageList("БРАК", "marriageHistory", r, 0);
                case 6 -> pageList("ИСТОРИЯ БРАКА", "marriageHistory", r, 6);
                case 7 -> pageList("ВОЕННАЯ СЛУЖБА", "military", r, 0);
                case 8 -> pageList("ИСТОРИЯ ВОЕННОЙ СЛУЖБЫ", "military", r, 6);
                case 9 -> pageList("ДЕТИ", "children", r, 0);
                case 10 -> pageList("ДЕТИ / ПРОЧИЕ СВЕДЕНИЯ", "children", r, 6);
                case 11 -> pageList("ДОПОЛНИТЕЛЬНЫЕ ОТМЕТКИ", "extra", r, 0);
                case 12 -> pageHistory(r);
            }
            if (page > 1) button("«", left + 10, top + 231, 42, 22, () -> { page--; rebuildPage(); });
            button("Закрыть", left + 164, top + 231, 92, 22, this::onClose);
            if (page < 12) button("»", left + 308, top + 231, 42, 22, () -> { page++; rebuildPage(); });
        }

        private void pageMain(JsonObject r) {
            label("ГРАЖДАНИН", left + 10, top + 38, 210, 22);
            addLine("Фамилия", value(r, "surname"), 66);
            addLine("Имя", value(r, "name"), 91);
            addLine("Отчество", value(r, "patronymic"), 116);
            addLine("Гражданство", value(r, "citizenship"), 141);
            addLine("Серия / №", value(r, "series") + "  /  " + value(r, "number"), 166);
            addLine("Статус", PassportMod.statusLabel(value(r, "status")), 191);
        }

        private void pageTechnical(JsonObject r) {
            label("ОСНОВНЫЕ ДАННЫЕ", left + 10, top + 38, 340, 22);
            addLine("Пол", value(r, "sex"), 66); addLine("Дата рождения", value(r, "birthDate"), 91);
            addLine("Место рождения", value(r, "birthPlace"), 116); addLine("Дата выдачи", value(r, "issueDate"), 141);
            addLine("Кем выдан", value(r, "issuingAuthority"), 166); addLine("Код подразделения", value(r, "unitCode"), 191);
        }

        private void pageRegistration(JsonObject r, boolean history) {
            label(history ? "ИСТОРИЯ РЕГИСТРАЦИИ" : "РЕГИСТРАЦИЯ", left + 10, top + 38, 340, 22);
            if (!history) {
                addLine("Текущая регистрация", value(r, "registration"), 70);
                addLine("Записей истории", Integer.toString(arraySize(r, "registrationHistory")), 110);
                addLine("Дата выдачи", value(r, "issueDate"), 150);
            } else {
                addList(r, "registrationHistory", 68, 0, 6);
            }
        }

        private void pageList(String title, String key, JsonObject r, int start) {
            label(title, left + 10, top + 38, 340, 22);
            addList(r, key, 68, start, start + 6);
            if (start > 0 && arraySize(r, key) == 0) label("Нет записей.", left + 10, top + 80, 340, 22);
        }

        private void pageHistory(JsonObject r) {
            label("ДОПОЛНИТЕЛЬНЫЕ СВЕДЕНИЯ И ИСТОРИЯ", left + 10, top + 38, 340, 22);
            addLine("Статус", PassportMod.statusLabel(value(r, "status")), 68);
            addList(r, "extra", 94, 0, 3);
            addList(r, "history", 171, 0, 2);
            addLine("UUID владельца", value(r, "ownerUuid"), 219);
        }

        private void addLine(String key, String value, int y) {
            String text = key + ": " + shorten(value, 46);
            label(text, left + 10, top + y, 340, 22);
        }

        private void addList(JsonObject r, String key, int y, int from, int to) {
            JsonArray a = r.has(key) && r.get(key).isJsonArray() ? r.getAsJsonArray(key) : new JsonArray();
            int end = Math.min(to, a.size());
            for (int i = from; i < end; i++) {
                label("• " + shorten(a.get(i).getAsString(), 48), left + 10, top + y, 340, 23);
                y += 25;
            }
            if (from == 0 && a.size() == 0) label("Нет записей.", left + 10, top + y, 340, 22);
        }

        private int arraySize(JsonObject o, String key) {
            return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key).size() : 0;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
            if (page == 1) {
                PlayerSkin skin = resolveSkin(value(data, "ownerUuid"));
                if (skin != null) PlayerFaceExtractor.extractRenderState(graphics, skin, left + 242, top + 55, 86);
            }
        }

        private PlayerSkin resolveSkin(String uuidText) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return null;
            try {
                UUID uuid = UUID.fromString(uuidText);
                if (mc.player.getUUID().equals(uuid)) return mc.player.getSkin();
                ClientLevel level = mc.level;
                if (level != null) {
                    for (Player player : level.players()) {
                        if (player.getUUID().equals(uuid) && player instanceof AbstractClientPlayer cp) return cp.getSkin();
                    }
                }
            } catch (Exception ignored) {}
            return mc.player.getSkin();
        }

        private String shorten(String value, int max) {
            if (value == null) return "—";
            return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
        }
    }

    private static final class NameChangeScreen extends BaseScreen {
        private final JsonObject data;
        private EditBox surname, name, patronymic;
        private String scope = "ALL";
        private final long cooldownAt;
        NameChangeScreen(JsonObject data) {
            super(Component.literal("Смена ФИО"));
            this.data = data;
            this.cooldownAt = System.currentTimeMillis() + Math.max(0L, data.has("cooldownMs") ? data.get("cooldownMs").getAsLong() : 0L);
        }
        @Override protected void init() {
            super.init(); clearWidgets();
            label("Смена ФИО — голосование", left, top + 6, 360, 26);
            surname = edit("Фамилия", left + 10, top + 42, 105, value(data, "surname"), 32);
            name = edit("Имя", left + 125, top + 42, 105, value(data, "name"), 32);
            patronymic = edit("Отчество", left + 240, top + 42, 110, value(data, "patronymic"), 32);
            label("Объём изменения:", left + 10, top + 68, 110, 20);
            button("Фамилия", left + 10, top + 91, 82, 22, () -> selectScope("SURNAME"));
            button("Имя", left + 96, top + 91, 76, 22, () -> selectScope("NAME"));
            button("Отчество", left + 176, top + 91, 92, 22, () -> selectScope("PATRONYMIC"));
            button("Всё ФИО", left + 272, top + 91, 78, 22, () -> selectScope("ALL"));
            long remaining = Math.max(0L, cooldownAt - System.currentTimeMillis());
            label(remaining > 0 ? "Cooldown: " + formatClientCooldown(remaining) : "Cooldown: готово", left + 10, top + 122, 340, 22);
            label("После отправки сервер создаст голосование на 60 секунд.", left + 10, top + 147, 340, 20);
            button("Подать заявку", left + 10, top + 178, 170, 24, () -> PassportScreens.send("NAME_PROPOSE", scope, surname.getValue(), name.getValue(), patronymic.getValue()));
            button("Назад", left + 180, top + 178, 170, 24, () -> PassportModClient.send("OPEN_DESK"));
            button("Закрыть", left + 10, top + 207, 340, 22, this::onClose);
        }
        private void selectScope(String scope) { this.scope = scope; }
        private String formatClientCooldown(long ms) { long s = ms / 1000; return String.format("%d:%02d", s / 60, s % 60); }
    }

    private static final class VoteScreen extends BaseScreen {
        private final JsonObject data;
        VoteScreen(JsonObject data) { super(Component.literal("Голосование")); this.data = data; }
        @Override protected void init() {
            super.init(); clearWidgets();
            label("ГОЛОСОВАНИЕ ЗА СМЕНУ ФИО", left, top + 7, 360, 26);
            label("Смена: " + PassportModStatusText.scopeLabel(value(data, "scope")), left + 10, top + 39, 340, 22);
            label(value(data, "oldSurname") + " " + value(data, "oldName") + " " + value(data, "oldPatronymic"), left + 10, top + 67, 340, 22);
            label("→ " + value(data, "surname") + " " + value(data, "name") + " " + value(data, "patronymic"), left + 10, top + 92, 340, 22);
            label("Участников: " + value(data, "voters") + "  •  Да: " + value(data, "yes") + "  •  Нет: " + value(data, "no"), left + 10, top + 125, 340, 22);
            button("ДА", left + 10, top + 165, 165, 28, () -> PassportModClient.send("VOTE", "YES"));
            button("НЕТ", left + 185, top + 165, 165, 28, () -> PassportModClient.send("VOTE", "NO"));
            label("Большинство считается строго как > 50% от числа игроков на старте.", left + 10, top + 202, 340, 20);
        }
    }

    private static final class EntryFormScreen extends BaseScreen {
        private final String action;
        private final String title;
        private final List<String> labels;
        private final List<String> initials;
        private final List<EditBox> boxes = new ArrayList<>();
        EntryFormScreen(String action, String title, List<String> labels, List<String> initials) {
            super(Component.literal(title)); this.action = action; this.title = title; this.labels = labels; this.initials = initials;
        }
        @Override protected void init() {
            super.init(); clearWidgets(); boxes.clear();
            label(title, left, top + 8, 360, 26);
            int y = top + 44;
            for (int i = 0; i < labels.size(); i++) {
                boxes.add(edit(labels.get(i), left + 10, y, 340, i < initials.size() ? initials.get(i) : "", 300));
                y += 30;
            }
            button("Сохранить запись", left + 10, top + 202, 170, 24, this::submit);
            button("Назад к столу", left + 180, top + 202, 170, 24, () -> PassportModClient.send("OPEN_DESK"));
            button("Закрыть", left + 10, top + 231, 340, 20, this::onClose);
        }
        private void submit() {
            String[] values = boxes.stream().map(EditBox::getValue).toArray(String[]::new);
            PassportScreens.send(action, values);
        }
    }

    private static final class PassportModStatusText {
        static String scopeLabel(String scope) {
            return switch (scope) { case "SURNAME" -> "фамилии"; case "NAME" -> "имени"; case "PATRONYMIC" -> "отчества"; default -> "всего ФИО"; };
        }
    }

    private static final class PassportModClientClientShim {
        static void noop() {}
    }
}
