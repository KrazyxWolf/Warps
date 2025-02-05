package world.bentobox.warps;

import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.inventory.ItemStack;
import org.eclipse.jdt.annotation.NonNull;

import world.bentobox.bentobox.api.panels.PanelItem;
import world.bentobox.bentobox.api.panels.builders.PanelBuilder;
import world.bentobox.bentobox.api.panels.builders.PanelItemBuilder;
import world.bentobox.bentobox.api.user.User;

public class WarpPanelManager {

    private static final int PANEL_MAX_SIZE = 52;
    private final Warp addon;
    // This is a cache of signs
    private final SignCacheManager signCacheManager;

    public WarpPanelManager(Warp addon) {
        this.addon = addon;
        signCacheManager = new SignCacheManager(addon);
    }

    private PanelItem getPanelItem(World world, UUID warpOwner, SignCacheItem sign) {

        PanelItemBuilder pib = new PanelItemBuilder()
                .name(addon.getSettings().getNameFormat() + addon.getPlugin().getPlayers().getName(warpOwner))
                .description(sign.getSignText())
                .clickHandler((panel, clicker, click, slot) -> hander(world, clicker, warpOwner));
        Material icon = sign.getType();
        if (icon.equals(Material.PLAYER_HEAD)) {
            return pib.icon(addon.getPlayers().getName(warpOwner)).build();
        } else {
            return pib.icon(icon).build();
        }
    }

    private boolean hander(World world, User clicker, UUID warpOwner) {
        clicker.closeInventory();
        String playerCommand = addon.getPlugin().getIWM().getAddon(world).map(gm -> gm.getPlayerCommand().map(Command::getLabel).orElse("")).orElse("");
        String command = addon.getSettings().getWarpCommand() + " " + addon.getPlayers().getName(warpOwner);
        clicker.getPlayer().performCommand((playerCommand.isEmpty() ? "" : playerCommand + " ") + command);
        return true;
    }

    private PanelItem getRandomButton(World world, User user, UUID warpOwner) {
        return new PanelItemBuilder()
                .name(addon.getSettings().getNameFormat() + user.getTranslation("warps.random"))
                .clickHandler((panel, clicker, click, slot) -> hander(world, clicker, warpOwner))
                .icon(Material.END_CRYSTAL).build();
    }

    /**
     * Show the warp panel for the user
     * @param world - world
     * @param user - user
     * @param index - page to show - 0 is first
     */
    public void showWarpPanel(World world, User user, int index) {

        PanelBuilder panelBuilder = new PanelBuilder()
                .user(user)
                .name(user.getTranslation("warps.title") + " " + (index + 1));

        buildPanel(panelBuilder, user, index, world).thenRun(panelBuilder::build);
    }

    CompletableFuture<Void> buildPanel(PanelBuilder panelBuilder, User user, int index, World world) {
        CompletableFuture<Void> r = new CompletableFuture<>();
        processSigns(r, panelBuilder, user, index, world);
        return r;
    }

    void processSigns(CompletableFuture<Void> r, PanelBuilder panelBuilder, User user, int index, World world) {
        addon.getWarpSignsManager().getSortedWarps(world).thenAccept(warps -> {
            // Cache and clean the signs
            Iterator<UUID> it = warps.iterator();
            while(it.hasNext()) {
                UUID warpOwner = it.next();
                @NonNull
                SignCacheItem sign = signCacheManager.getSignItem(world, warpOwner);
                if (!sign.isReal()) {
                    it.remove();
                    addon.getWarpSignsManager().removeWarpFromMap(world, warpOwner);
                }
            }
            // Add random warp
            getRandomWarp(warps);
            // Build the main body
            int i = buildMainBody(panelBuilder, user, index, world, warps);
            // Add navigation
            addNavigation(panelBuilder, user, world, i, index, warps.size());
            r.complete(null);
        });
    }

    private void getRandomWarp(List<UUID> warps) {
        // Add random warp
        if (!warps.isEmpty() && addon.getSettings().isRandomAllowed()) {
            warps.add(0, warps.get(new Random().nextInt(warps.size())));
        }
    }

    int buildMainBody(PanelBuilder panelBuilder, User user, int index, World world, List<UUID> warps) {
        if (index < 0) {
            index = 0;
        } else if (index > (warps.size() / PANEL_MAX_SIZE)) {
            index = warps.size() / PANEL_MAX_SIZE;
        }

        int i = index * PANEL_MAX_SIZE;
        for (; panelBuilder.getItems().size() < PANEL_MAX_SIZE && i < warps.size(); i++) {
            UUID warpOwner = warps.get(i);
            if (addon.getSettings().isRandomAllowed() && i == 0) {
                panelBuilder.item(getRandomButton(world, user, warpOwner));
            } else {
                @NonNull
                SignCacheItem sign = signCacheManager.getSignItem(world, warpOwner);
                if (sign.isReal()) {
                    panelBuilder.item(getPanelItem(world, warpOwner, sign));
                } else {
                    addon.getWarpSignsManager().removeWarpFromMap(world, warpOwner);
                }
            }
        }
        return i;
    }

    /**
     * Add Next and Previous icons to navigate
     * @param panelBuilder - the panel builder
     * @param user - user
     * @param world - world
     * @param numOfItems - number of items shown so far including in previous panels
     * @param panelNum - panel number (page)
     * @param totalNum - total number of items in the list
     */
    void addNavigation(PanelBuilder panelBuilder, User user, World world, int numOfItems, int panelNum, int totalNum) {
        // Previous
        if (panelNum > 0 && numOfItems > PANEL_MAX_SIZE) {
            // Previous
            panelBuilder.item(new PanelItemBuilder()
                    .name(user.getTranslation("warps.previous"))
                    .icon(new ItemStack(Material.COBBLESTONE))
                    .clickHandler((panel, clicker, click, slot) -> {
                        user.closeInventory();
                        showWarpPanel(world, user, panelNum-1);
                        return true;
                    }).build());
        }
        // Next
        if (numOfItems < totalNum) {
            // Next
            panelBuilder.item(new PanelItemBuilder()
                    .name(user.getTranslation("warps.next"))
                    .icon(new ItemStack(Material.STONE))
                    .clickHandler((panel, clicker, click, slot) -> {
                        user.closeInventory();
                        showWarpPanel(world, user, panelNum+1);
                        return true;
                    }).build());
        }
    }

    /**
     * Removes sign text from the cache
     * @param world - world
     * @param key - uuid of owner
     * @return true if the item was removed from the cache
     */
    public boolean removeWarp(World world, UUID key) {
        return signCacheManager.removeWarp(world, key);
    }

    public void saveCache() {
        signCacheManager.saveCache();
    }

}
