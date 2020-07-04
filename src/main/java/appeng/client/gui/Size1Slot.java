
package appeng.client.gui;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import appeng.client.me.SlotME;
import com.mojang.datafixers.util.Pair;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.Slot;
import net.minecraft.item.ItemStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Identifier;

/**
 * A proxy for a slot that will always return an itemstack with size 1, if there
 * is an item in the slot. Used to prevent the default item count from
 * rendering.
 */
class Size1Slot extends Slot {

    private final SlotME delegate;

    public Size1Slot(SlotME delegate) {
        super(delegate.inventory, -1, delegate.x, delegate.y);
        this.delegate = delegate;
    }

    @Override
    @Nonnull
    public ItemStack getStack() {
        ItemStack orgStack = this.delegate.getStack();
        if (!orgStack.isEmpty()) {
            ItemStack modifiedStack = orgStack.copy();
            modifiedStack.setCount(1);
            return modifiedStack;
        }

        return ItemStack.EMPTY;
    }

    @Override
    public boolean hasStack() {
        return this.delegate.hasStack();
    }

    @Override
    public int getMaxStackAmount() {
        return this.delegate.getMaxStackAmount();
    }

    @Override
    public int getMaxStackAmount(ItemStack stack) {
        return this.delegate.getMaxStackAmount(stack);
    }

    @Override
    public boolean canTakeItems(PlayerEntity playerIn) {
        return this.delegate.canTakeItems(playerIn);
    }

    @Override
    public void markDirty() {
        delegate.markDirty();
    }

    @Override
    @Environment(EnvType.CLIENT)
    @Nullable
    public Pair<Identifier, Identifier> getBackgroundSprite() {
        return delegate.getBackgroundSprite();
    }

    @Override
    @Environment(EnvType.CLIENT)
    public boolean doDrawHoveringEffect() {
        return delegate.doDrawHoveringEffect();
    }

}
