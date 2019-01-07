package gregtech.api.metatileentity;

import com.google.common.base.Preconditions;
import gregtech.api.GTValues;
import gregtech.api.GregTechAPI;
import gregtech.api.block.machines.BlockMachine;
import gregtech.api.gui.IUIHolder;
import gregtech.api.util.GTControlledRegistry;
import gregtech.api.util.GTLog;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.Constants.NBT;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class MetaTileEntityHolder extends TickableTileEntityBase implements IUIHolder {

    private MetaTileEntity metaTileEntity;
    private boolean needToUpdateLightning = false;

    public MetaTileEntity getMetaTileEntity() {
        return metaTileEntity;
    }

    /**
     * Sets this holder's current meta tile entity to copy of given one
     * Note that this method copies given meta tile entity and returns actual instance
     * so it is safe to call it on sample meta tile entities
     */
    public MetaTileEntity setMetaTileEntity(MetaTileEntity sampleMetaTileEntity) {
        Preconditions.checkNotNull(sampleMetaTileEntity, "metaTileEntity");
        this.metaTileEntity = sampleMetaTileEntity.createMetaTileEntity(this);
        this.metaTileEntity.holder = this;
        if(hasWorld() && !getWorld().isRemote) {
            updateBlockOpacity();
            writeCustomData(-1, buffer -> {
                buffer.writeString(metaTileEntity.metaTileEntityId.toString());
                metaTileEntity.writeInitialSyncData(buffer);
            });
            //just to update neighbours so cables and other things will work properly
            this.needToUpdateLightning = true;
            world.neighborChanged(getPos(), getBlockType(), getPos());
            markDirty();
        }
        return metaTileEntity;
    }

    private void updateBlockOpacity() {
        IBlockState currentState = world.getBlockState(getPos());
        boolean isMetaTileEntityOpaque = metaTileEntity.isOpaqueCube();
        if(currentState.getValue(BlockMachine.OPAQUE) != isMetaTileEntityOpaque) {
            world.setBlockState(getPos(), currentState.withProperty(BlockMachine.OPAQUE, isMetaTileEntityOpaque));
        }
    }

    public void scheduleChunkForRenderUpdate() {
        BlockPos pos = getPos();
        getWorld().markBlockRangeForRenderUpdate(
            pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
            pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    public void notifyBlockUpdate() {
        getWorld().notifyNeighborsOfStateChange(pos, getBlockType(), false);
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        if(compound.hasKey("MetaId", NBT.TAG_STRING)) {
            String metaTileEntityIdRaw = compound.getString("MetaId");
            ResourceLocation metaTileEntityId;
            if(metaTileEntityIdRaw.indexOf(':') == -1) {
                metaTileEntityId = convertMetaTileEntityId(metaTileEntityIdRaw);
            } else {
                metaTileEntityId = new ResourceLocation(metaTileEntityIdRaw);
            }
            MetaTileEntity sampleMetaTileEntity = metaTileEntityId == null ? null : GregTechAPI.META_TILE_ENTITY_REGISTRY.getObject(metaTileEntityId);
            NBTTagCompound metaTileEntityData = compound.getCompoundTag("MetaTileEntity");
            if (sampleMetaTileEntity != null) {
                this.metaTileEntity = sampleMetaTileEntity.createMetaTileEntity(this);
                this.metaTileEntity.holder = this;
                this.metaTileEntity.readFromNBT(metaTileEntityData);
                this.metaTileEntity.onPostNBTLoad();
            } else {
                GTLog.logger.error("Failed to load MetaTileEntity with invalid ID " + metaTileEntityIdRaw);
            }
        }
    }

    private static List<String> registeredModIDs = null;

    private static ResourceLocation convertMetaTileEntityId(String metaTileEntityIdOld) {
        ResourceLocation gregtechId = new ResourceLocation(GTValues.MODID, metaTileEntityIdOld);
        GTControlledRegistry<ResourceLocation, MetaTileEntity> registry = GregTechAPI.META_TILE_ENTITY_REGISTRY;
        if(registry.containsKey(gregtechId)) {
            return gregtechId; //remap to gregtech meta tile entities first
        }
        //try to lookup by different registry IDs
        if(registeredModIDs == null) {
            registeredModIDs = registry.getKeys().stream()
                .map(ResourceLocation::getResourceDomain)
                .distinct().collect(Collectors.toList());
            registeredModIDs.remove(GTValues.MODID);
        }
        for(String registryModId : registeredModIDs) {
            ResourceLocation probableId = new ResourceLocation(registryModId, metaTileEntityIdOld);
            if(registry.containsKey(probableId)) {
                return probableId;
            }
        }
        GTLog.logger.error("Failed to convert old MetaTileEntity string ID " + metaTileEntityIdOld);
        return null;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        if(metaTileEntity != null) {
            compound.setString("MetaId", metaTileEntity.metaTileEntityId.toString());
            NBTTagCompound metaTileEntityData = new NBTTagCompound();
            metaTileEntity.writeToNBT(metaTileEntityData);
            compound.setTag("MetaTileEntity", metaTileEntityData);
        }
        return compound;
    }

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
        Object metaTileEntityValue = metaTileEntity == null ? null : metaTileEntity.getCoverCapability(capability, facing);
        return metaTileEntityValue != null || super.hasCapability(capability, facing);
    }

    @Nullable
    @Override
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
        T metaTileEntityValue = metaTileEntity == null ? null : metaTileEntity.getCoverCapability(capability, facing);
        return metaTileEntityValue != null ? metaTileEntityValue : super.getCapability(capability, facing);
    }

    @Override
    public void update() {
        if(metaTileEntity != null) {
            metaTileEntity.update();
        }
        if(this.needToUpdateLightning) {
            getWorld().checkLight(getPos());
            this.needToUpdateLightning = false;
        }
        //increment only after current tick, so meta tile entities will get first tick as timer == 0
        //and update their settings which depend on getTimer() % N properly
        super.update();
    }

    public void writeInitialSyncData(PacketBuffer buf) {
        if(metaTileEntity != null) {
            buf.writeBoolean(true);
            buf.writeString(metaTileEntity.metaTileEntityId.toString());
            metaTileEntity.writeInitialSyncData(buf);
        } else buf.writeBoolean(false);
    }

    public void receiveInitialSyncData(PacketBuffer buf) {
        if(buf.readBoolean()) {
            ResourceLocation metaTileEntityName = new ResourceLocation(buf.readString(Short.MAX_VALUE));
            setMetaTileEntity(GregTechAPI.META_TILE_ENTITY_REGISTRY.getObject(metaTileEntityName));
            this.metaTileEntity.receiveInitialSyncData(buf);
            scheduleChunkForRenderUpdate();
            this.needToUpdateLightning = true;
        }
    }

    public void receiveCustomData(int discriminator, PacketBuffer buffer) {
        if(discriminator == -1) {
            ResourceLocation metaTileEntityName = new ResourceLocation(buffer.readString(Short.MAX_VALUE));
            setMetaTileEntity(GregTechAPI.META_TILE_ENTITY_REGISTRY.getObject(metaTileEntityName));
            this.metaTileEntity.receiveInitialSyncData(buffer);
            scheduleChunkForRenderUpdate();
            this.needToUpdateLightning = true;
        } else if(metaTileEntity != null) {
            metaTileEntity.receiveCustomData(discriminator, buffer);
        }
    }

    @Override
    public boolean isValid() {
        return !super.isInvalid() && metaTileEntity != null;
    }

    @Override
    public boolean isRemote() {
        return getWorld().isRemote;
    }

    @Override
    public void markAsDirty() {
        markDirty();
    }

    private static class UpdateEntry {
        private final int discriminator;
        private final byte[] updateData;

        public UpdateEntry(int discriminator, byte[] updateData) {
            this.discriminator = discriminator;
            this.updateData = updateData;
        }
    }

    private final List<UpdateEntry> updateEntries = new ArrayList<>();

    public void writeCustomData(int discriminator, Consumer<PacketBuffer> dataWriter) {
        ByteBuf backedBuffer = Unpooled.buffer();
        dataWriter.accept(new PacketBuffer(backedBuffer));
        byte[] updateData = Arrays.copyOfRange(backedBuffer.array(), 0, backedBuffer.writerIndex());
        updateEntries.add(new UpdateEntry(discriminator, updateData));
        @SuppressWarnings("deprecation")
        IBlockState blockState = getBlockType().getStateFromMeta(getBlockMetadata());
        world.notifyBlockUpdate(getPos(), blockState, blockState, 0);
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        NBTTagCompound updateTag = new NBTTagCompound();
        NBTTagList tagList = new NBTTagList();
        for(UpdateEntry updateEntry : updateEntries) {
            NBTTagCompound entryTag = new NBTTagCompound();
            entryTag.setInteger("id", updateEntry.discriminator);
            entryTag.setByteArray("data", updateEntry.updateData);
            tagList.appendTag(entryTag);
        }
        updateEntries.clear();
        updateTag.setTag("data", tagList);
        return new SPacketUpdateTileEntity(getPos(), 0, updateTag);
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        NBTTagCompound updateTag = pkt.getNbtCompound();
        NBTTagList tagList = updateTag.getTagList("data", NBT.TAG_COMPOUND);
        for(int i = 0; i < tagList.tagCount(); i++) {
            NBTTagCompound entryTag = tagList.getCompoundTagAt(i);
            int discriminator = entryTag.getInteger("id");
            byte[] updateData = entryTag.getByteArray("data");
            ByteBuf backedBuffer = Unpooled.copiedBuffer(updateData);
            receiveCustomData(discriminator, new PacketBuffer(backedBuffer));
        }
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound updateTag = super.getUpdateTag();
        updateTag.setInteger("x", getPos().getX());
        updateTag.setInteger("y", getPos().getY());
        updateTag.setInteger("z", getPos().getZ());
        ByteBuf backedBuffer = Unpooled.buffer();
        writeInitialSyncData(new PacketBuffer(backedBuffer));
        byte[] updateData = Arrays.copyOfRange(backedBuffer.array(), 0, backedBuffer.writerIndex());
        updateTag.setByteArray("data", updateData);
        return updateTag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        byte[] updateData = tag.getByteArray("data");
        ByteBuf backedBuffer = Unpooled.copiedBuffer(updateData);
        receiveInitialSyncData(new PacketBuffer(backedBuffer));
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newSate) {
        return oldState.getBlock() != newSate.getBlock(); //MetaTileEntityHolder should never refresh (until block changes)
    }
}
