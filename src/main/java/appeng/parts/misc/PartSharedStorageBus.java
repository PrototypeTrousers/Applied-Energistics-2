/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2018, AlgorithmX2, All rights reserved.
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
import appeng.api.networking.events.MENetworkChannelsChanged;
import appeng.api.networking.events.MENetworkEventSubscribe;
import appeng.api.networking.events.MENetworkPowerStatusChange;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IBaseMonitor;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.storage.*;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import appeng.api.util.AECableType;
import appeng.api.util.IConfigManager;
import appeng.helpers.IPriorityHost;
import appeng.me.GridAccessException;
import appeng.me.helpers.MachineSource;
import appeng.me.storage.ITickingMonitor;
import appeng.me.storage.MEInventoryHandler;
import appeng.parts.automation.PartUpgradeable;
import appeng.util.Platform;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

import java.util.Collections;
import java.util.List;


/**
 * @author BrockWS
 * @version rv6 - 22/05/2018
 * @since rv6 22/05/2018
 */
public abstract class PartSharedStorageBus<T extends IAEStack<T>> extends PartUpgradeable implements IGridTickable, ICellContainer, IPriorityHost, IMEMonitorHandlerReceiver<T>, IMEMonitor<T>
{
	protected final IActionSource mySrc;
	protected int priority = 0;
	protected boolean cached = false;
	protected ITickingMonitor monitor = null;
	protected MEInventoryHandler<T> handler = null;
	protected int handlerHash = 0;
	protected boolean wasActive = false;
	protected byte resetCacheLogic = 0;

	private final Object2ObjectMap<IMEMonitorHandlerReceiver<T>, Object> listeners = new Object2ObjectOpenHashMap<>();

	public PartSharedStorageBus( ItemStack is )
	{
		super( is );
		this.getConfigManager().registerSetting( Settings.ACCESS, AccessRestriction.READ_WRITE );
		this.getConfigManager().registerSetting( Settings.FUZZY_MODE, FuzzyMode.IGNORE_ALL );
		this.getConfigManager().registerSetting( Settings.STORAGE_FILTER, StorageFilter.EXTRACTABLE_ONLY );
		this.mySrc = new MachineSource( this );
	}

	@Override
	public boolean isValid( final Object verificationToken )
	{
		return this.handler == verificationToken;
	}

	@Override
	public void onListUpdate()
	{
		// not used here.
	}

	@Override
	public void postChange( final IBaseMonitor<T> monitor, final Iterable<T> change, final IActionSource source )
	{
		if (this.mySrc.machine().map( machine -> machine == this ).orElse( false ))
		{
			try
			{
				if( this.getProxy().isActive() )
				{
					this.getProxy().getStorage().postAlterationOfStoredItems( this.getStorageChannel(), change, this.mySrc );
				}
			}
			catch( final GridAccessException e )
			{
				// :(
			}
		}
	}

	protected void updateStatus()
	{
		final boolean currentActive = this.getProxy().isActive();
		if( this.wasActive != currentActive )
		{
			this.wasActive = currentActive;
			try
			{
				this.getProxy().getGrid().postEvent( new MENetworkCellArrayUpdate() );
				this.getHost().markForUpdate();
			}
			catch( final GridAccessException ignore )
			{
				// :P
			}
		}
	}

	@MENetworkEventSubscribe
	public void updateChannels( final MENetworkChannelsChanged changedChannels )
	{
		this.updateStatus();
	}

	/**
	 * Helper method to get this parts storage channel
	 *
	 * @return Storage channel
	 */
	public abstract IStorageChannel<T> getStorageChannel();

	private void resetCache()
	{
		final boolean fullReset = this.resetCacheLogic == 2;
		this.resetCacheLogic = 0;

		final IMEInventory<T> in = this.getInternalHandler();
		IItemList<T> before = this.getChannel().createList();
		if( in != null )
		{
			before = in.getAvailableItems( before );
		}

		this.cached = false;
		if( fullReset )
		{
			this.handlerHash = 0;
		}

		final IMEInventory<T> out = this.getInternalHandler();

		if( in != out )
		{
			IItemList<T> after = this.getChannel().createList();
			if( out != null )
			{
				after = out.getAvailableItems( after );
			}
			Platform.postListChanges( before, after, this, this.mySrc );
		}
	}

	protected void resetCache( final boolean fullReset )
	{
		if( this.getHost() == null || this.getHost().getTile() == null || this.getHost().getTile().getWorld() == null || this.getHost()
				.getTile()
				.getWorld().isRemote )
		{
			return;
		}

		if( fullReset )
		{
			this.resetCacheLogic = 2;
		}
		else
		{
			this.resetCacheLogic = 1;
		}

		try
		{
			this.getProxy().getTick().alertDevice( this.getProxy().getNode() );
		}
		catch( final GridAccessException e )
		{
			// :P
		}
	}

	@Override
	public List<IMEInventoryHandler> getCellArray( final IStorageChannel channel )
	{
		if( channel == AEApi.instance().storage().getStorageChannel( IItemStorageChannel.class ) )
		{
			final IMEInventoryHandler<T> out = this.getInternalHandler();
			if( out != null )
			{
				return Collections.singletonList( out );
			}
		}
		return Collections.emptyList();
	}

	protected abstract int createHandlerHash( TileEntity target );

	public abstract MEInventoryHandler<T> getInternalHandler();


	@Override
	public void blinkCell( int slot )
	{
	}

	@Override
	public void saveChanges( ICellInventory<?> cellInventory )
	{
	}

	@Override
	public int getPriority()
	{
		return this.priority;
	}

	@Override
	public void setPriority( final int newValue )
	{
		this.priority = newValue;
		this.getHost().markForSave();
		this.resetCache( true );
	}

	@Override
	@MENetworkEventSubscribe
	public void powerRender( final MENetworkPowerStatusChange c )
	{
		this.updateStatus();
	}

	@Override
	public void upgradesChanged()
	{
		super.upgradesChanged();
		this.resetCache( true );
	}

	@Override
	public void updateSetting( final IConfigManager manager, final Enum settingName, final Enum newValue )
	{
		this.resetCache( true );
		this.getHost().markForSave();
	}

	@Override
	public void onNeighborChanged( IBlockAccess w, BlockPos pos, BlockPos neighbor )
	{
		if( pos.offset( this.getSide().getFacing() ).equals( neighbor ) )
		{
			final TileEntity te = w.getTileEntity( neighbor );

			// In case the TE was destroyed, we have to do a full reset immediately.
			if( te == null )
			{
				this.resetCache( true );
				this.resetCache();
			}
			else
			{
				this.resetCache( false );
			}
		}
	}

	@Override
	public void readFromNBT( final NBTTagCompound data )
	{
		super.readFromNBT( data );
		this.priority = data.getInteger( "priority" );
	}

	@Override
	public void writeToNBT( final NBTTagCompound data )
	{
		super.writeToNBT( data );
		data.setInteger( "priority", this.priority );
	}

	@Override
	public void addListener( final IMEMonitorHandlerReceiver<T> l, final Object verificationToken )
	{
		this.listeners.put( l, verificationToken );
	}

	@Override
	public void removeListener( final IMEMonitorHandlerReceiver<T> l )
	{
		this.listeners.remove( l );
	}

	@Override
	public void getBoxes( final IPartCollisionHelper bch )
	{
		bch.addBox( 3, 3, 15, 13, 13, 16 );
		bch.addBox( 2, 2, 14, 14, 14, 15 );
		bch.addBox( 5, 5, 12, 11, 11, 14 );
	}

	@Override
	public TickRateModulation tickingRequest( final IGridNode node, final int ticksSinceLastCall )
	{
		if( this.resetCacheLogic != 0 )
		{
			this.resetCache();
		}

		if( this.monitor != null )
		{
			return this.monitor.onTick();
		}

		return TickRateModulation.SLEEP;
	}

	protected abstract IMEInventory<T> getInventoryWrapper( TileEntity target );


	@Override
	protected int getUpgradeSlots()
	{
		return 5;
	}

	@Override
	public float getCableConnectionLength( AECableType cable )
	{
		return 4;
	}

	@Override
	public T injectItems( T input, Actionable type, IActionSource src )
	{
		return input;
	}

	@Override
	public T extractItems( T request, Actionable mode, IActionSource src )
	{
		return null;
	}

	@Override
	public IStorageChannel<T> getChannel()
	{
		return this.getStorageChannel();
	}

	@Override
	public IItemList<T> getAvailableItems( IItemList<T> out )
	{
		if( this.getInternalHandler() != null )
		{
			return handler.getAvailableItems( out );
		}
		return this.getStorageChannel().createList();
	}

	@Override
	public IItemList<T> getStorageList()
	{
		IItemList<T> out = this.getStorageChannel().createList();
		if( this.getInternalHandler() != null )
		{
			return handler.getAvailableItems( out );
		}
		return out;
	}

	@Override
	public AccessRestriction getAccess()
	{
		return AccessRestriction.READ;
	}

	@Override
	public boolean isPrioritized( IAEStack input )
	{
		return false;
	}

	@Override
	public boolean canAccept( IAEStack input )
	{
		return false;
	}

	@Override
	public int getSlot()
	{
		return 0;
	}

	@Override
	public boolean validForPass( int i )
	{
		return false;
	}
}
