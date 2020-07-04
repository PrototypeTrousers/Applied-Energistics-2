/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.client.gui.implementations;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.client.gui.widgets.ServerSettingToggleButton;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.client.gui.widgets.TabButton;
import appeng.client.gui.widgets.ToggleButton;
import appeng.container.implementations.InterfaceContainer;
import appeng.container.implementations.PriorityContainer;
import appeng.core.localization.GuiText;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.ConfigButtonPacket;
import appeng.core.sync.packets.SwitchGuisPacket;

public class InterfaceScreen extends UpgradeableScreen<InterfaceContainer> {

    private SettingToggleButton<YesNo> blockMode;
    private ToggleButton interfaceMode;

    public InterfaceScreen(InterfaceContainer container, PlayerInventory playerInventory, Text title) {
        super(container, playerInventory, title);
        this.backgroundHeight = 211;
    }

    @Override
    protected void addButtons() {
        this.addButton(new TabButton(this.x + 154, this.y, 2 + 4 * 16, GuiText.Priority.getLocal(),
                this.itemRenderer, btn -> openPriorityGui()));

        this.blockMode = new ServerSettingToggleButton<>(this.x - 18, this.y + 8, Settings.BLOCK, YesNo.NO);
        this.addButton(this.blockMode);

        this.interfaceMode = new ToggleButton(this.x - 18, this.y + 26, 84, 85,
                GuiText.InterfaceTerminal.getLocal(), GuiText.InterfaceTerminalHint.getLocal(),
                btn -> selectNextInterfaceMode());
        this.addButton(this.interfaceMode);
    }

    @Override
    public void drawFG(MatrixStack matrices, final int offsetX, final int offsetY, final int mouseX, final int mouseY) {
        if (this.blockMode != null) {
            this.blockMode.set(((InterfaceContainer) this.cvb).getBlockingMode());
        }

        if (this.interfaceMode != null) {
            this.interfaceMode.setState(((InterfaceContainer) this.cvb).getInterfaceTerminalMode() == YesNo.YES);
        }

        this.textRenderer.draw(matrices, this.getGuiDisplayName(GuiText.Interface.getLocal()), 8, 6, 4210752);

        this.textRenderer.draw(matrices, GuiText.Config.getLocal(), 8, 6 + 11 + 7, 4210752);
        this.textRenderer.draw(matrices, GuiText.StoredItems.getLocal(), 8, 6 + 60 + 7, 4210752);
        this.textRenderer.draw(matrices, GuiText.Patterns.getLocal(), 8, 6 + 73 + 7, 4210752);

        this.textRenderer.draw(matrices, GuiText.inventory.getLocal(), 8, this.backgroundHeight - 96 + 3, 4210752);
    }

    @Override
    protected String getBackground() {
        return "guis/interface.png";
    }

    private void openPriorityGui() {
        NetworkHandler.instance().sendToServer(new SwitchGuisPacket(PriorityContainer.TYPE));
    }

    private void selectNextInterfaceMode() {
        final boolean backwards = getClient().mouse.wasRightButtonClicked();
        NetworkHandler.instance().sendToServer(new ConfigButtonPacket(Settings.INTERFACE_TERMINAL, backwards));
    }

}
