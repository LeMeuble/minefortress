package org.minefortress.tasks;

import com.mojang.datafixers.util.Pair;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;
import org.minefortress.entity.ai.PathUtils;
import org.minefortress.network.ClientboundTaskExecutedPacket;
import org.minefortress.network.helpers.FortressChannelNames;
import org.minefortress.network.helpers.FortressServerNetworkHelper;
import org.minefortress.selections.SelectionType;
import org.minefortress.tasks.block.info.BlockInfoUtils;
import org.minefortress.tasks.block.info.BlockStateTaskBlockInfo;
import org.minefortress.tasks.block.info.ItemTaskBlockInfo;
import org.minefortress.tasks.block.info.TaskBlockInfo;
import org.minefortress.tasks.interfaces.Task;

import java.util.*;

public class SimpleSelectionTask implements Task {

    // CAUTION: ID is not actually unique!
    private final UUID id;

    private final TaskType taskType;
    private final BlockPos startingBlock;
    private final BlockPos endingBlock;
    private final HitResult hitResult;
    private final Direction horizontalDirection;
    private final SelectionType selectionType;

    private Item placingItem;

    private static final int PART_SIZE = 3;
    private final Queue<Pair<BlockPos, BlockPos>> parts;

    private int totalParts;
    private int completedParts;

    public SimpleSelectionTask(UUID id, TaskType taskType, BlockPos startingBlock, BlockPos endingBlock, HitResult hitResult, SelectionType selectionType) {
        this.id = id;
        this.selectionType = selectionType;
        this.taskType = taskType;

        boolean removeFromBottomToTop = taskType == TaskType.REMOVE && endingBlock.getY() > startingBlock.getY();
        boolean buildFromTopToBottom = taskType == TaskType.BUILD && endingBlock.getY() < startingBlock.getY();
        boolean isLadder = selectionType == SelectionType.LADDER || selectionType == SelectionType.LADDER_Z_DIRECTION;
        if(!isLadder && (removeFromBottomToTop || buildFromTopToBottom)) {
            this.startingBlock = endingBlock;
            this.endingBlock = startingBlock;
        } else {
            this.startingBlock = startingBlock;
            this.endingBlock = endingBlock;
        }

        this.parts = new ArrayDeque<>();
        if(selectionType == SelectionType.LADDER && hitResult instanceof BlockHitResult) {
            this.horizontalDirection  = startingBlock.getX() > endingBlock.getX() ? Direction.WEST : Direction.EAST;
            this.hitResult = new BlockHitResult(hitResult.getPos(), Direction.UP, ((BlockHitResult) hitResult).getBlockPos(), ((BlockHitResult) hitResult).isInsideBlock());
        } else if (selectionType == SelectionType.LADDER_Z_DIRECTION && hitResult instanceof BlockHitResult) {
            this.horizontalDirection  = startingBlock.getZ() > endingBlock.getZ() ? Direction.NORTH : Direction.SOUTH;
            this.hitResult = new BlockHitResult(hitResult.getPos(), Direction.UP, ((BlockHitResult) hitResult).getBlockPos(), ((BlockHitResult) hitResult).isInsideBlock());
        } else {
            this.hitResult = hitResult;
            this.horizontalDirection = null;
        }
    }

    @Override
    public TaskType getTaskType() {
        return taskType;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public void prepareTask() {
        if(selectionType == SelectionType.WALLS_EVERY_SECOND) {
            parts.add(Pair.of(startingBlock, endingBlock));
        } else {
            BlockPos.Mutable cursor = this.startingBlock.mutableCopy();

            final Vec3i direction = PathUtils.getDirection(startingBlock, endingBlock);

            do {
                BlockPos start = cursor.toImmutable();
                BlockPos end = createPartEnd(start, direction);
                parts.add(Pair.of(start, end));

                if(end.getX() * direction.getX() >= endingBlock.getX() * direction.getX()) {
                    if (end.getZ() * direction.getZ() >= endingBlock.getZ() * direction.getZ()) {
                        break;
                    } else {
                        cursor.setX(startingBlock.getX());
                        cursor.move(0, 0, PART_SIZE * direction.getZ());
                    }
                } else {
                    cursor.move(direction.getX() * PART_SIZE, 0, 0);
                }
            } while (true);
        }

        totalParts = this.parts.size();
    }

    private BlockPos createPartEnd(BlockPos start, Vec3i direction) {
        BlockPos.Mutable cursor = start.mutableCopy();
        cursor.setY(endingBlock.getY());
        cursor.move((PART_SIZE-1) * direction.getX(), 0, (PART_SIZE-1)*direction.getZ());
        if(cursor.getX() * direction.getX() > endingBlock.getX() * direction.getX()) {
            cursor.setX(endingBlock.getX());
        }
        if(cursor.getZ() * direction.getZ() > endingBlock.getZ() * direction.getZ()) {
            cursor.setZ(endingBlock.getZ());
        }
        return cursor.toImmutable();
    }

    public void setPlacingItem(Item placingItem) {
        this.placingItem = placingItem;
    }

    @Override
    public boolean hasAvailableParts() {
        return !this.parts.isEmpty();
    }

    @Override
    public TaskPart getNextPart(ServerWorld world) {
        Pair<BlockPos, BlockPos> startAndEnd = parts.poll();
        if(startAndEnd == null) throw new IllegalStateException("Null part for task!");
        final List<TaskBlockInfo> blocks = getPartBlocksInfo(startAndEnd, world);
        return new TaskPart(startAndEnd, blocks, this);
    }

    @Override
    public void returnPart(Pair<BlockPos, BlockPos> part) {
        parts.add(part);
    }

    @Override
    public void finishPart(ServerWorld level) {
        completedParts++;
        if(parts.isEmpty() && totalParts == completedParts) {
            ServerPlayerEntity randomPlayer = level.getRandomAlivePlayer();
            if(randomPlayer != null) {
                FortressServerNetworkHelper.send(randomPlayer, FortressChannelNames.FINISH_TASK, new ClientboundTaskExecutedPacket(this.getId()));
            }
        }
    }

    private List<TaskBlockInfo> getPartBlocksInfo(Pair<BlockPos, BlockPos> startAndEnd, ServerWorld world) {
        final List<TaskBlockInfo> blocksInfo = new ArrayList<>();
        getBlocksForPart(startAndEnd).spliterator().forEachRemaining(block -> {
            if(BlockInfoUtils.shouldBePlacedAsItem(placingItem)) {
                final ItemUsageContext useOnContext = BlockInfoUtils.getUseOnContext(this.hitResult, this.placingItem, block, world);
                final ItemTaskBlockInfo itemTaskBlockInfo = new ItemTaskBlockInfo(placingItem, block, useOnContext);
                blocksInfo.add(itemTaskBlockInfo);
            } else {
                final BlockState blockStateForPlacement = BlockInfoUtils.getBlockStateForPlacement(placingItem, hitResult, horizontalDirection, world, block);
                final BlockStateTaskBlockInfo blockStateTaskBlockInfo = new BlockStateTaskBlockInfo(placingItem, block, blockStateForPlacement);
                blocksInfo.add(blockStateTaskBlockInfo);
            }
        });
        return blocksInfo;
    }

    private Iterable<BlockPos> getBlocksForPart(Pair<BlockPos, BlockPos> part) {
        if(selectionType == SelectionType.LADDER) {
            return PathUtils.getLadderSelection(this.startingBlock, part.getFirst(), part.getSecond(), Direction.Axis.X);
        } else if(selectionType == SelectionType.LADDER_Z_DIRECTION) {
            return PathUtils.getLadderSelection(this.startingBlock, part.getFirst(), part.getSecond(), Direction.Axis.Z);
        } else {
            return PathUtils.fromStartToEnd(part.getFirst(), part.getSecond(), selectionType);
        }
    }



}