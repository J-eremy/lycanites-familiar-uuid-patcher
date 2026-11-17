import com.lycanitesmobs.core.pets.PlayerFamiliars;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Embeddable smoke test for the patched PlayerFamiliars class.
 *
 * Checks that:
 *  - the class initialises outside Minecraft (static initialiser + SSLContext),
 *  - REPORTED_UUID resolved to the expected value (printed, compared by run.sh),
 *  - the public surface the rest of the mod links against is unchanged.
 *
 * It does NOT exercise the HTTP fetch or JSON parsing: those need a live
 * CreatureManager and a game directory.
 */
public class Test {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("RESULT " + PlayerFamiliars.REPORTED_UUID);

        Field instance = field("INSTANCE");
        check(instance != null, "INSTANCE field exists");
        check(instance != null && instance.getType() == PlayerFamiliars.class, "INSTANCE field type");
        if (instance != null) {
            check(instance.get(null) != null, "INSTANCE is non-null");
        }

        Field familiars = field("playerFamiliars");
        check(familiars != null && Map.class.isAssignableFrom(familiars.getType()),
                "playerFamiliars is a Map");

        check(field("playerFamiliarLoadedTimes") != null, "playerFamiliarLoadedTimes field");
        check(field("familiarBlacklist") != null, "familiarBlacklist field");

        Field ssl = field("sslContext");
        check(ssl != null && ssl.getType().getName().equals("javax.net.ssl.SSLContext"),
                "sslContext field");

        check(method("parseFamiliarJSON", String.class) != null, "parseFamiliarJSON(String)");
        check(method("getFamiliarsForPlayer", EntityPlayer.class) != null,
                "getFamiliarsForPlayer(EntityPlayer)");
        check(method("getPlayerFamiliarLoadedTime", EntityPlayer.class) != null,
                "getPlayerFamiliarLoadedTime(EntityPlayer)");
        check(method("updatePlayerFamiliarLoadedTime", EntityPlayer.class) != null,
                "updatePlayerFamiliarLoadedTime(EntityPlayer)");

        Method legacy = method("handleLegacySpecies", String.class);
        check(legacy != null, "handleLegacySpecies(String)");
        if (legacy != null) {
            check("archvile".equals(legacy.invoke(PlayerFamiliars.INSTANCE, "apollyon")),
                    "handleLegacySpecies maps apollyon -> archvile");
        }

        Class<?> loader = null;
        try {
            loader = Class.forName("com.lycanitesmobs.core.pets.PlayerFamiliars$FamiliarLoader");
        }
        catch (ClassNotFoundException e) {
            check(false, "FamiliarLoader class present");
        }
        if (loader != null) {
            check(Runnable.class.isAssignableFrom(loader), "FamiliarLoader implements Runnable");
            boolean ctor = false;
            for (Constructor<?> candidate : loader.getDeclaredConstructors()) {
                if (candidate.getParameterTypes().length == 1
                        && candidate.getParameterTypes()[0] == EntityPlayer.class) {
                    ctor = true;
                }
            }
            check(ctor, "FamiliarLoader(EntityPlayer)");
        }

        if (failures > 0) {
            System.out.println("API FAIL " + failures);
            System.exit(1);
        }
        System.out.println("API OK");
    }

    private static Field field(String name) {
        try {
            return PlayerFamiliars.class.getField(name);
        }
        catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Method method(String name, Class<?>... params) {
        try {
            return PlayerFamiliars.class.getMethod(name, params);
        }
        catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            failures++;
            System.out.println("API FAIL: " + what);
        }
    }
}
