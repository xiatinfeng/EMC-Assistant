package com.emc_assistant.mapper;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import moze_intel.projecte.api.mapper.EMCMapper;
import moze_intel.projecte.api.mapper.IEMCMapper;
import moze_intel.projecte.api.mapper.collector.IMappingCollector;
import moze_intel.projecte.api.nss.NSSItem;
import moze_intel.projecte.api.nss.NormalizedSimpleStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 价值传播引擎 v1（唯材料判断，设计见 RECIPE-TRANSLATOR-DESIGN.md §15/§16）。
 *
 * 哲学：价值 = 种子（固定值下界）或"从配方图推导出的最低生产成本"。
 *   - 种子来源：raw_emc.json（item/tag/PROCESSED）+ vanilla.json（原版表数值）+ 16 染料
 *   - 种子物品 = fixed（不参与 min 压低，等价 ProjectE setValueBefore 固定值优先语义）
 *   - 无种子物品：min-迭代（类 Bellman-Ford），value = min(各配方输入价值总和 / 输出数)
 *     → 单调下降必有界 → 环自动收敛到最低；矿石（黑名单清零但引擎内部有值）经
 *     smelting 1:1 隐含值传导，crushed 等下游自动 = 锭价（PROCESSED 表退役仍保留兜底）
 *   - 输出：仅对"非 fixed 且算出值 > 0 且不在矿石/原矿黑名单 tag"的物品 setValueAfter
 *     → 只补 ProjectE 推导断链处，不覆盖原版固定值（§15.8）
 *
 * 安全（§16.4）：全程 try-catch 隔离，失败只记日志绝不影响启动；
 * 建图只存物品 id 字符串（不查方块挖掘等级/不构造额外 ItemStack）；
 * EMC clamp 2e9 防 Avaritia 类溢出；基础流体输出配方（getResultItem 空）天然跳过。
 */
@EMCMapper(priority = 1000, requiredMods = {})
public class ValuePropagationEngine implements IEMCMapper<NormalizedSimpleStack, Long> {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("EMC Assistant");

    private static final int MAX_ITERATIONS = 200;
    private static final long MAX_EMC = 2_000_000_000L;

    /** ProjectE 黑名单 tag：矿石/原矿强制 EMC=0，引擎不 setValueAfter（内部隐含值照常参与推导） */
    private static final TagKey<Item> TAG_ORES = ItemTags.create(new ResourceLocation("forge", "ores"));
    private static final TagKey<Item> TAG_RAW = ItemTags.create(new ResourceLocation("forge", "raw_materials"));

    /** 环敏感物品预设：原版 16 染料（min 原则 = 最便宜原料价 32） */
    private static final Set<String> DYE_ITEMS = Set.of(
        "minecraft:white_dye", "minecraft:orange_dye", "minecraft:magenta_dye",
        "minecraft:light_blue_dye", "minecraft:yellow_dye", "minecraft:lime_dye",
        "minecraft:pink_dye", "minecraft:gray_dye", "minecraft:light_gray_dye",
        "minecraft:cyan_dye", "minecraft:purple_dye", "minecraft:blue_dye",
        "minecraft:brown_dye", "minecraft:green_dye", "minecraft:red_dye",
        "minecraft:black_dye");
    private static final long DYE_EMC = 32;

    /** 配方边：outCount 个 out 的成本 = Σ 输入价值（1:1 材料守恒） */
    private static final class Edge {
        final String out;
        final int outCount;
        final List<String> ins;
        final List<Integer> inCounts;

        Edge(String out, int outCount, List<String> ins, List<Integer> inCounts) {
            this.out = out;
            this.outCount = outCount;
            this.ins = ins;
            this.inCounts = inCounts;
        }
    }

    @Override
    public String getName() {
        return "EMCA-ValueEngine";
    }

    @Override
    public String getDescription() {
        return "EMC Assistant: 价值传播引擎（种子 + 配方图 min-迭代，唯材料判断，只补推导断链）";
    }

    @Override
    public void addMappings(IMappingCollector<NormalizedSimpleStack, Long> collector,
                            com.electronwill.nightconfig.core.file.CommentedFileConfig config,
                            ReloadableServerResources resources,
                            RegistryAccess registryAccess,
                            ResourceManager resourceManager) {
        // 整体 try-catch：引擎任何失败只记日志，绝不影响服务器启动/世界加载（§16.4）
        int setCount = 0;
        int iterCount = 0;
        try {
            // ① 种子层（fixed = 不参与 min 压低；tag 展开为物品级）
            Map<String, Long> seeds = new HashMap<>();
            Set<String> fixed = new HashSet<>();
            loadSeeds(resourceManager, seeds, fixed);

            // ② 建图（轻量：只存 id 字符串）
            List<Edge> edges = buildGraph(resources, registryAccess);

            // ③ min-迭代（类 Bellman-Ford）
            Map<String, Long> value = new HashMap<>(seeds);
            iterCount = propagate(edges, value, fixed);

            // ④ 输出：只补"非 fixed 且 >0 且非黑名单"（原版固定值不被覆盖，§15.8）
            for (Map.Entry<String, Long> e : value.entrySet()) {
                if (fixed.contains(e.getKey())) continue;
                long v = e.getValue();
                if (v <= 0 || v > MAX_EMC) continue;
                Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(e.getKey()));
                if (item == null || item == Items.AIR) continue;
                if (inBlacklist(item)) continue;
                collector.setValueAfter(NSSItem.createItem(item), v);
                setCount++;
            }
            LOGGER.info("[EMC Assistant] 价值传播引擎完成: {} 条边, {} 个种子, 迭代 {} 轮, 钉值 {} 个",
                    edges.size(), seeds.size(), iterCount, setCount);
        } catch (Exception e) {
            LOGGER.error("[EMC Assistant] 价值传播引擎失败(已隔离, 不影响启动): {}", e.getMessage());
        }
    }

    /** 读取种子：raw_emc.json（item/tag/PROCESSED）+ vanilla.json（原版 before 数值）+ 染料；tag 展开为物品 */
    private static void loadSeeds(ResourceManager rm, Map<String, Long> seeds, Set<String> fixed) {
        // raw_emc.json：item / #tag / PROCESSED|item / FLUID|（FLUID 由 RawMaterialEmcMapper 处理，引擎跳过）
        loadJsonSeeds(rm, new ResourceLocation("emcassistant", "raw_emc.json"), seeds, fixed, false);
        // vanilla.json：values.before（原版表数值，item + #tag）
        loadJsonSeeds(rm, new ResourceLocation("emcassistant", "vanilla.json"), seeds, fixed, true);
        // 染料（环敏感，fixed）
        for (String id : DYE_ITEMS) {
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(id));
            if (item != null && item != Items.AIR) {
                seeds.put(id, DYE_EMC);
                fixed.add(id);
            }
        }
    }

    /** 解析种子 JSON（复用 raw_emc.json 的 key 约定；vanilla 模式只取 values.before） */
    private static void loadJsonSeeds(ResourceManager rm, ResourceLocation loc,
                                      Map<String, Long> seeds, Set<String> fixed, boolean vanillaOnly) {
        try {
            java.util.Optional<Resource> res = rm.getResource(loc);
            if (res.isEmpty()) {
                LOGGER.info("[EMC Assistant] {} 不存在，跳过种子", loc);
                return;
            }
            JsonObject root;
            try (java.io.InputStreamReader r = new java.io.InputStreamReader(res.get().open())) {
                root = JsonParser.parseReader(r).getAsJsonObject();
            }
            JsonObject values = root.getAsJsonObject("values");
            if (values == null) return;
            JsonObject before = values.getAsJsonObject("before"); // vanilla 格式
            if (before != null) {
                applySeedMap(before, seeds, fixed);
            }
            if (!vanillaOnly) {
                // raw_emc 格式：直接 values 里的条目（#tag / item / PROCESSED|item / FLUID|）
                for (Map.Entry<String, JsonElement> e : values.entrySet()) {
                    String key = e.getKey();
                    if (key.equals("before") || key.equals("conversion")) continue;
                    if (key.startsWith("FLUID|")) continue; // 液体由 RawMaterialEmcMapper 处理
                    long v = e.getValue().getAsLong();
                    if (key.startsWith("PROCESSED|")) {
                        applySeed(key.substring("PROCESSED|".length()), v, seeds, fixed);
                    } else if (key.startsWith("#")) {
                        expandTag(key.substring(1), v, seeds, fixed);
                    } else {
                        applySeed(key, v, seeds, fixed);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[EMC Assistant] 种子加载失败 {}: {}", loc, e.getMessage());
        }
    }

    private static void applySeedMap(JsonObject map, Map<String, Long> seeds, Set<String> fixed) {
        for (Map.Entry<String, JsonElement> e : map.entrySet()) {
            String key = e.getKey();
            long v = e.getValue().getAsLong();
            if (key.startsWith("#")) {
                expandTag(key.substring(1), v, seeds, fixed);
            } else {
                applySeed(key, v, seeds, fixed);
            }
        }
    }

    /** 单物品种子（校验注册名存在） */
    private static void applySeed(String id, long v, Map<String, Long> seeds, Set<String> fixed) {
        if (v <= 0) return;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return;
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        if (item == null || item == Items.AIR) return;
        seeds.put(id, v);
        fixed.add(id);
    }

    /** tag 种子 → 展开 tag 内所有物品（Forge tag 查询，失败静默） */
    private static void expandTag(String tagLoc, long v, Map<String, Long> seeds, Set<String> fixed) {
        if (v <= 0) return;
        int idx = tagLoc.indexOf(':');
        if (idx <= 0) return;
        try {
            TagKey<Item> tag = ItemTags.create(new ResourceLocation(tagLoc.substring(0, idx), tagLoc.substring(idx + 1)));
            for (Item item : ForgeRegistries.ITEMS.tags().getTag(tag)) {
                if (item == null || item == Items.AIR) continue;
                String id = ForgeRegistries.ITEMS.getKey(item).toString();
                seeds.put(id, v);
                fixed.add(id);
            }
        } catch (Exception ignore) {
            // tag 查询失败静默（避免启动期异常）
        }
    }

    /** 建图：RecipeManager 全量配方 → 轻量边（只存 id；输出空/输入空/通配跳过） */
    private static List<Edge> buildGraph(ReloadableServerResources resources, RegistryAccess registryAccess) {
        List<Edge> edges = new ArrayList<>();
        RecipeManager rm = resources.getRecipeManager();
        for (Recipe<?> recipe : rm.getRecipes()) {
            try {
                ItemStack out = recipe.getResultItem(registryAccess);
                if (out.isEmpty()) continue; // 流体输出/无物品输出：天然排除（§16.4 基础流体）
                String outId = ForgeRegistries.ITEMS.getKey(out.getItem()).toString();
                if (outId == null) continue;
                int outCount = out.getCount();
                if (outCount <= 0) continue;

                List<String> ins = new ArrayList<>();
                List<Integer> inCounts = new ArrayList<>();
                boolean skip = false;
                for (Ingredient ing : recipe.getIngredients()) {
                    ItemStack[] stacks = ing.getItems();
                    if (stacks.length == 0) { skip = true; break; } // 通配
                    ItemStack s = stacks[0];
                    if (s.isEmpty()) { skip = true; break; }
                    String inId = ForgeRegistries.ITEMS.getKey(s.getItem()).toString();
                    if (inId == null) { skip = true; break; }
                    ins.add(inId);
                    inCounts.add(s.getCount() > 0 ? s.getCount() : 1);
                }
                if (skip || ins.isEmpty()) continue;
                edges.add(new Edge(outId, outCount, ins, inCounts));
            } catch (Exception ignore) {
                // 单配方容错
            }
        }
        return edges;
    }

    /** min-迭代：value[out] = min(种子, 各配方 Σ输入/outCount)；fixed 不更新；返回迭代轮数 */
    private static int propagate(List<Edge> edges, Map<String, Long> value, Set<String> fixed) {
        int iterations = 0;
        boolean changed = true;
        while (changed && iterations < MAX_ITERATIONS) {
            changed = false;
            iterations++;
            for (Edge e : edges) {
                if (fixed.contains(e.out)) continue;
                long cost = 0;
                boolean allKnown = true;
                for (int i = 0; i < e.ins.size(); i++) {
                    Long iv = value.get(e.ins.get(i));
                    if (iv == null) { allKnown = false; break; }
                    long ival = iv;
                    if (ival <= 0) { allKnown = false; break; } // 0 视为未知（黑名单清零物用隐含值时另算）
                    // 溢出保护（§16.4 clamp）
                    if (e.inCounts.get(i) > 1 && ival > (MAX_EMC / e.inCounts.get(i))) {
                        allKnown = false; break;
                    }
                    cost += ival * e.inCounts.get(i);
                    if (cost > MAX_EMC) { allKnown = false; break; }
                }
                if (!allKnown) continue;
                long unit = cost / e.outCount;
                if (unit <= 0) continue;
                Long cur = value.get(e.out);
                if (cur == null || unit < cur) {
                    value.put(e.out, unit);
                    changed = true;
                }
            }
        }
        return iterations;
    }

    /** 黑名单判断：forge:ores / forge:raw_materials（失败静默返回 false，宁多不误杀） */
    private static boolean inBlacklist(Item item) {
        try {
            return ForgeRegistries.ITEMS.tags().getTag(TAG_ORES).contains(item)
                || ForgeRegistries.ITEMS.tags().getTag(TAG_RAW).contains(item);
        } catch (Exception e) {
            return false;
        }
    }
}
