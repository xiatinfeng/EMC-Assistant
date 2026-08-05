package com.emc_assistant.mapper;

import moze_intel.projecte.api.mapper.recipe.RecipeTypeMapper;

/** Create（机械动力）加工配方 → 转换边 */
@RecipeTypeMapper(priority = 1000, requiredMods = "create")
public class CreateRecipeMapper extends AbstractEmcaRecipeMapper {
    @Override
    protected boolean isTargetType(String type) {
        return hasPrefix(type, "create:") && !isKnownSkip(type);
    }
}
