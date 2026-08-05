package com.emc_assistant.mapper;

import moze_intel.projecte.api.mapper.recipe.RecipeTypeMapper;

/** Occultism（神秘学）加工/仪式配方 → 转换边（仪式 soulCost 等成本忽略，只取材料→产物） */
@RecipeTypeMapper(priority = 1000, requiredMods = "occultism")
public class OccultismRecipeMapper extends AbstractEmcaRecipeMapper {
    @Override
    protected boolean isTargetType(String type) {
        return hasPrefix(type, "occultism:") && !isKnownSkip(type);
    }
}
