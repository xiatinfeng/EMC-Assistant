package com.emc_assistant.mapper;

import moze_intel.projecte.api.mapper.collector.IMappingCollector;
import moze_intel.projecte.api.mapper.recipe.IRecipeTypeMapper;
import moze_intel.projecte.api.nss.NSSItem;
import moze_intel.projecte.api.nss.NormalizedSimpleStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.HashMap;
import java.util.Map;

/**
 * M3 产品模式基类：把"材料 → 产物"翻译成 ProjectE 转换边（1:1 材料守恒，用户拍板）。
 * 单配方解析出错返回 false 跳过，绝不崩服。
 */
public abstract class AbstractEmcaRecipeMapper implements IRecipeTypeMapper {
    @Override
    public String getName() {
        return "EMCA-" + getClass().getSimpleName();
    }

    @Override
    public String getDescription() {
        return "EMC Assistant: 将 mod 加工配方翻译为 ProjectE 转换边（材料守恒）";
    }

    /** 判定某个 recipeType 是否属于本 mapper 负责的 mod/类型集合 */
    protected abstract boolean isTargetType(String type);

    @Override
    public boolean canHandle(net.minecraft.world.item.crafting.RecipeType<?> recipeType) {
        return isTargetType(recipeType.toString());
    }

    @Override
    public boolean handleRecipe(IMappingCollector<NormalizedSimpleStack, Long> collector,
                                Recipe<?> recipe, RegistryAccess registryAccess,
                                moze_intel.projecte.api.mapper.recipe.INSSFakeGroupManager fakeGroupManager) {
        try {
            ItemStack out = recipe.getResultItem(registryAccess);
            if (out.isEmpty()) return false;

            Map<NormalizedSimpleStack, Integer> ingredients = new HashMap<>();
            for (Ingredient ing : recipe.getIngredients()) {
                ItemStack[] stacks = ing.getItems();
                if (stacks.length == 0) return false; // 通配（任意物品）→ 无法确定材料，跳过
                ItemStack s = stacks[0]; // tag/多解取第一个（保守近似，已知限制）
                if (s.isEmpty()) return false;
                NormalizedSimpleStack in = NSSItem.createItem(s);
                ingredients.merge(in, s.getCount(), Integer::sum);
            }
            if (ingredients.isEmpty()) return false;

            collector.addConversion(out.getCount(), NSSItem.createItem(out), ingredients);
            return true;
        } catch (Exception e) {
            return false; // 单配方容错
        }
    }

    /** 子类判断 type 前缀（mod: 前缀匹配） */
    protected static boolean hasPrefix(String type, String prefix) {
        return type != null && type.startsWith(prefix);
    }

    /** 排除已知坑 type（嵌套/非物品类） */
    protected static boolean isKnownSkip(String type) {
        return type.contains("conditional") || type.endsWith(":mek_data")
            || type.contains("_catalyst") || type.endsWith(":bee_breeding")
            || type.endsWith(":bee_conversion") || type.endsWith(":bee_spawning");
    }
}
