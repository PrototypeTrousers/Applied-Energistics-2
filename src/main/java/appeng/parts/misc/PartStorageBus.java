/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2015, AlgorithmX2, All rights reserved.
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

package appeng.parts.misc;


import appeng.api.AEApi;
import appeng.api.config.*;
import appeng.api.networking.IGridNode;
import appeng.api.networking.events.MENetworkCellArrayUpdate;
import appeng.api.networking.storage.IBaseMonitor;
import appeng.api.networking.ticking.ITickManager;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.parts.IPartModel;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.IStorageMonitorable;
import appeng.api.storage.IStorageMonitorableAccessor;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.capabilities.Capabilities;
import appeng.core.AppEng;
import appeng.core.settings.TickRates;
import appeng.core.sync.GuiBridge;
import appeng.helpers.Reflected;
import appeng.items.parts.PartModels;
import appeng.me.GridAccessException;
import appeng.me.helpers.MachineSource;
import appeng.me.storage.ITickingMonitor;
import appeng.me.storage.MEInventoryHandler;
import appeng.me.storage.MEMonitorIInventory;
import appeng.parts.PartModel;
import appeng.tile.inventory.AppEngInternalAEInventory;
import appeng.util.Platform;
import appeng.util.inv.InvOperation;
import appeng.util.prioritylist.FuzzyPriorityList;
import appeng.util.prioritylist.PrecisePriorityList;
import com.jaquadro.minecraft.storagedrawers.api.capabilities.IItemRepository;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import java.util.Objects;


public class PartStorageBus extends PartSharedStorageBus<IAEItemStack>
{
	@CapabilityInject(IItemRepository.class)
	public static Capability<IItemRepository> ITEM_REPOSITORY_CAPABILITY = null;

	public static final ResourceLocation MODEL_BASE = new ResourceLocation( AppEng.MOD_ID, "part/storage_bus_base" );

	@PartModels
	public static final IPartModel MODELS_OFF = new PartModel( MODEL_BASE, new ResourceLocation( AppEng.MOD_ID, "part/storage_bus_off" ) );

	@PartModels
	public static final IPartModel MODELS_ON = new PartModel( MODEL_BASE, new ResourceLocation( AppEng.MOD_ID, "part/storage_bus_on" ) );

	@PartModels
	public static final IPartModel MODELS_HAS_CHANNEL = new PartModel( MODEL_BASE, new ResourceLocation( AppEng.MOD_ID, "part/storage_bus_has_channel" ) );

	protected final AppEngInternalAEInventory Config = new AppEngInternalAEInventory( this, 63 );

	@Reflected
	public PartStorageBus( final ItemStack is )
	{
		super( is );
	}
	@Override
	public void onChangeInventory( final IItemHandler inv, final int slot, final InvOperation mc, final ItemStack removedStack, final ItemStack newStack )
	{
		super.onChangeInventory( inv, slot, mc, removedStack, newStack );

		if( inv == this.Config )
		{
			this.resetCache( true );
		}
	}

	@Override
	public void readFromNBT( final NBTTagCompound data )
	{
		super.readFromNBT( data );
		this.Config.readFromNBT( data, "config" );
	}

	@Override
	public void writeToNBT( final NBTTagCompound data )
	{
		super.writeToNBT( data );
		this.Config.writeToNBT( data, "config" );
	}

	@Override
	public boolean onPartActivate( final EntityPlayer player, final EnumHand hand, final Vec3d pos )
	{
		if( Platform.isServer() )
		{
			Platform.openGUI( player, this.getHost().getTile(), this.getSide(), GuiBridge.GUI_STORAGEBUS );
		}
		return true;
	}

	@Override
	public IStorageChannel<IAEItemStack> getStorageChannel()
	{
		return AEApi.instance().storage().getStorageChannel( IItemStorageChannel.class );
	}

	@Override
	public TickingRequest getTickingRequest( final IGridNode node )
	{
		return new TickingRequest( TickRates.StorageBus.getMin(), TickRates.StorageBus.getMax(), this.monitor == null, true );
	}

	@Override
	protected IMEInventory<IAEItemStack> getInventoryWrapper( TileEntity target )
	{

		EnumFacing targetSide = this.getSide().getFacing().getOpposite();

		// Prioritize a handler to directly link to another ME network
		IStorageMonitorableAccessor accessor = target.getCapability( Capabilities.STORAGE_MONITORABLE_ACCESSOR, targetSide );

		if( accessor != null )
		{
			IStorageMonitorable inventory = accessor.getInventory( this.mySrc );
			if( inventory != null )
			{
				return inventory.getInventory( AEApi.instance().storage().getStorageChannel( IItemStorageChannel.class ) );
			}

			// So this could / can be a design decision. If the tile does support our custom capability,
			// but it does not return an inventory for the action source, we do NOT fall back to using
			// IItemHandler's, as that might circumvent the security setings, and might also cause
			// performance issues.
			return null;
		}

		// Check via cap for IItemRepository
		if (ITEM_REPOSITORY_CAPABILITY != null && target.hasCapability( ITEM_REPOSITORY_CAPABILITY, targetSide ))
		{
			IItemRepository handlerRepo = target.getCapability( ITEM_REPOSITORY_CAPABILITY, targetSide );
			if( handlerRepo != null )
			{
				return new ItemRepositoryAdapter( handlerRepo, this );
			}
		}
		// Check via cap for IItemHandler
		IItemHandler handlerExt = target.getCapability( CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, targetSide );
		if( handlerExt != null )
		{
			return new ItemHandlerAdapter( handlerExt, this );
		}

		return null;

	}

	@Override
	protected int createHandlerHash( TileEntity target )
	{
		if( target == null )
		{
			return 0;
		}

		final EnumFacing targetSide = this.getSide().getFacing().getOpposite();

		if( target.hasCapability( Capabilities.STORAGE_MONITORABLE_ACCESSOR, targetSide ) )
		{
			return Objects.hash( target, target.getCapability( Capabilities.STORAGE_MONITORABLE_ACCESSOR, targetSide ) );
		}

		if (ITEM_REPOSITORY_CAPABILITY != null && target.hasCapability( ITEM_REPOSITORY_CAPABILITY, targetSide ))
		{
			final IItemRepository handlerRepo = target.getCapability( ITEM_REPOSITORY_CAPABILITY, targetSide );

			if( handlerRepo != null )
			{
				return Objects.hash( target, handlerRepo, handlerRepo.getAllItems().size() );
			}
		}

		final IItemHandler itemHandler = target.getCapability( CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, targetSide );

		if( itemHandler != null )
		{
			return Objects.hash( target, itemHandler, itemHandler.getSlots() );
		}

		return 0;
	}

	public MEInventoryHandler<IAEItemStack> getInternalHandler()
	{
		if( this.cached )
		{
			return this.handler;
		}

		final boolean wasSleeping = this.monitor == null;

		this.cached = true;
		final TileEntity self = this.getHost().getTile();
		final TileEntity target = self.getWorld().getTileEntity( self.getPos().offset( this.getSide().getFacing() ) );
		final int newHandlerHash = this.createHandlerHash( target );

		if( newHandlerHash != 0 && newHandlerHash == this.handlerHash )
		{
			return this.handler;
		}

		this.handlerHash = newHandlerHash;
		this.handler = null;
		this.monitor = null;
		if( target != null )
		{
			IMEInventory<IAEItemStack> inv = this.getInventoryWrapper( target );

			if( inv instanceof MEMonitorIInventory )
			{
				final MEMonitorIInventory h = (MEMonitorIInventory) inv;
				h.setMode( (StorageFilter) this.getConfigManager().getSetting( Settings.STORAGE_FILTER ) );
			}

			if( inv instanceof ITickingMonitor )
			{
				this.monitor = (ITickingMonitor) inv;
				this.monitor.setActionSource( new MachineSource( this ) );
				this.monitor.setMode( (StorageFilter) this.getConfigManager().getSetting( Settings.STORAGE_FILTER ) );
			}

			if( inv != null )
			{
				this.handler = new MEInventoryHandler<IAEItemStack>( inv, AEApi.instance().storage().getStorageChannel( IItemStorageChannel.class ) );

				this.handler.setBaseAccess( (AccessRestriction) this.getConfigManager().getSetting( Settings.ACCESS ) );
				this.handler.setWhitelist( this.getInstalledUpgrades( Upgrades.INVERTER ) > 0 ? IncludeExclude.BLACKLIST : IncludeExclude.WHITELIST );
				this.handler.setPriority( this.priority );

				final IItemList<IAEItemStack> priorityList = AEApi.instance().storage().getStorageChannel( IItemStorageChannel.class ).createList();

				final int slotsToUse = 18 + this.getInstalledUpgrades( Upgrades.CAPACITY ) * 9;
				for( int x = 0; x < this.Config.getSlots() && x < slotsToUse; x++ )
				{
					final IAEItemStack is = this.Config.getAEStackInSlot( x );
					if( is != null )
					{
						priorityList.add( is );
					}
				}

				if( this.getInstalledUpgrades( Upgrades.FUZZY ) > 0 )
				{
					this.handler
							.setPartitionList( new FuzzyPriorityList<IAEItemStack>( priorityList, (FuzzyMode) this.getConfigManager()
									.getSetting( Settings.FUZZY_MODE ) ) );
				}
				else
				{
					this.handler.setPartitionList( new PrecisePriorityList<IAEItemStack>( priorityList ) );
				}

				if( inv instanceof IBaseMonitor )
				{
					( (IBaseMonitor<IAEItemStack>) inv ).addListener( this, this.handler );
				}
			}
		}

		// update sleep state...
		if( wasSleeping != ( this.monitor == null ) )
		{
			try
			{
				final ITickManager tm = this.getProxy().getTick();
				if( this.monitor == null )
				{
					tm.sleepDevice( this.getProxy().getNode() );
				}
				else
				{
					tm.wakeDevice( this.getProxy().getNode() );
				}
			}
			catch( final GridAccessException e )
			{
				// :(
			}
		}

		try
		{
			// force grid to update handlers...
			this.getProxy().getGrid().postEvent( new MENetworkCellArrayUpdate() );
		}
		catch( final GridAccessException e )
		{
			// :3
		}

		return this.handler;
	}

	@Override
	public IItemHandler getInventoryByName( final String name )
	{
		if( name.equals( "config" ) )
		{
			return this.Config;
		}

		return super.getInventoryByName( name );
	}

	@Override
	public void setPriority( final int newValue )
	{
		this.priority = newValue;
		this.getHost().markForSave();
		this.resetCache( true );
	}

	@Override
	public IPartModel getStaticModels()
	{
		if( this.isActive() && this.isPowered() )
		{
			return MODELS_HAS_CHANNEL;
		}
		else if( this.isPowered() )
		{
			return MODELS_ON;
		}
		else
		{
			return MODELS_OFF;
		}
	}

	@Override
	public ItemStack getItemStackRepresentation()
	{
		return AEApi.instance().definitions().parts().storageBus().maybeStack( 1 ).orElse( ItemStack.EMPTY );
	}

	@Override
	public GuiBridge getGuiBridge()
	{
		return GuiBridge.GUI_STORAGEBUS;
	}

}
