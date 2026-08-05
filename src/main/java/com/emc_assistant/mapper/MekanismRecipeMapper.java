package com.emc_assistant.mapper;

import moze_intel.projecte.api.mapper.recipe.RecipeTypeMapper;

/** Mekanism（通用机械）加工配方 → 转换边（mek_data 嵌套本轮跳过） */
@RecipeTypeMapper(priority = 1000, requiredMods = "mekanism")
public class MekanismRecipeMapper extends AbstractEmcaRecipeMapper {
    @Override
    protected boolean isTargetType(String type) {
        return hasPrefix(type, "mekanism:") && !isKnownSkip(type);
    }
}
