package top.xinsin.pistondepth.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端显示配置的唯一数据源。这里没有任何 MaLiLib 类型，所以专用服务器加载
 * 通用模组入口时不会触发客户端 UI 类的链接。配置文件位于 config/piston_depth.json。
 * 数值范围同时在读取文件时校验，避免手工编辑文件后把标签放大到不可用的程度。
 */
public final class ClientSettings {
    private static final Logger LOGGER = LoggerFactory.getLogger("piston_depth");
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("piston_depth.json");
    private static final int[] DEFAULT_DEPTH_COLORS = {
            0xFF73E1FF, 0xFF7DFFA0, 0xFFFFE46C, 0xFFFFAF5B, 0xFFFF7A91, 0xFFD594FF
    };
    public static final int DEFAULT_TITLE_COLOR = 0xFF80E0FF;
    public static final int DEFAULT_TEXT_COLOR = 0xFFFFFFFF;
    public static final int DEFAULT_MUTED_COLOR = 0xFFD0D0D0;
    public static final int DEFAULT_BACKGROUND_COLOR = 0xB0151925;
    public static final int DEFAULT_TRIGGER_ARROW_COLOR = 0xFFFFB347;

    public static boolean enabled;
    public static boolean showPistonEvents = true;
    public static boolean showWireUpdates = true;
    public static double hudScale = 1.0;
    public static int hudX = 5;
    public static int hudY = 5;
    // 统一倍率乘在三个世界标签的单独大小上；HUD 不受此项影响。
    public static double worldTextScale = 1.0;
    public static double pistonNumberScale = 0.10;
    public static double detailScale = 0.05;
    public static double wireScale = 0.05;
    public static int renderDistance = 64;
    public static int titleColor = DEFAULT_TITLE_COLOR;
    public static int textColor = DEFAULT_TEXT_COLOR;
    public static int mutedColor = DEFAULT_MUTED_COLOR;
    public static int backgroundColor = DEFAULT_BACKGROUND_COLOR;
    public static int triggerArrowColor = DEFAULT_TRIGGER_ARROW_COLOR;
    public static int[] depthColors = DEFAULT_DEPTH_COLORS.clone();
    // Hotkey JSON belongs to MaLiLib. Keeping its object here lets commands save without erasing binds.
    private static JsonObject hotkeys = new JsonObject();

    private ClientSettings() {}

    public static int defaultDepthColor(int index) {
        return DEFAULT_DEPTH_COLORS[index];
    }

    public static void load() {
        resetToDefaults();
        if (!Files.isRegularFile(FILE)) return;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            enabled = bool(json, "enabled", enabled);
            showPistonEvents = bool(json, "showPistonEvents", showPistonEvents);
            showWireUpdates = bool(json, "showWireUpdates", showWireUpdates);
            hudScale = decimal(json, "hudScale", hudScale, 0.5, 3.0);
            hudX = integer(json, "hudX", hudX, 0, 1000);
            hudY = integer(json, "hudY", hudY, 0, 1000);
            worldTextScale = decimal(json, "worldTextScale", worldTextScale, 0.5, 5.0);
            pistonNumberScale = decimal(json, "pistonNumberScale", pistonNumberScale, 0.02, 0.25);
            detailScale = decimal(json, "detailScale", detailScale, 0.015, 0.15);
            wireScale = decimal(json, "wireScale", wireScale, 0.015, 0.15);
            // 服务端只发送玩家 96 格内的轨迹，因此更远的本地绘制距离没有意义。
            renderDistance = integer(json, "renderDistance", renderDistance, 8, 96);
            titleColor = color(json, "titleColor", titleColor);
            textColor = color(json, "textColor", textColor);
            mutedColor = color(json, "mutedColor", mutedColor);
            backgroundColor = color(json, "backgroundColor", backgroundColor);
            triggerArrowColor = color(json, "triggerArrowColor", triggerArrowColor);
            for (int i = 0; i < depthColors.length; i++) {
                depthColors[i] = color(json, "depthColor" + (i + 1), depthColors[i]);
            }
            if (json.has("hotkeys") && json.get("hotkeys").isJsonObject()) {
                hotkeys = json.getAsJsonObject("hotkeys").deepCopy();
            }
        } catch (Exception exception) {
            LOGGER.warn("Could not read {}. Defaults will be used for invalid values.", FILE, exception);
        }
    }

    private static void resetToDefaults() {
        enabled = false;
        showPistonEvents = true;
        showWireUpdates = true;
        hudScale = 1.0;
        hudX = 5;
        hudY = 5;
        worldTextScale = 1.0;
        pistonNumberScale = 0.10;
        detailScale = 0.05;
        wireScale = 0.05;
        renderDistance = 64;
        titleColor = DEFAULT_TITLE_COLOR;
        textColor = DEFAULT_TEXT_COLOR;
        mutedColor = DEFAULT_MUTED_COLOR;
        backgroundColor = DEFAULT_BACKGROUND_COLOR;
        triggerArrowColor = DEFAULT_TRIGGER_ARROW_COLOR;
        depthColors = DEFAULT_DEPTH_COLORS.clone();
        hotkeys = new JsonObject();
    }

    public static void save() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("showPistonEvents", showPistonEvents);
        json.addProperty("showWireUpdates", showWireUpdates);
        json.addProperty("hudScale", hudScale);
        json.addProperty("hudX", hudX);
        json.addProperty("hudY", hudY);
        json.addProperty("worldTextScale", worldTextScale);
        json.addProperty("pistonNumberScale", pistonNumberScale);
        json.addProperty("detailScale", detailScale);
        json.addProperty("wireScale", wireScale);
        json.addProperty("renderDistance", renderDistance);
        json.addProperty("titleColor", hex(titleColor));
        json.addProperty("textColor", hex(textColor));
        json.addProperty("mutedColor", hex(mutedColor));
        json.addProperty("backgroundColor", hex(backgroundColor));
        json.addProperty("triggerArrowColor", hex(triggerArrowColor));
        for (int i = 0; i < depthColors.length; i++) {
            json.addProperty("depthColor" + (i + 1), hex(depthColors[i]));
        }
        json.add("hotkeys", hotkeys.deepCopy());
        // 写临时文件后替换，避免游戏中途退出时留下半个 JSON 文件。
        try {
            Files.createDirectories(FILE.getParent());
            Path temporary = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(json), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOGGER.warn("Could not save {}", FILE, exception);
        }
    }

    public static void setHotkeys(JsonObject value) {
        hotkeys = value.deepCopy();
        save();
    }

    public static JsonObject hotkeys() {
        return hotkeys.deepCopy();
    }

    public static String hex(int argb) {
        return String.format(Locale.ROOT, "#%08X", argb);
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        try { return json.has(key) ? json.get(key).getAsBoolean() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static int integer(JsonObject json, String key, int fallback, int min, int max) {
        try { return json.has(key) ? Math.clamp(json.get(key).getAsInt(), min, max) : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static double decimal(JsonObject json, String key, double fallback, double min, double max) {
        try {
            double value = json.has(key) ? json.get(key).getAsDouble() : fallback;
            return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
        } catch (Exception ignored) { return fallback; }
    }

    private static int color(JsonObject json, String key, int fallback) {
        try {
            JsonElement value = json.get(key);
            if (value == null) return fallback;
            String hex = value.getAsString();
            if (!hex.matches("#[0-9A-Fa-f]{8}")) return fallback;
            return (int) Long.parseLong(hex.substring(1), 16);
        } catch (Exception ignored) { return fallback; }
    }
}
