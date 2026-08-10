package com.emc_assistant;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

@Mod.EventBusSubscriber(modid = EMCAssistantMod.MOD_ID)
public class RegistryScanner {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final TagKey<Item> TAG_FORGE_ORES = ItemTags.create(new ResourceLocation("forge", "ores"));
    private static final TagKey<Item> TAG_FORGE_RAW_MATERIALS = ItemTags.create(new ResourceLocation("forge", "raw_materials"));
    private static final File OUTPUT_FILE = FMLPaths.GAMEDIR.get().resolve(".emc_assistant/items_snapshot.json").toFile();

    /** ProjectE VanillaRecipeTypeMapper/FallbackRecipeTypeMapper 覆盖的配方类型 → 不需要翻译 */
    private static final Set<String> VANILLA_COVERED_TYPES = Set.of(
        "minecraft:crafting_shaped", "minecraft:crafting_shapeless",
        "minecraft:smelting", "minecraft:blasting", "minecraft:smoking",
        "minecraft:campfire_cooking", "minecraft:stonecutting",
        "minecraft:smithing_transform", "minecraft:smithing_trim");

    private static boolean projecteInitialized = false;
    private static Object projecteInstance;
    private static Method projecteHasValue;
    private static Method projecteGetValue;

    private static boolean scanDone = false;
    private static Set<String> recipeOutputs = new HashSet<>();
    private static Set<String> peiOverrides = new HashSet<>();
    private static MinecraftServer currentServer;
    private static boolean scanCompleted = false;
    private static String lastScanSummary = "";
    private static boolean devMode = false;
    private static boolean integrationLoaded = false;
    private static JsonObject recipeDetailsCache = null;
    private static final File CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("emc_assistant.properties").toFile();
    /** ProjectE Integration 已覆盖的 mod → 装它时这些 mod 的翻译由 Integration runtime 处理，我们跳过避免重复 */
    private static final Set<String> INTEGRATION_COVERED_MODS = Set.of(
        "bloodmagic", "botania", "ars_nouveau", "alchemistry",
        "appliedenergistics2", "avaritia", "iceandfire",
        "farmersdelight", "chipped", "blue_skies", "alexscaves", "touhou_little_maid");
    /** 单 mod 翻译边上限（防 GT 等大 mod 爆炸） */
    private static final int MAX_EDGES_PER_MOD = 5000;

    private static void initProjectEApi() {
        if (projecteInitialized) return;

        try {
            // Path 1: IEMCProxy.INSTANCE (direct static field)
            Class<?> proxyClass = Class.forName("moze_intel.projecte.api.proxy.IEMCProxy");
            Field instField = proxyClass.getField("INSTANCE");
            projecteInstance = instField.get(null);
            projecteHasValue = proxyClass.getMethod("hasValue", ItemStack.class);
            projecteGetValue = proxyClass.getMethod("getValue", ItemStack.class);
        } catch (Exception e1) {
            try {
                // Path 2: ProjectEAPI.getEMCProxy()
                Class<?> apiClass = Class.forName("moze_intel.projecte.api.ProjectEAPI");
                Method getProxy = apiClass.getMethod("getEMCProxy");
                projecteInstance = getProxy.invoke(null);
                projecteHasValue = projecteInstance.getClass().getMethod("hasValue", ItemStack.class);
                projecteGetValue = projecteInstance.getClass().getMethod("getValue", ItemStack.class);
            } catch (Exception e2) {
                LOGGER.info("[EMC Assistant] ProjectE not installed, EMC values will be unavailable");
                return;
            }
        }

        projecteInitialized = true;
        LOGGER.info("[EMC Assistant] ProjectE API detected and initialized");
    }

    private static boolean hasEMCValue(ItemStack stack) {
        if (!projecteInitialized) return false;
        try {
            return (boolean) projecteHasValue.invoke(projecteInstance, stack);
        } catch (Exception e) {
            return false;
        }
    }

    private static long getEMCValue(ItemStack stack) {
        if (!projecteInitialized) return 0;
        try {
            return (long) projecteGetValue.invoke(projecteInstance, stack);
        } catch (Exception e) {
            return 0;
        }
    }

    private static Set<String> gatherRecipeOutputs(RecipeManager recipeManager, MinecraftServer server) {
        Set<String> outputs = new HashSet<>();
        var registryAccess = server.registryAccess();
        for (Recipe<?> recipe : List.copyOf(recipeManager.getRecipes())) {
            ItemStack result = recipe.getResultItem(registryAccess);
            if (!result.isEmpty()) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(result.getItem());
                if (id != null) outputs.add(id.toString());
            }
        }
        LOGGER.info("[EMC Assistant] Found {} recipe outputs", outputs.size());
        return outputs;
    }

    /** 完整导出配方明细（M1：recipeType 分类 + projecte_sees 粗判）。*/
    private static JsonObject gatherRecipeDetails(RecipeManager recipeManager, MinecraftServer server) {
        JsonObject result = new JsonObject();
        JsonArray recipes = new JsonArray();
        JsonObject typeStats = new JsonObject();
        var registryAccess = server.registryAccess();
        for (Recipe<?> recipe : List.copyOf(recipeManager.getRecipes())) {
            JsonObject r = new JsonObject();
            r.addProperty("id", recipe.getId().toString());
            String type = recipe.getType().toString();
            r.addProperty("type", type);
            r.addProperty("mod", type.contains(":") ? type.substring(0, type.indexOf(':')) : "minecraft");
            int covered = VANILLA_COVERED_TYPES.contains(type) ? 1 : 0;
            r.addProperty("covered", covered);

            JsonArray ingredients = new JsonArray();
            for (Ingredient ing : recipe.getIngredients()) {
                ItemStack[] stacks = ing.getItems();
                if (stacks.length == 0) {
                    ingredients.add("*"); // 通配（任意物品）
                    continue;
                }
                for (ItemStack s : stacks) {
                    ResourceLocation iid = ForgeRegistries.ITEMS.getKey(s.getItem());
                    if (iid != null) ingredients.add(iid.toString());
                }
            }
            r.add("ingredients", ingredients);

            JsonObject outObj = new JsonObject();
            ItemStack out = recipe.getResultItem(registryAccess);
            if (!out.isEmpty()) {
                ResourceLocation oid = ForgeRegistries.ITEMS.getKey(out.getItem());
                if (oid != null) outObj.addProperty("item", oid.toString());
                outObj.addProperty("count", out.getCount());
            }
            r.add("output", outObj);

            recipes.add(r);

            JsonObject st = typeStats.getAsJsonObject(type);
            if (st == null) {
                st = new JsonObject();
                st.addProperty("count", 0);
                st.addProperty("covered", covered);
                typeStats.add(type, st);
            }
            st.addProperty("count", st.get("count").getAsInt() + 1);
        }
        result.addProperty("total", recipes.size());
        result.add("recipes", recipes);
        result.add("type_stats", typeStats);
        List<String> uncoveredTypes = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : typeStats.entrySet()) {
            if (e.getValue().getAsJsonObject().get("covered").getAsInt() == 0) uncoveredTypes.add(e.getKey());
        }
        uncoveredTypes.sort((a, b) -> typeStats.getAsJsonObject(b).get("count").getAsInt() - typeStats.getAsJsonObject(a).get("count").getAsInt());
        result.addProperty("uncovered_types", uncoveredTypes.size());
        JsonArray top = new JsonArray();
        for (int i = 0; i < Math.min(5, uncoveredTypes.size()); i++) top.add(uncoveredTypes.get(i));
        result.add("uncovered_top", top);
        LOGGER.info("[EMC Assistant] Exported {} recipes across {} types, {} uncovered", recipes.size(), typeStats.size(), uncoveredTypes.size());
        return result;
    }

    private static Set<String> gatherPeiOverrides() {
        Set<String> ids = new HashSet<>();
        File dir = new File("config/projecte_integration/override");
        if (!dir.isDirectory()) return ids;
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return ids;
        for (File f : files) {
            try (FileReader r = new FileReader(f)) {
                ids.addAll(JsonParser.parseReader(r).getAsJsonObject().keySet());
            } catch (Exception e) {
                LOGGER.warn("[EMC Assistant] Failed to read PEI override {}: {}", f.getName(), e.getMessage());
            }
        }
        if (!ids.isEmpty()) {
            LOGGER.info("[EMC Assistant] Found {} PEI override items across {} files", ids.size(), files.length);
        }
        return ids;
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        currentServer = event.getServer();
        loadConfig();
        integrationLoaded = ModList.get().isLoaded("projecte_integration");
        LOGGER.info("[EMC Assistant] Dev mode: {}, ProjectE Integration loaded: {}", devMode, integrationLoaded);
        if (!devMode) {
            LOGGER.info("[EMC Assistant] Dev mode OFF, auto-scan skipped. Use /emca mode on");
            return;
        }
        if (scanDone) return;
        scanDone = true;
        if (OUTPUT_FILE.exists()) {
            LOGGER.info("[EMC Assistant] Snapshot exists, skipping");
            broadcast(currentServer, "快照已存在（跳过扫描）。输入 /emca status 查看，/emca scan 重扫");
            return;
        }
        doScan(currentServer);
    }

    private static void doScan(MinecraftServer server) {
        LOGGER.info("[EMC Assistant] === Starting Registry Scan ===");
        scanCompleted = false;
        initProjectEApi();
        recipeOutputs = gatherRecipeOutputs(server.getRecipeManager(), server);
        peiOverrides = gatherPeiOverrides();
        scanAllItems(server);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("emca")
            .then(Commands.literal("mode")
                .then(Commands.literal("on").executes(ctx -> {
                    devMode = true;
                    saveConfig();
                    ctx.getSource().sendSuccess(() -> Component.literal("[EMC Assistant] 开发模式已开启（进世界自动扫描）"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    devMode = false;
                    saveConfig();
                    ctx.getSource().sendSuccess(() -> Component.literal("[EMC Assistant] 开发模式已关闭"), false);
                    return 1;
                })))
            .then(Commands.literal("status").executes(ctx -> {
                String mode = devMode ? "ON" : "OFF";
                String msg = scanCompleted ? lastScanSummary : (scanDone ? "已扫描（旧快照）" : "尚未扫描");
                ctx.getSource().sendSuccess(() -> Component.literal("[EMC Assistant] 模式: " + mode + " | " + msg), false);
                return 1;
            }))
            .then(Commands.literal("scan").executes(ctx -> {
                if (currentServer == null) {
                    ctx.getSource().sendSuccess(() -> Component.literal("[EMC Assistant] 服务器不可用"), false);
                    return 0;
                }
                ctx.getSource().sendSuccess(() -> Component.literal("[EMC Assistant] 开始扫描..."), false);
                scanDone = false;
                scanCompleted = false;
                OUTPUT_FILE.delete();
                doScan(currentServer);
                return 1;
            }))
            .then(Commands.literal("rescan").executes(ctx -> {
                if (currentServer == null) {
                    ctx.getSource().sendSuccess(() -> Component.literal("[EMC Assistant] 服务器不可用"), false);
                    return 0;
                }
                ctx.getSource().sendSuccess(() -> Component.literal("[EMC Assistant] 开始重扫..."), false);
                scanDone = false;
                scanCompleted = false;
                OUTPUT_FILE.delete();
                doScan(currentServer);
                return 1;
            }))
            .then(Commands.literal("translate").executes(ctx -> {
                if (currentServer == null) {
                    ctx.getSource().sendSuccess(() -> Component.literal("[EMC Assistant] 服务器不可用"), false);
                    return 0;
                }
                try {
                    if (recipeDetailsCache == null) {
                        ctx.getSource().sendSuccess(() -> Component.literal("[EMC Assistant] 尚未扫描，先执行 /emca scan"), false);
                        return 0;
                    }
                    JsonObject conv = buildConversions(recipeDetailsCache.getAsJsonArray("recipes"));
                    int n = writeDatapack(currentServer, conv);
                    ctx.getSource().sendSuccess(() -> Component.literal("[EMC Assistant] 已生成 " + n + " 条转换边 → datapacks/emc_assistant_pack。执行 /reload 后 /projecte reloademc 生效"), false);
                } catch (Exception e) {
                    LOGGER.error("[EMC Assistant] translate failed", e);
                    ctx.getSource().sendSuccess(() -> Component.literal("[EMC Assistant] 翻译失败: " + e.getMessage()), false);
                }
                return 1;
            }))
            .then(Commands.literal("missing").executes(ctx -> {
                if (currentServer == null) {
                    ctx.getSource().sendSuccess(() -> Component.literal("[EMC Assistant] 服务器不可用"), false);
                    return 0;
                }
                try {
                    initProjectEApi();
                    if (!projecteInitialized) {
                        ctx.getSource().sendSuccess(() -> Component.literal("[EMC Assistant] ProjectE 未加载，无法检测缺失"), false);
                        return 0;
                    }
                    int n = writeMissingReport();
                    ctx.getSource().sendSuccess(() -> Component.literal("[EMC Assistant] 缺失报告已输出: logs/emc_assistant/missing_report.txt（共 " + n + " 个无 EMC 物品）"), false);
                } catch (Exception e) {
                    LOGGER.error("[EMC Assistant] missing report failed", e);
                    ctx.getSource().sendSuccess(() -> Component.literal("[EMC Assistant] 缺失报告生成失败: " + e.getMessage()), false);
                }
                return 1;
            })));
    }

    /** 扫描无 EMC 物品，按 mod 分组写 logs/emc_assistant/missing_report.txt */
    private static int writeMissingReport() throws IOException {
        Map<String, Integer> modCount = new HashMap<>();
        int total = 0;
        for (Map.Entry<ResourceKey<Item>, Item> entry : List.copyOf(ForgeRegistries.ITEMS.getEntries())) {
            ItemStack stack = new ItemStack(entry.getValue());
            if (!hasEMCValue(stack)) {
                total++;
                modCount.merge(entry.getKey().location().getNamespace(), 1, Integer::sum);
            }
        }
        File dir = new File("logs/emc_assistant");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "missing_report.txt");
        StringBuilder sb = new StringBuilder();
        sb.append("EMC Assistant Missing Report\n");
        sb.append("generated_at: ").append(Instant.now()).append('\n');
        sb.append("total_missing: ").append(total).append("\n\n");
        sb.append("=== 按 mod 分组（Top 30）===\n");
        modCount.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(30)
            .forEach(e -> sb.append(String.format("%-30s %d\n", e.getKey(), e.getValue())));
        try (FileWriter w = new FileWriter(f)) {
            w.write(sb.toString());
        }
        LOGGER.info("[EMC Assistant] missing report written: {} ({} items)", f.getAbsolutePath(), total);
        return total;
    }

    private static void broadcast(MinecraftServer server, String msg) {
        LOGGER.info("[EMC Assistant] {}", msg);
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(Component.literal("[EMC Assistant] " + msg), false);
        }
    }

    /** 未覆盖 recipeType → 转换边（1:1 材料守恒；跳过通配/已知坑类型/Integration 已覆盖 mod/单 mod 上限） */
    private static JsonObject buildConversions(JsonArray recipes) {
        JsonObject root = new JsonObject();
        root.addProperty("comment", "Generated by EMC Assistant. 机器/仪式配方 -> ProjectE 转换边（1:1 材料守恒）");
        JsonObject groups = new JsonObject();
        Map<String, JsonArray> groupMap = new HashMap<>();
        Map<String, Integer> perModCount = new HashMap<>();
        Set<String> skipTypes = Set.of("forge:conditional", "mekanism:mek_data");
        int converted = 0, skipped = 0, capped = 0, intSkipped = 0;
        for (JsonElement el : recipes) {
            JsonObject r = el.getAsJsonObject();
            if (r.get("covered").getAsInt() == 1) continue;
            String type = r.get("type").getAsString();
            String mod = r.get("mod").getAsString();
            if (skipTypes.contains(type) || type.startsWith("minecraft:")) { skipped++; continue; }
            if (integrationLoaded && INTEGRATION_COVERED_MODS.contains(mod)) { intSkipped++; continue; }
            JsonObject output = r.getAsJsonObject("output");
            if (output == null || !output.has("item")) { skipped++; continue; }
            boolean wildcard = false;
            for (JsonElement ing : r.getAsJsonArray("ingredients")) {
                if ("*".equals(ing.getAsString())) { wildcard = true; break; }
            }
            if (wildcard) { skipped++; continue; }
            if (perModCount.getOrDefault(mod, 0) >= MAX_EDGES_PER_MOD) { capped++; continue; }
            JsonObject conv = new JsonObject();
            if (output.has("count") && output.get("count").getAsInt() > 1) {
                conv.addProperty("count", output.get("count").getAsInt());
            }
            conv.add("ingredients", r.getAsJsonArray("ingredients"));
            conv.addProperty("output", output.get("item").getAsString());
            groupMap.computeIfAbsent(mod, k -> new JsonArray()).add(conv);
            perModCount.put(mod, perModCount.getOrDefault(mod, 0) + 1);
            converted++;
        }
        for (Map.Entry<String, JsonArray> e : groupMap.entrySet()) {
            JsonObject g = new JsonObject();
            g.addProperty("comment", "由 EMC Assistant 从 " + e.getKey() + " 配方生成");
            g.add("conversions", e.getValue());
            groups.add(e.getKey(), g);
        }
        root.add("groups", groups);
        LOGGER.info("[EMC Assistant] Converted {} edges, skipped {} (坑/通配/输出缺失), integration-skipped {}, capped {}",
            converted, skipped, intSkipped, capped);
        return root;
    }

    /** 写 datapack: world/datapacks/emc_assistant_pack/data/emc_assistant/pe_custom_conversions/machines.json */
    private static int writeDatapack(MinecraftServer server, JsonObject conversions) throws IOException {
        Path packRoot = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("emc_assistant_pack");
        Path dataDir = packRoot.resolve("data/emc_assistant/pe_custom_conversions");
        Files.createDirectories(dataDir);
        JsonObject meta = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "EMC Assistant generated conversions");
        pack.addProperty("pack_format", 15); // 1.20.1 datapack format
        meta.add("pack", pack);
        Files.write(packRoot.resolve("pack.mcmeta"), GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
        Files.write(dataDir.resolve("machines.json"), GSON.toJson(conversions).getBytes(StandardCharsets.UTF_8));
        int n = 0;
        for (Map.Entry<String, JsonElement> ge : conversions.getAsJsonObject("groups").entrySet()) {
            n += ge.getValue().getAsJsonObject().getAsJsonArray("conversions").size();
        }
        return n;
    }

    private static void loadConfig() {
        Properties p = new Properties();
        if (CONFIG_FILE.exists()) {
            try (FileReader r = new FileReader(CONFIG_FILE)) { p.load(r); }
            catch (Exception e) { LOGGER.warn("[EMC Assistant] config load failed: {}", e.getMessage()); }
        }
        devMode = "on".equalsIgnoreCase(p.getProperty("dev_mode", "off"));
    }

    private static void saveConfig() {
        try {
            Properties p = new Properties();
            p.setProperty("dev_mode", devMode ? "on" : "off");
            if (CONFIG_FILE.getParentFile() != null) CONFIG_FILE.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(CONFIG_FILE)) { p.store(w, "EMC Assistant"); }
        } catch (Exception e) {
            LOGGER.warn("[EMC Assistant] config save failed: {}", e.getMessage());
        }
    }

    private static void scanAllItems(MinecraftServer server) {
        LOGGER.info("[EMC Assistant] === Starting Registry Scan ===");
        JsonObject root = new JsonObject();
        root.addProperty("generated_at", Instant.now().toString());
        root.addProperty("game_version", "1.20.1");
        root.addProperty("mod_version", "1.0.0");
        root.addProperty("projecte_available", projecteInitialized);

        JsonArray itemsArray = new JsonArray();
        JsonObject modGroups = new JsonObject();
        int total = 0, withEMC = 0, withoutEMC = 0;

        for (Map.Entry<ResourceKey<Item>, Item> entry : List.copyOf(ForgeRegistries.ITEMS.getEntries())) {
            ResourceLocation id = entry.getKey().location();
            ItemStack stack = new ItemStack(entry.getValue());
            total++;
            boolean hasEMC = projecteInitialized && hasEMCValue(stack);
            String key = id.toString();

            int burnTime = ForgeHooks.getBurnTime(stack, null);
            boolean isOre = stack.is(TAG_FORGE_ORES);
            boolean isRawMat = stack.is(TAG_FORGE_RAW_MATERIALS);

            int harvestLevel = 0;
            if (entry.getValue() instanceof BlockItem bi) {
                Block b = bi.getBlock();
                if (b != Blocks.AIR) {
                    BlockState bs = b.defaultBlockState();
                    if (bs.is(BlockTags.NEEDS_DIAMOND_TOOL)) harvestLevel = 3;
                    else if (bs.is(BlockTags.NEEDS_IRON_TOOL)) harvestLevel = 2;
                    else if (bs.is(BlockTags.NEEDS_STONE_TOOL)) harvestLevel = 1;
                }
            }

            JsonObject itemObj = new JsonObject();
            itemObj.addProperty("registry_name", key);
            itemObj.addProperty("has_emc", hasEMC);
            if (hasEMC) itemObj.addProperty("emc_value", getEMCValue(stack));
            itemObj.addProperty("has_pei_override", peiOverrides.contains(key));
            itemObj.addProperty("has_producing_recipe", recipeOutputs.contains(key));
            itemObj.addProperty("is_fuel", burnTime > 0);
            itemObj.addProperty("burn_time", burnTime);
            itemObj.addProperty("is_high_confidence_raw_material", (isOre || isRawMat) && !recipeOutputs.contains(key));
            itemObj.addProperty("is_raw_material_candidate", (isOre || isRawMat) && !recipeOutputs.contains(key));
            itemObj.addProperty("harvest_level", harvestLevel);

            JsonArray reasons = new JsonArray();
            if (isOre) reasons.add("tag:forge:ores");
            if (isRawMat) reasons.add("tag:forge:raw_materials");
            if (harvestLevel > 0) reasons.add("harvest_level:" + harvestLevel);
            itemObj.add("confidence_reasons", reasons);

            JsonObject cls = new JsonObject();
            cls.addProperty("is_ore", isOre);
            cls.addProperty("is_raw_material", isRawMat);
            cls.addProperty("has_producing_recipe", recipeOutputs.contains(key));
            cls.addProperty("is_raw_material_candidate", (isOre || isRawMat) && !recipeOutputs.contains(key));
            itemObj.add("classifications", cls);

            JsonArray tags = new JsonArray();
            try {
                entry.getValue().builtInRegistryHolder().tags()
                    .map(t -> t.location().toString()).forEach(tags::add);
            } catch (Exception ignored) {}
            itemObj.add("tags", tags);

            itemsArray.add(itemObj);
            if (hasEMC) withEMC++; else withoutEMC++;

            String modId = id.getNamespace();
            JsonObject grp = modGroups.getAsJsonObject(modId);
            if (grp == null) {
                grp = new JsonObject();
                grp.addProperty("mod_id", modId);
                grp.addProperty("item_count", 0);
                grp.addProperty("items_with_emc", 0);
                grp.addProperty("items_without_emc", 0);
                grp.add("items", new JsonArray());
                modGroups.add(modId, grp);
            }
            grp.addProperty("item_count", grp.get("item_count").getAsInt() + 1);
            if (hasEMC) grp.addProperty("items_with_emc", grp.get("items_with_emc").getAsInt() + 1);
            else grp.addProperty("items_without_emc", grp.get("items_without_emc").getAsInt() + 1);
            grp.getAsJsonArray("items").add(itemObj);
        }

        root.addProperty("total_items_scanned", total);
        root.addProperty("items_with_emc", withEMC);
        root.addProperty("items_without_emc", withoutEMC);
        root.add("items", itemsArray);
        root.add("mod_groups", modGroups);

        JsonObject recipeDetails = gatherRecipeDetails(server.getRecipeManager(), server);
        recipeDetailsCache = recipeDetails;
        root.addProperty("recipes_total", recipeDetails.get("total").getAsInt());
        root.add("recipes", recipeDetails.getAsJsonArray("recipes"));
        root.add("recipe_type_stats", recipeDetails.getAsJsonObject("type_stats"));

        File outDir = OUTPUT_FILE.getParentFile();
        if (!outDir.exists()) outDir.mkdirs();
        try (FileWriter w = new FileWriter(OUTPUT_FILE)) {
            GSON.toJson(root, w);
            scanCompleted = true;
            int recipesTotal = recipeDetails.get("total").getAsInt();
            int uncovered = recipeDetails.get("uncovered_types").getAsInt();
            String top = recipeDetails.get("uncovered_top").getAsJsonArray().size() > 0
                ? "，如 " + recipeDetails.get("uncovered_top").getAsJsonArray().get(0).getAsString() + " 等" : "";
            lastScanSummary = String.format("扫描完成: %d 物品 (%d 有 EMC / %d 无), %d 配方, %d 种未覆盖配方类型%s",
                total, withEMC, withoutEMC, recipesTotal, uncovered, top);
            LOGGER.info("[EMC Assistant] Snapshot written: {} ({} items, {} with EMC, {} without)",
                OUTPUT_FILE.getAbsolutePath(), total, withEMC, withoutEMC);
            broadcast(server, lastScanSummary);
        } catch (Exception e) {
            LOGGER.error("[EMC Assistant] Failed to write snapshot: {}", e.getMessage());
        }
    }
}