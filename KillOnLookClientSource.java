// ============================================================
//  Kill on Look v1.1.5 — мод для Minecraft 1.21.4 (Fabric)
//  Все исходники в одном файле (для чтения).
//  Реально проект собирается из 5 файлов в папке killonlook\
//
//  Функции:
//   - Гуишка (бинд по умолчанию Right Shift), ESC — закрыть
//   - Ползунок «Удары в секунду» от 1 до 10
//   - Бинды: «Открыть гуишку» и «Вкл/выкл мод» — клик по кнопке,
//     затем нажми клавишу (ESC — отмена)
//   - Категории: враждебные мобы / животные / жители / игроки
//   - «Мгновенно убивать» — только одиночная игра
//   - «Аура (бить без наведения)» — бьёт ближайшую подходящую
//     сущность в ванильном радиусе атаки (3.0 блока), перед ударом
//     ОЧЕНЬ плавно доворачивая камеру к цели (30% оставшегося угла
//     за тик; пока камера крутится, ударов нет). После доводки —
//     пауза 2 тика, чтобы сервер получил поворот ДО удара.
//     Для тестов своего сервера с GrimAC.
//   - «Тригер-бот» — строгий режим: бьёт ТОЛЬКО когда прицел на цели
//     и между глазами и целью нет блоков (сквозь стены ударов нет)
//   - «Таргет-ESP» — зелёный прямоугольник вокруг текущей цели,
//     виден сквозь стены (цель проецируется в экранные координаты
//     и рисуется в HUD), с именем цели над рамкой.
//   - «Авто-спринт» — бежит вперёд сам (работает даже при выключенном
//     моде; при открытых экранах не мешает)
//   - Плюс один маленький бонус мода — найдёшь в игре сам :)
//   - Всё сохраняется в .minecraft/config/killonlook.json
// ============================================================

package com.killonlook;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.WaterCreatureEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

// ============================================================
// 1. KillOnLookClient — входная точка мода.
//    Загружает конфиг и вешает обработчик игрового тика.
// ============================================================
public class KillOnLookClient implements ClientModInitializer {

    public static KillAuraConfig config;

    @Override
    public void onInitializeClient() {
        config = KillAuraConfig.load();
        KillAuraEspRenderer.register();

        ClientTickEvents.END_CLIENT_TICK.register(client -> KillAuraHandler.tick(client, config));
    }
}

// ============================================================
// 2. KillAuraConfig — настройки мода.
//    Скорость ударов, категории, бинды, аура. Сохраняется в
//    .minecraft/config/killonlook.json (JSON, читается Gson'ом).
// ============================================================
class KillAuraConfig {

    public boolean enabled = false;
    public int hitsPerSecond = 5;       // удары в секунду (от 1 до 10)
    public boolean hostile = true;      // зомби, скелеты, крипперы и т.д.
    public boolean animals = false;     // коровы, свиньи, овцы и т.д.
    public boolean villagers = false;   // жители и бродячие торговцы
    public boolean players = false;     // игроки
    public boolean instantKill = false; // мгновенное убийство (одиночная игра)
    public boolean aura = false;        // бить без наведения (с доворотом к цели)
    public boolean esp = false;         // прямоугольник вокруг текущей цели
    public boolean triggerBot = false;  // бить только по видимой цели под прицелом
    public boolean autoSprint = false;  // автоматически бежать вперёд

    // Бинды (GLFW-коды клавиш; -1 = не назначено)
    public int bindOpenGui = 344;  // Right Shift
    public int bindToggle = 71;    // G

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Проверяет, входит ли сущность в выбранные категории. */
    public boolean matches(LivingEntity target) {
        if (target instanceof PlayerEntity) return players;
        if (target instanceof HostileEntity) return hostile;
        if (target instanceof MerchantEntity) return villagers;
        if (target instanceof AnimalEntity || target instanceof WaterCreatureEntity) return animals;
        return false;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("killonlook.json");
    }

    public static KillAuraConfig load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                KillAuraConfig cfg = GSON.fromJson(Files.readString(f), KillAuraConfig.class);
                if (cfg != null) return cfg;
            }
        } catch (Exception e) {
            System.err.println("[KillOnLook] Не удалось прочитать конфиг: " + e);
        }
        return new KillAuraConfig();
    }

    public void save() {
        try {
            Files.writeString(file(), GSON.toJson(this));
        } catch (Exception e) {
            System.err.println("[KillOnLook] Не удалось сохранить конфиг: " + e);
        }
    }
}

// ============================================================
// 3. KillAuraScreen — гуишка настроек.
//    Переключатели категорий, ползунок скорости, аура, кнопки
//    биндов (клик — затем нажимаешь клавишу, ESC — отмена).
// ============================================================
class KillAuraScreen extends Screen {

    private final KillAuraConfig cfg = KillOnLookClient.config;

    // Ожидание нажатия клавиши для перепривязки бинда
    private String listeningName = null;
    private IntSupplier listeningGetter = null;
    private Consumer<Integer> listeningSetter = null;
    private ButtonWidget listeningButton = null;

    public KillAuraScreen() {
        super(Text.literal("Kill on Look — настройки"));
    }

    @Override
    protected void init() {
        int x = this.width / 2 - 100;
        int y = 32;

        addDrawableChild(toggle(x, y, "Мод включён", () -> cfg.enabled, v -> cfg.enabled = v)); y += 19;
        addDrawableChild(slider(x, y)); y += 19;
        // категории — в два столбца, чтобы всё влезло на экран
        addDrawableChild(toggle(x, y, "Мобы", () -> cfg.hostile, v -> cfg.hostile = v));
        addDrawableChild(toggle(x + 102, y, "Животные", () -> cfg.animals, v -> cfg.animals = v)); y += 19;
        addDrawableChild(toggle(x, y, "Жители", () -> cfg.villagers, v -> cfg.villagers = v));
        addDrawableChild(toggle(x + 102, y, "Игроки", () -> cfg.players, v -> cfg.players = v)); y += 19;

        addDrawableChild(toggle(x, y, "Мгновенно убивать (одиночная игра)", () -> cfg.instantKill, v -> cfg.instantKill = v)); y += 19;
        addDrawableChild(toggle(x, y, "Аура (бить без наведения)", () -> cfg.aura, v -> cfg.aura = v)); y += 19;
        addDrawableChild(toggle(x, y, "Таргет-ESP (прямоугольник)", () -> cfg.esp, v -> cfg.esp = v)); y += 19;
        addDrawableChild(toggle(x, y, "Тригер-бот (только видимые)", () -> cfg.triggerBot, v -> cfg.triggerBot = v)); y += 19;
        addDrawableChild(toggle(x, y, "Авто-спринт", () -> cfg.autoSprint, v -> cfg.autoSprint = v)); y += 23;

        addDrawableChild(bindButton(x, y, "Открыть гуишку", () -> cfg.bindOpenGui, code -> cfg.bindOpenGui = code)); y += 19;
        addDrawableChild(bindButton(x, y, "Вкл/выкл мод", () -> cfg.bindToggle, code -> cfg.bindToggle = code));
    }

    /** Кнопка-переключатель: при клике меняет поле конфига и сразу сохраняет его в файл. */
    private ButtonWidget toggle(int x, int y, String label, BooleanSupplier get, Consumer<Boolean> set) {
        return ButtonWidget.builder(message(label, get.getAsBoolean()), button -> {
            set.accept(!get.getAsBoolean());
            button.setMessage(message(label, get.getAsBoolean()));
            cfg.save();
        }).dimensions(x, y, 200, 20).build();
    }

    private Text message(String label, boolean on) {
        return Text.literal(label + ": ")
                .append(Text.literal(on ? "ВКЛ" : "ВЫКЛ")
                        .formatted(on ? Formatting.GREEN : Formatting.RED));
    }

    /** Ползунок: удары в секунду от 1 до 10. */
    private SliderWidget slider(int x, int y) {
        double normalized = (cfg.hitsPerSecond - 1) / 9.0;
        normalized = Math.max(0.0, Math.min(1.0, normalized));
        return new SliderWidget(x, y, 200, 20, sliderMessage(), normalized) {
            @Override
            protected void updateMessage() {
                setMessage(sliderMessage());
            }

            @Override
            protected void applyValue() {
                cfg.hitsPerSecond = 1 + (int) Math.round(this.value * 9);
                cfg.save();
            }
        };
    }

    private Text sliderMessage() {
        return Text.literal("Удары в секунду: " + cfg.hitsPerSecond);
    }

    /** Кнопка бинда: клик — и следующая нажатая клавиша станет биндом (ESC — отмена). */
    private ButtonWidget bindButton(int x, int y, String name, IntSupplier get, Consumer<Integer> set) {
        return ButtonWidget.builder(bindLabel(name, get.getAsInt()), button -> {
            listeningName = name;
            listeningGetter = get;
            listeningSetter = set;
            listeningButton = button;
            button.setMessage(Text.literal(name + ": нажми клавишу (ESC — отмена)"));
        }).dimensions(x, y, 200, 20).build();
    }

    private Text bindLabel(String name, int keyCode) {
        String key = keyCode <= 0 ? "нет" : InputUtil.fromKeyCode(keyCode, 0).getLocalizedText().getString();
        return Text.literal(name + ": " + key);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Ловим клавишу для перепривязки бинда
        if (listeningName != null) {
            if (keyCode != GLFW.GLFW_KEY_ESCAPE) {
                listeningSetter.accept(keyCode);
                cfg.save();
            }
            listeningButton.setMessage(bindLabel(listeningName, listeningGetter.getAsInt()));
            listeningName = null;
            listeningGetter = null;
            listeningSetter = null;
            listeningButton = null;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("ESC — закрыть · настройки сохраняются сами"), this.width / 2, 26, 0xFFAAAAAA);
    }

    @Override
    public boolean shouldPause() {
        return false; // мир не ставится на паузу, пока открыта гуишка
    }
}

// ============================================================
// 4. KillAuraHandler — сама логика.
//    Бинды, выбор цели (прицел → ближайшая при ауре), аккуратный
//    доворот к цели перед ударом, удары с заданной скоростью,
//    мгновенное убийство в одиночной игре.
// ============================================================
class KillAuraHandler {

    /** Ванильный радиус атаки в survival — Grim проверяет именно его. */
    private static final double REACH = 3.0;

    /** Плавность доворота: доля оставшегося угла, закрываемая за тик (меньше = плавнее). */
    private static final float TURN_FACTOR = 0.3f;

    /** Цель, выбранная модом в этом тике (для ESP). */
    public static LivingEntity currentTarget = null;

    /** Сколько сущностей мод убил за сессию. */
    public static int kills = 0;

    private static LivingEntity lastHit = null;

    private static int cooldown = 0;
    private static int rotateDelay = 0; // тиков ожидания после доворота к цели
    private static boolean prevOpenDown = false;
    private static boolean prevToggleDown = false;

    private KillAuraHandler() {}

    /** Вызывается каждый игровой тик (20 раз в секунду). */
    public static void tick(MinecraftClient client, KillAuraConfig cfg) {
        // --- Бинды (опрашиваем клавиши напрямую; работает, когда нет открытых экранов) ---
        long window = client.getWindow().getHandle();
        boolean openDown = InputUtil.isKeyPressed(window, cfg.bindOpenGui);
        boolean toggleDown = cfg.bindToggle > 0 && InputUtil.isKeyPressed(window, cfg.bindToggle);

        if (openDown && !prevOpenDown && client.currentScreen == null && client.player != null) {
            client.setScreen(new KillAuraScreen());
        }
        if (toggleDown && !prevToggleDown && client.currentScreen == null && client.player != null) {
            cfg.enabled = !cfg.enabled;
            cfg.save();
            client.player.sendMessage(
                    Text.literal("Kill on Look: ")
                            .append(Text.literal(cfg.enabled ? "ВКЛ" : "ВЫКЛ")
                                    .formatted(cfg.enabled ? Formatting.GREEN : Formatting.RED)),
                    true);
        }
        prevOpenDown = openDown;
        prevToggleDown = toggleDown;

        // --- Авто-спринт (независим от главного переключателя) ---
        if (cfg.autoSprint && client.player != null && client.currentScreen == null) {
            client.options.sprintKey.setPressed(true);
        }

        // --- Учёт убийств ---
        if (lastHit != null) {
            if (lastHit.isDead() || !lastHit.isAlive()) {
                kills++;
                lastHit = null;
            } else if (client.world == null || lastHit.getWorld() != client.world) {
                lastHit = null; // сменили мир — счёт не трогаем
            }
        }

        if (cooldown > 0) cooldown--;
        if (client.player == null || !cfg.enabled || client.interactionManager == null) {
            currentTarget = null;
            return;
        }
        if (client.currentScreen != null) {
            currentTarget = null;
            if (cfg.autoSprint) {
                // при открытой гуишке сбрасываем, чтобы спринт не «залип»
                client.options.sprintKey.setPressed(false);
            }
            return;
        }

        // --- Выбор цели ---
        LivingEntity target = null;
        boolean lookingAtTarget = false;

        // 1) То, на что наведён прицел
        if (client.crosshairTarget instanceof EntityHitResult hit
                && hit.getEntity() instanceof LivingEntity le
                && le != client.player && le.isAlive() && cfg.matches(le)) {
            target = le;
            lookingAtTarget = true;
        }

        // 2) Режим «аура»: ближайшая подходящая сущность в ванильном радиусе атаки
        if (target == null && cfg.aura) {
            target = findNearest(client, cfg);
        }
        currentTarget = target;
        if (target == null) return;

        // Тригер-бот: бьём только по видимой цели (сквозь стены ударов нет)
        if (cfg.triggerBot && !canSee(client, target)) {
            currentTarget = null;
            return;
        }

        // Если бьём мимо прицела — плавно доворачиваемся к цели
        if (!lookingAtTarget) {
            if (needsRotation(client.player, target)) {
                rotateStep(client.player, target, TURN_FACTOR);
                rotateDelay = 2; // после доводки ждём пару тиков, чтобы сервер получил поворот ДО удара
                return; // пока камера крутится — не бьём
            }
        }
        if (rotateDelay > 0) {
            rotateDelay--;
            return;
        }
        if (cooldown > 0) return;

        cooldown = Math.max(1, (int) Math.round(20.0 / Math.max(1, cfg.hitsPerSecond)));

        if (cfg.instantKill && client.getServer() != null) {
            // Одиночная игра: убиваем сущность напрямую на серверной стороне
            lastHit = target;
            UUID id = target.getUuid();
            RegistryKey<World> worldKey = client.world.getRegistryKey();
            MinecraftServer server = client.getServer();
            client.player.swingHand(Hand.MAIN_HAND);
            server.execute(() -> {
                ServerWorld world = server.getWorld(worldKey);
                if (world == null) return;
                Entity e = world.getEntity(id);
                if (e instanceof LivingEntity le) {
                    le.kill(world);
                }
            });
        } else {
            // Обычный удар (работает и на серверах, но с обычным уроном)
            lastHit = target;
            client.interactionManager.attackEntity(client.player, target);
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }

    /** Ближайшая подходящая сущность в радиусе ванильной атаки (от глаз до хитбокса). */
    private static LivingEntity findNearest(MinecraftClient client, KillAuraConfig cfg) {
        ClientPlayerEntity player = client.player;
        Vec3d eye = player.getEyePos();
        double bestDist = REACH * REACH;
        LivingEntity best = null;
        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof LivingEntity le) || le == player || !le.isAlive()) continue;
            if (!cfg.matches(le)) continue;
            double d = eye.squaredDistanceTo(aimPoint(le, eye));
            if (d <= bestDist) {
                bestDist = d;
                best = le;
            }
        }
        return best;
    }

    /** Видна ли цель (нет ли блоков между глазами и точкой прицеливания). */
    private static boolean canSee(MinecraftClient client, LivingEntity target) {
        Vec3d eyes = client.player.getEyePos();
        Vec3d aim = aimPoint(target, eyes);
        BlockHitResult hit = client.world.raycast(new RaycastContext(
                eyes, aim, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    /** Точка прицеливания: ближайшая к глазам точка хитбокса (или центр, если глаза внутри). */
    private static Vec3d aimPoint(LivingEntity target, Vec3d eye) {
        Box box = target.getBoundingBox();
        if (eye.x >= box.minX && eye.x <= box.maxX
                && eye.y >= box.minY && eye.y <= box.maxY
                && eye.z >= box.minZ && eye.z <= box.maxZ) {
            return box.getCenter();
        }
        return new Vec3d(
                Math.min(Math.max(eye.x, box.minX), box.maxX),
                Math.min(Math.max(eye.y, box.minY), box.maxY),
                Math.min(Math.max(eye.z, box.minZ), box.maxZ));
    }

    private static boolean needsRotation(ClientPlayerEntity player, LivingEntity target) {
        float[] yp = anglesTowards(player, target);
        float yawDiff = Math.abs(((player.getYaw() - yp[0]) % 360.0f + 540.0f) % 360.0f - 180.0f);
        float pitchDiff = Math.abs(player.getPitch() - yp[1]);
        return yawDiff > 1.0f || pitchDiff > 1.0f;
    }

    /** Плавный доворот: за один тик закрываем долю оставшегося угла, а не весь разворот. */
    private static void rotateStep(ClientPlayerEntity player, LivingEntity target, float factor) {
        float[] yp = anglesTowards(player, target);
        float yawDiff = ((yp[0] - player.getYaw()) % 360.0f + 540.0f) % 360.0f - 180.0f;
        float pitchDiff = yp[1] - player.getPitch();
        player.setYaw(player.getYaw() + yawDiff * factor);
        player.setPitch(player.getPitch() + pitchDiff * factor);
    }

    /** Вычисляет yaw/pitch от глаз игрока к точке прицеливания. */
    private static float[] anglesTowards(ClientPlayerEntity player, LivingEntity target) {
        Vec3d eye = player.getEyePos();
        Vec3d aim = aimPoint(target, eye);
        double dx = aim.x - eye.x;
        double dy = aim.y - eye.y;
        double dz = aim.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
        return new float[]{yaw, pitch};
    }
}

// ============================================================
// 5. KillAuraEspRenderer — простой таргет-ESP.
//    Хитбокс цели проецируется в экранные координаты (в конце
//    рендера мира), прямоугольник рисуется в HUD поверх всего —
//    поэтому виден сквозь стены. Зелёная рамка + имя цели.
// ============================================================
class KillAuraEspRenderer {

    /** Экранная рамка цели в scaled-пикселях: x1, y1, x2, y2 (или null). */
    private static int[] rect = null;
    private static Entity rectTarget = null;

    private KillAuraEspRenderer() {}

    public static void register() {
        WorldRenderEvents.LAST.register(KillAuraEspRenderer::onWorldRender);
        HudRenderCallback.EVENT.register(KillAuraEspRenderer::onHudRender);
    }

    /** Проекция углов хитбокса цели на экран (в конце рендера мира). */
    private static void onWorldRender(WorldRenderContext ctx) {
        rect = null;
        rectTarget = null;

        Entity target = KillAuraHandler.currentTarget;
        if (target == null || !target.isAlive()) return;
        MatrixStack ms = ctx.matrixStack();
        if (ms == null) return;

        Box box = target.getBoundingBox().expand(0.1); // чуть увеличиваем, чтобы рамка не прилегала вплотную
        Vec3d camPos = ctx.camera().getPos();
        double cx = camPos.x, cy = camPos.y, cz = camPos.z;
        Matrix4f view = ms.peek().getPositionMatrix();
        Matrix4f proj = ctx.projectionMatrix();
        MinecraftClient client = MinecraftClient.getInstance();
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

        // 8 углов хитбокса
        for (int i = 0; i < 8; i++) {
            double wx = (i & 1) == 0 ? box.minX : box.maxX;
            double wy = (i & 2) == 0 ? box.minY : box.maxY;
            double wz = (i & 4) == 0 ? box.minZ : box.maxZ;
            Vector4f p = new Vector4f((float) (wx - cx), (float) (wy - cy), (float) (wz - cz), 1f);
            view.transform(p);
            proj.transform(p);
            if (p.w <= 0f) return; // угол за камерой — не рисуем

            int sx = Math.round((p.x / p.w * 0.5f + 0.5f) * sw);
            int sy = Math.round((1f - (p.y / p.w * 0.5f + 0.5f)) * sh);
            minX = Math.min(minX, sx);
            maxX = Math.max(maxX, sx);
            minY = Math.min(minY, sy);
            maxY = Math.max(maxY, sy);
        }

        if (maxX - minX < 2 || maxY - minY < 2) return; // слишком далеко/мелко
        rect = new int[]{minX, minY, maxX, maxY};
        rectTarget = target;
    }

    /** Рисует рамку поверх всего мира (в HUD) — поэтому видна сквозь стены. */
    private static void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
        // маленький бонус мода — счётчик убийств за сессию
        if (KillAuraHandler.kills > 0) {
            MinecraftClient client = MinecraftClient.getInstance();
            context.drawTextWithShadow(client.textRenderer,
                    Text.literal("Kill on Look · убийств: " + KillAuraHandler.kills), 4, 4, 0xFF8FFF8F);
        }

        if (!KillOnLookClient.config.esp || rect == null) return;

        int x1 = rect[0], y1 = rect[1], x2 = rect[2], y2 = rect[3];
        int col = 0xFF40FF40; // зелёный

        // лёгкая заливка + рамка
        context.fill(x1, y1, x2, y2, 0x2840FF40);
        context.fill(x1, y1, x2, y1 + 1, col);
        context.fill(x1, y2 - 1, x2, y2, col);
        context.fill(x1, y1, x1 + 1, y2, col);
        context.fill(x2 - 1, y1, x2, y2, col);

        // имя цели над рамкой
        if (rectTarget != null) {
            MinecraftClient client = MinecraftClient.getInstance();
            context.drawTextWithShadow(client.textRenderer, rectTarget.getName(), x1, y1 - 11, 0xFFE6FF70);
        }
    }
}
