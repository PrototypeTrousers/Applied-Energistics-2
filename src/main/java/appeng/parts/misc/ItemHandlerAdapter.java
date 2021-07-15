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

package appeng.parts.misc;


import javax.annotation.Nullable;
import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.Settings;
import appeng.api.config.StorageFilter;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IBaseMonitor;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEMonitorHandlerReceiver;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.core.AELog;
import appeng.me.GridAccessException;
import appeng.me.helpers.IGridProxyable;
import appeng.me.storage.ITickingMonitor;
import appeng.util.inv.ItemHandlerIterator;
import appeng.util.inv.ItemSlot;
import appeng.util.item.AEItemStack;
import com.google.common.primitives.Ints;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import java.util.*;


/**
 * Wraps an Item Handler in such a way that it can be used as an IMEInventory for items.
 */
class ItemHandlerAdapter implements IMEInventory<IAEItemStack>, IBaseMonitor<IAEItemStack>, ITickingMonitor
{
	private final Object2ObjectMap<IMEMonitorHandlerReceiver<IAEItemStack>, Object> listeners = new Object2ObjectOpenHashMap<>();
	private IActionSource mySource;
	private final IItemHandler itemHandler;
	private final IGridProxyable proxyable;
	private final InventoryCache cache;
	private StorageFilter mode;

	private ItemStack stackCache = null;

	ItemHandlerAdapter( IItemHandler itemHandler, IGridProxyable proxy )
	{
		this.itemHandler = itemHandler;
		this.proxyable = proxy;
		if( this.proxyable instanceof PartStorageBus )
		{
			PartStorageBus partStorageBus = (PartStorageBus) this.proxyable;
			this.mode = ( (StorageFilter) partStorageBus.getConfigManager().getSetting( Settings.STORAGE_FILTER ) );
		}
		this.cache = new InventoryCache( this.itemHandler, this.mode );
	}

	@Override
	public IAEItemStack injectItems( IAEItemStack iox, Actionable type, IActionSource src )
	{
		// Try to reuse the cached stack
		@Nullable ItemStack currentCached = stackCache;
		stackCache = null;

		ItemStack orgInput;
		int slotCount = this.itemHandler.getSlots();

		if( currentCached != null && iox.isSameType( currentCached ) )
		{
			// Cache is suitable, just update the count
			orgInput = currentCached;
			currentCached.setCount( Ints.saturatedCast( iox.getStackSize() ) );
		}
		else
		{
			// We need a new stack :-(
			orgInput = iox.createItemStack();
		}

		ItemStack remaining = orgInput;

		for ( int i = 0; i < slotCount && !remaining.isEmpty(); i++ )
		{
			remaining = this.itemHandler.insertItem( i, remaining, type == Actionable.SIMULATE );
		}

		// Store the stack in the cache for next time.
		if( !remaining.isEmpty() && remaining != orgInput )
		{
			stackCache = remaining;
		}

		// At this point, we still have some items left...
		if( remaining == orgInput )
		{
			// The stack remained unmodified, target inventory is full
			return iox;
		}

		if( type == Actionable.MODULATE )
		{
			try
			{
				this.proxyable.getProxy().getTick().alertDevice( this.proxyable.getProxy().getNode() );
			}
			catch( GridAccessException e )
			{
				e.printStackTrace();
			}
		}

		return AEItemStack.fromItemStack( remaining );
	}

	@Override
	public IAEItemStack extractItems( IAEItemStack request, Actionable mode, IActionSource src )
	{
		int remainingSize = Ints.saturatedCast( request.getStackSize() );

		// Use this to gather the requested items
		ItemStack gathered = ItemStack.EMPTY;

		final boolean simulate = ( mode == Actionable.SIMULATE );
		for ( int i = 0; i < this.itemHandler.getSlots(); i++ )
		{
			ItemStack stackInInventorySlot = this.itemHandler.getStackInSlot( i );

			if( !request.isSameType( stackInInventorySlot ) )
			{
				continue;
			}

			ItemStack extracted;
			int stackSizeCurrentSlot = stackInInventorySlot.getCount();
			int remainingCurrentSlot = Math.min( remainingSize, stackSizeCurrentSlot );

			// We have to loop here because according to the docs, the handler shouldn't return a stack with size >
			// maxSize, even if we request more. So even if it returns a valid stack, it might have more stuff.
			do
			{
				extracted = this.itemHandler.extractItem( i, remainingCurrentSlot, simulate );

				if( !extracted.isEmpty() )
				{
					if( extracted.getCount() > remainingCurrentSlot )
					{
						// Something broke. It should never return more than we requested...
						// We're going to silently eat the remainder
						AELog.warn( "Mod that provided item handler %s is broken. Returned %s items while only requesting %d.",
								this.itemHandler.getClass().getName(), extracted.toString(), remainingCurrentSlot );
						extracted.setCount( remainingCurrentSlot );
					}

					// We're just gonna use the first stack we get our hands on as the template for the rest.
					// In case some stupid itemhandler (aka forge) returns an internal state we have to do a second
					// expensive copy again.
					if( gathered.isEmpty() )
					{
						gathered = extracted.copy();
					}
					else
					{
						gathered.grow( extracted.getCount() );
					}
					remainingCurrentSlot -= extracted.getCount();
				}
			} while ( !extracted.isEmpty() && remainingCurrentSlot > 0 );

			remainingSize -= stackSizeCurrentSlot - remainingCurrentSlot;

			// Done?
			if( remainingSize <= 0 )
			{
				break;
			}
		}

		if( !gathered.isEmpty() )
		{
			IAEItemStack gatheredAEItemStack = AEItemStack.fromItemStack( gathered );
			if( mode == Actionable.MODULATE )
			{
				try
				{
					this.proxyable.getProxy().getTick().alertDevice( this.proxyable.getProxy().getNode() );
				}
				catch( GridAccessException e )
				{
					e.printStackTrace();
				}
			}

			return gatheredAEItemStack;
		}

		return null;
	}

	@Override
	public TickRateModulation onTick()
	{
		List<IAEItemStack> changes = this.cache.update();
		if( !changes.isEmpty() )
		{
			this.postDifference( changes );
			return TickRateModulation.URGENT;
		}
		else
		{
			return TickRateModulation.SLOWER;
		}
	}

	@Override
	public void setActionSource( final IActionSource mySource )
	{
		this.mySource = mySource;
	}

	@Override
	public IItemList<IAEItemStack> getAvailableItems( IItemList<IAEItemStack> out )
	{
		return this.cache.getAvailableItems( out );
	}

	@Override
	public IItemStorageChannel getChannel()
	{
		return AEApi.instance().storage().getStorageChannel( IItemStorageChannel.class );
	}

	@Override
	public void addListener( final IMEMonitorHandlerReceiver<IAEItemStack> l, final Object verificationToken )
	{
		this.listeners.put( l, verificationToken );
	}

	@Override
	public void removeListener( final IMEMonitorHandlerReceiver<IAEItemStack> l )
	{
		this.listeners.remove( l );
	}

	private void postDifference( Iterable<IAEItemStack> a )
	{
		final Iterator<Map.Entry<IMEMonitorHandlerReceiver<IAEItemStack>, Object>> i = this.listeners.entrySet().iterator();
		while ( i.hasNext() )
		{
			final Map.Entry<IMEMonitorHandlerReceiver<IAEItemStack>, Object> l = i.next();
			final IMEMonitorHandlerReceiver<IAEItemStack> key = l.getKey();
			if( key.isValid( l.getValue() ) )
			{
				key.postChange( this, a, this.mySource );
			}
			else
			{
				i.remove();
			}
		}
	}

	private static class InventoryCache implements Iterable<ItemSlot>
	{
		private final IItemHandler itemHandler;
		private final StorageFilter mode;
		IItemList<IAEItemStack> currentlyCached = AEApi.instance().storage().getStorageChannel( IItemStorageChannel.class ).createList();


		public InventoryCache( IItemHandler itemHandler, StorageFilter mode )
		{
			this.mode = mode;
			this.itemHandler = itemHandler;
		}

		public IItemList<IAEItemStack> getAvailableItems( IItemList<IAEItemStack> out )
		{
			currentlyCached.iterator().forEachRemaining( out::add );
			return out;
		}

		private StorageFilter getMode()
		{
			return this.mode;
		}

		public List<IAEItemStack> update()
		{
			final List<IAEItemStack> changes = new ArrayList<>();

			IItemList<IAEItemStack> currentlyOnStorage = AEApi.instance().storage().getStorageChannel( IItemStorageChannel.class ).createList();

			for ( final ItemSlot is : this )
			{
				final ItemStack newIS = !is.isExtractable() && this.getMode() == StorageFilter.EXTRACTABLE_ONLY ? ItemStack.EMPTY : is.getItemStack();
				if( !newIS.isEmpty() )
				{
					currentlyOnStorage.add( is.getAEItemStack() );
				}
			}

			for ( final IAEItemStack is : currentlyCached )
			{
				is.setStackSize( -is.getStackSize() );
			}

			for ( final IAEItemStack is : currentlyOnStorage )
			{
				currentlyCached.add( is );
			}

			for ( final IAEItemStack is : currentlyCached )
			{
				if( is.getStackSize() != 0 )
				{
					changes.add( is );
				}
			}

			currentlyCached = currentlyOnStorage;

			return changes;
		}

		@Override
		public Iterator<ItemSlot> iterator()
		{
			return new ItemHandlerIterator( this.itemHandler );
		}

	}
}
