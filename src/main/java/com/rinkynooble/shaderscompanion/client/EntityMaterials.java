package com.rinkynooble.shaderscompanion.client;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Material classes for entities. Oculus draws each entity with the id the shader pack's entity.properties lists for its
 * type ({@code entityId}); entity types with a class get {@code 16384 | class} here, as items do ({@link ItemMaterials}).
 * Sources, in order: the jar's entity table (minecarts), the user's rules ({@link MaterialRules}), then inference the
 * first time an entity of a type is drawn (an entity type doesn't name its class before that): vehicles (an entity class
 * or superclass named ...Vehicle..., Superb Warfare's) take the paint class. Armour, held items and dropped items carry
 * their item's class already ({@code currentRenderedItemId}).
 */
public final class EntityMaterials {
    private static final String TABLE = "/assets/shaderscompanion/materials/entities.json";
    private static final int PAINT = Materials.classNumber("paint");

    /** Classes by entity id. Main thread: built with the block merge, grown as entities are first drawn. */
    private static final Object2IntOpenHashMap<NamespacedId> classes = new Object2IntOpenHashMap<>();
    /** Entity types whose class is settled (from a table or rule, or inferred from a drawn entity). */
    private static final Set<EntityType<?>> settled = Collections.newSetFromMap(new IdentityHashMap<>());
    private static boolean active;
    private static int fromTables;
    private static int inferred;
    private static int overrides;
    static String status = "";

    private EntityMaterials() {
    }

    /** The block merge didn't run for this pipeline: entities get no classes. */
    static void clear() {
        active = false;
        classes.clear();
        settled.clear();
        status = "";
    }

    /** Oculus is setting a pipeline's entity id map: returns it with the entity classes in when the merge ran. */
    public static Object2IntFunction<NamespacedId> wrap(Object2IntFunction<NamespacedId> packIds) {
        if (!active || packIds == null) {
            return packIds;
        }
        // The live map: entity types inferred as they are first drawn reach the pipeline's map too
        return Materials.withClasses(packIds, classes);
    }

    /** The tables' and the user's classes (called from the block merge, before Oculus sets the entity id map). */
    static void build(List<String> problems, List<MaterialRules.Rule> user) {
        Reference2IntOpenHashMap<EntityType<?>> fixed = new Reference2IntOpenHashMap<>();
        MaterialRules.apply(MaterialRules.resource(TABLE, MaterialRules.Kind.ENTITY, "the entity table", problems), MaterialRules.Kind.ENTITY,
                BuiltInRegistries.ENTITY_TYPE, fixed, true, problems);
        overrides = MaterialRules.apply(user, MaterialRules.Kind.ENTITY, BuiltInRegistries.ENTITY_TYPE, fixed, false, problems);
        classes.clear();
        settled.clear();
        classes.defaultReturnValue(0);
        fromTables = 0;
        inferred = 0;
        for (Reference2IntMap.Entry<EntityType<?>> entry : fixed.reference2IntEntrySet()) {
            settled.add(entry.getKey());
            if (entry.getIntValue() > 0) {
                classes.put(id(entry.getKey()), entry.getIntValue());
                fromTables++;
            }
        }
        active = true;
        status = entityStatus();
    }

    /** An entity about to be drawn (main thread): the first of its type settles the type's class. */
    public static void drawing(Entity entity) {
        if (!active) {
            return;
        }
        EntityType<?> type = entity.getType();
        if (!settled.add(type)) {
            return;
        }
        int material = infer(entity);
        if (material > 0) {
            classes.put(id(type), material);
            inferred++;
            status = entityStatus();
            ShadersCompanion.LOGGER.info("Shaders Companion materials: inferred entity {} as {} ({})", BuiltInRegistries.ENTITY_TYPE.getKey(type),
                    Materials.className(material), entity.getClass().getName());
        }
    }

    /** An entity's class from its own class: vehicles are painted. */
    static int infer(Entity entity) {
        for (Class<?> c = entity.getClass(); c != null && c != Entity.class; c = c.getSuperclass()) {
            if (c.getSimpleName().contains("Vehicle")) {
                return PAINT;
            }
        }
        return 0;
    }

    private static NamespacedId id(EntityType<?> type) {
        return Materials.namespacedId(BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }

    private static String entityStatus() {
        return String.format("%d entity types given a class: %d from the entity table and rules%s, %d inferred as they were drawn",
                fromTables + inferred, fromTables, overrides > 0 ? " (" + overrides + " overrides)" : "", inferred);
    }
}
