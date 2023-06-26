package org.minefortress.fortress.automation;

import net.minecraft.world.World;

import java.time.LocalDateTime;
import java.util.Iterator;

public interface IAutomationArea {

    Iterator<AutomationBlockInfo> iterator(World world);
    void update();
    LocalDateTime getUpdated();

}
