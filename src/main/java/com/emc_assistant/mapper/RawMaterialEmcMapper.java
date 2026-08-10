package com.emc_assistant.mapper;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import moze_intel.projecte.api.mapper.EMCMapper;
import moze_intel.projecte.api.mapper.IEMCMapper;
import moze_intel.projecte.api.mapper.collector.IMappingCollector;
import moze_intel.projecte.api.nss.NSSFluid;
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
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * M3 产品模式：原材料预设 EMC（种子值）。快速路径版（零重操作）。
 *
 * 崩溃排查背景（2026-08-05）：原版 addMappings 在服务器资源加载关键路径上
 * 遍历全配方（17 万）+ 全物品（5.5 万 x ItemStack 构造/标签查询/方块挖掘等级），
 * 经对照实验（移除 mod 后可进存档）确认会干扰启动时序，诱发 tombstone
 * getLevel()==null NPE 与 Forge 握手 CME（堆栈无我们的代码 = 间接触发）。
 * 本版改为纯查表注册（约 150 条 setValueBefore，毫秒级），整体 try-catch 隔离，
 * 任何情况下不影响服务器启动。
 *
 * 种子来源（保持"种子 + 规则"哲学）：
 * ① 数据参考自 MIT 开源的 ProjectE Integration（TagnumElite）精确种子表（data/emcassistant/raw_emc.json，作者校准值，安全）
 * ② 16 原版染料手动钉（环敏感：染料互染/分解配方成环被 ProjectE 归零，必须先钉）
 *
 * 待恢复（config 开关，默认关）：通用 forge 标签种子 / 目标 mod 无配方闭环种子（需遍历）。
 */
@EMCMapper(priority = 2000, requiredMods = {})
public class RawMaterialEmcMapper implements IEMCMapper<NormalizedSimpleStack, Long> {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("EMC Assistant");

    /** 环敏感物品预设：原版 16 染料（min 原则 = 最便宜原料价 32，保守起步） */
    private static final Set<String> DYE_ITEMS = Set.of(
        "minecraft:white_dye", "minecraft:orange_dye", "minecraft:magenta_dye",
        "minecraft:light_blue_dye", "minecraft:yellow_dye", "minecraft:lime_dye",
        "minecraft:pink_dye", "minecraft:gray_dye", "minecraft:light_gray_dye",
        "minecraft:cyan_dye", "minecraft:purple_dye", "minecraft:blue_dye",
        "minecraft:brown_dye", "minecraft:green_dye", "minecraft:red_dye",
        "minecraft:black_dye");
    private static final long DYE_EMC = 32;

    @Override
    public String getName() {
        return "EMCA-RawMaterials";
    }

    @Override
    public String getDescription() {
        return "EMC Assistant: 原材料种子 EMC + 液体种子 + 加工产物钉值（快速路径）";
    }

    @Override
    public void addMappings(IMappingCollector<NormalizedSimpleStack, Long> collector,
                            CommentedFileConfig config,
                            ReloadableServerResources resources,
                            RegistryAccess registryAccess,
                            ResourceManager resourceManager) {
        // 整体 try-catch：预设失败只记日志，绝不影响服务器启动/世界加载
        try {
            int set = 0;

            // ① 参考 MIT 开源 ProjectE Integration 的精确种子表（item + tag，毫秒级查表，不遍历注册表）
            Map<String, Long> itemPreset = new HashMap<>();
            Map<TagKey<Item>, Long> tagPreset = new HashMap<>();
            Map<String, Long> fluidPreset = new HashMap<>();
            Map<String, Long> processedPreset = new HashMap<>();
            loadPresetTable(resourceManager, itemPreset, tagPreset, fluidPreset, processedPreset);
            for (Map.Entry<String, Long> e : itemPreset.entrySet()) {
                Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(e.getKey()));
                if (item != null && item != Items.AIR) {
                    collector.setValueBefore(NSSItem.createItem(item), e.getValue());
                    set++;
                }
            }
            for (Map.Entry<TagKey<Item>, Long> e : tagPreset.entrySet()) {
                collector.setValueBefore(NSSItem.createTag(e.getKey()), e.getValue());
                set++;
            }

            // ② 液体种子（FLUID| 前缀 → NSSFluid，液体本身在转化桌中可见 EMC）
            for (Map.Entry<String, Long> e : fluidPreset.entrySet()) {
                ResourceLocation rl = ResourceLocation.tryParse(e.getKey());
                if (rl == null) continue;
                try {
                    collector.setValueBefore(NSSFluid.createFluid(rl), e.getValue());
                    set++;
                } catch (Exception ignore) { }
            }

            // ③ 加工产物钉值（PROCESSED| 前缀 → setValueAfter，推导后覆盖：
            //    矿石/原矿被 ProjectE 黑名单清零时配方推导断链，产物需直接钉值，值 = 对应锭 EMC 全价
            //    （mod 正常量产链：粉碎→洗涤→粒→锭，按 1:1 主输出等价，用户拍板不砍半））
            for (Map.Entry<String, Long> e : processedPreset.entrySet()) {
                Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(e.getKey()));
                if (item != null && item != Items.AIR) {
                    collector.setValueAfter(NSSItem.createItem(item), e.getValue());
                    set++;
                }
            }

            // ④ 16 染料手动钉（环敏感，优先级最高）
            for (String id : DYE_ITEMS) {
                Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(id));
                if (item != null && item != Items.AIR) {
                    collector.setValueBefore(NSSItem.createItem(item), DYE_EMC);
                    set++;
                }
            }

            LOGGER.info("[EMC Assistant] 原料预设(快速路径)完成: {} 个种子", set);
        } catch (Exception e) {
            LOGGER.error("[EMC Assistant] 原料预设失败(已隔离, 不影响服务器): {}", e.getMessage());
        }
    }

    /**
     * 从资源 data/emcassistant/raw_emc.json 加载种子表（数据参考自 MIT 开源 ProjectE Integration）。
     * key 约定：#tag → 物品 tag 种子（setValueBefore）；FLUID|id → 液体种子（setValueBefore）；
     * PROCESSED|id → 加工产物钉值（setValueAfter）；其余 → 物品种子（setValueBefore）。
     */
    private static void loadPresetTable(ResourceManager rm, Map<String, Long> itemPreset, Map<TagKey<Item>, Long> tagPreset,
                                        Map<String, Long> fluidPreset, Map<String, Long> processedPreset) {
        try {
            java.util.Optional<Resource> res = rm.getResource(new ResourceLocation("emcassistant", "raw_emc.json"));
            if (res.isEmpty()) {
                LOGGER.info("[EMC Assistant] raw_emc.json 不存在，仅染料预设");
                return;
            }
            JsonObject values;
            try (java.io.InputStreamReader r = new java.io.InputStreamReader(res.get().open())) {
                values = JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("values");
            }
            for (Map.Entry<String, JsonElement> e : values.entrySet()) {
                String key = e.getKey();
                long v = e.getValue().getAsLong();
                if (key.startsWith("FLUID|")) {
                    fluidPreset.put(key.substring("FLUID|".length()), v);
                } else if (key.startsWith("PROCESSED|")) {
                    processedPreset.put(key.substring("PROCESSED|".length()), v);
                } else if (key.startsWith("#")) {
                    String loc = key.substring(1);
                    int idx = loc.indexOf(':');
                    if (idx > 0) {
                        try {
                            tagPreset.put(ItemTags.create(new ResourceLocation(loc.substring(0, idx), loc.substring(idx + 1))), v);
                        } catch (Exception ignore) { }
                    }
                } else {
                    itemPreset.put(key, v);
                }
            }
            LOGGER.info("[EMC Assistant] raw_emc.json 加载: {} item + {} tag + {} fluid + {} processed",
                    itemPreset.size(), tagPreset.size(), fluidPreset.size(), processedPreset.size());
        } catch (Exception e) {
            LOGGER.warn("[EMC Assistant] raw_emc.json 加载失败: {}", e.getMessage());
        }
    }
}
