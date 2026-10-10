package com.example.adminpanel;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marks inventories created by this plugin. Menus are recognised by their holder
 * instead of by title, so other inventories (chests, the player's own inventory)
 * are never affected, and it works on every server version.
 */
public class MenuHolder implements InventoryHolder {

    public enum Type { MAIN, ANTICHEAT, REPORTS, VIOLATORS, BAN, KICK, TELEPORT }

    private final Type type;
    private final Map<Integer, UUID> slotTargets = new HashMap<>();
    private Inventory inventory;

    public MenuHolder(Type type) {
        this.type = type;
    }

    public Type getType() {
        return type;
    }

    public void setTarget(int slot, UUID target) {
        slotTargets.put(slot, target);
    }

    public UUID getTarget(int slot) {
        return slotTargets.get(slot);
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
