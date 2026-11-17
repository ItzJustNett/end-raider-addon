package com.endraider;

import com.endraider.modules.EndRaider;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class EndRaiderAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("End Raider");

    @Override
    public void onInitialize() {
        LOG.info("Initializing End Raider");
        Modules.get().add(new EndRaider());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.endraider";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("ItzJustNett", "end-raider-addon");
    }
}
