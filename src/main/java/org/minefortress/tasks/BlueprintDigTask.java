package org.minefortress.tasks;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.minefortress.selections.ServerSelectionType;

import java.util.UUID;

public class BlueprintDigTask extends SimpleSelectionTask {
    public BlueprintDigTask(UUID id, BlockPos startingBlock, BlockPos endingBlock) {
        super(id, TaskType.REMOVE, startingBlock, endingBlock, null, ServerSelectionType.SQUARES);
    }

    @Override
    protected void sendFinishTaskNotificationToPlayer(ServerPlayerEntity randomPlayer) {}
}
