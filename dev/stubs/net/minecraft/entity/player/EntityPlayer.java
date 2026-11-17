package net.minecraft.entity.player;

import java.util.UUID;
import net.minecraft.entity.EntityLivingBase;

/**
 * Compile-time stub only. Never packaged into the patched jar.
 *
 * The patched class must emit a call to the exact SRG method name used at runtime
 * by Minecraft 1.12.2 (EntityPlayer#getUniqueID -> func_110124_au). javac only
 * needs the signature, so declaring it here is enough.
 */
public class EntityPlayer extends EntityLivingBase {
    public UUID func_110124_au() {
        throw new UnsupportedOperationException("stub");
    }
}
