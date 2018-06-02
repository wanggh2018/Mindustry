package io.anuke.mindustry.content.blocks;

import io.anuke.mindustry.type.ContentList;
import io.anuke.mindustry.world.Block;
import io.anuke.mindustry.world.blocks.types.storage.CoreBlock;
import io.anuke.mindustry.world.blocks.types.storage.SortedUnloader;
import io.anuke.mindustry.world.blocks.types.storage.Unloader;
import io.anuke.mindustry.world.blocks.types.storage.Vault;

public class StorageBlocks implements ContentList {
    public static Block core, vault, unloader, sortedunloader;

    @Override
    public void load() {
        core = new CoreBlock("core") {{
            health = 800;
        }};

        vault = new Vault("vault") {{
            size = 3;
            health = 600;
        }};

        unloader = new Unloader("unloader") {{
            speed = 5;
        }};

        sortedunloader = new SortedUnloader("sortedunloader") {{
            speed = 5;
        }};
    }
}