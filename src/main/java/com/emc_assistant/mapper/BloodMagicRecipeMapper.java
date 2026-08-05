package com.emc_assistant.mapper;

import moze_intel.projecte.api.mapper.recipe.RecipeTypeMapper;

/** Blood Magic（血魔法）祭坛/炼金/合成配方 → 转换边 */
@RecipeTypeMapper(priority = 1000, requiredMods = "bloodmagic")
public class BloodMagicRecipeMapper extends AbstractEmcaRecipeMapper {
    @Override
    protected boolean isTargetType(String type) {
        return hasPrefix(type, "bloodmagic:") && !isKnownSkip(type);
    }
}
