package com.emc_assistant.mapper;

import moze_intel.projecte.api.mapper.recipe.RecipeTypeMapper;

/** Thermal 系列加工配方 → 转换边（*_catalyst 非产出边跳过） */
@RecipeTypeMapper(priority = 1000, requiredMods = "thermal_expansion")
public class ThermalRecipeMapper extends AbstractEmcaRecipeMapper {
    @Override
    protected boolean isTargetType(String type) {
        return hasPrefix(type, "thermal:") && !isKnownSkip(type);
    }
}
