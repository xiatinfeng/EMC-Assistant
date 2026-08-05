package com.emc_assistant.mapper;

import moze_intel.projecte.api.mapper.recipe.RecipeTypeMapper;

/** Productive Bees（生产蜜蜂）产物加工配方 → 转换边（实体类 breeding/conversion/spawning 跳过） */
@RecipeTypeMapper(priority = 1000, requiredMods = "productivebees")
public class ProductiveBeesRecipeMapper extends AbstractEmcaRecipeMapper {
    @Override
    protected boolean isTargetType(String type) {
        return hasPrefix(type, "productivebees:") && !isKnownSkip(type);
    }
}
