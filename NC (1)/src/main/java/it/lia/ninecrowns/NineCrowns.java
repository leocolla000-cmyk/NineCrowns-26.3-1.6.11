package it.lia.ninecrowns;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.resources.Identifier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.effect.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import net.minecraft.commands.Commands;

public final class NineCrowns implements ModInitializer {
    public static Config config;
    public static WorldData data;

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("ninecrowns", path);
    }

    @Override
    public void onInitialize() {
        config = Config.load(FabricLoader.getInstance().getConfigDir().resolve("ninecrowns.json"));
        ModItems.init();

        ServerLifecycleEvents.SERVER_STARTING.register(server ->
            data = new WorldData(server.getWorldPath(LevelResource.ROOT).resolve("data/ninecrowns.json"))
        );

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            server.getPlayerList().getPlayers().forEach(CrownEffects::clear);
            if (data != null) data.save();
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> data = null);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var player = handler.player;
            if (data == null) return;
            boolean member = data.roster.register(player.getUUID(), player.getGameProfile().name());
            data.save();
            enforceNoEnderChest(player);
            player.sendSystemMessage(Component.literal(
                member
                    ? "Nine Crowns: partecipanti registrati " + data.roster.players().size() + "/9."
                    : "Nine Crowns: elenco completo; questo account non e un partecipante."
            ));
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> CrownEffects.clear(handler.player));

        ServerTickEvents.END_SERVER_TICK.register(server ->
            server.getPlayerList().getPlayers().forEach(player -> {
                enforceNoEnderChest(player);
                CrownEffects.update(player);

                // Keeps special items in the required state, including Creative or /give copies.
                for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                    ModItems.applyRequiredProperties(player.getInventory().getItem(slot), player.registryAccess());
                }
                ModItems.applyRequiredProperties(player.getMainHandItem(), player.registryAccess());
                ModItems.applyRequiredProperties(player.getOffhandItem(), player.registryAccess());
                ModItems.applyRequiredProperties(player.getItemBySlot(EquipmentSlot.HEAD), player.registryAccess());
            })
        );

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player && data != null && data.roster.contains(player.getUUID())) {
                var head = new ItemStack(Items.PLAYER_HEAD);
                head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(player.getGameProfile()));
                head.set(DataComponents.CUSTOM_NAME, Component.literal("Testa di " + player.getGameProfile().name()));
                player.spawnAtLocation(player.level(), head);
            }
        });

        ServerLivingEntityEvents.AFTER_DAMAGE.register((victim, source, baseDamage, damageTaken, blocked) -> {
            if (blocked || damageTaken <= 0) return;
            if (source.getDirectEntity() instanceof ServerPlayer attacker && attacker.getMainHandItem().is(ModItems.SWORD)) {
                var random = attacker.getRandom();
                if (random.nextFloat() < config.poisonChance) {
                    victim.addEffect(new MobEffectInstance(MobEffects.POISON, config.effectTicks, 0), attacker);
                }
                if (random.nextFloat() < config.slownessChance) {
                    victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, config.effectTicks, 0), attacker);
                }
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var root = Commands.literal("ninecrowns")
                .executes(context -> {
                    if (data == null) {
                        context.getSource().sendFailure(Component.literal("Nine Crowns: dati non ancora caricati."));
                        return 0;
                    }
                    String names = data.roster.players().isEmpty()
                        ? "nessuno"
                        : String.join(", ", data.roster.players().values());
                    context.getSource().sendSuccess(
                        () -> Component.literal(
                            "Nine Crowns (" + data.roster.players().size() + "/9): " + names
                            + " | " + data.craftedStatus()
                        ),
                        false
                    );
                    return data.roster.players().size();
                })
                .then(Commands.literal("recipes").executes(context -> {
                    context.getSource().sendSuccess(
                        () -> Component.literal("Ricette leggendarie: 8 teste degli ALTRI giocatori attorno all'oggetto centrale."),
                        false
                    );
                    context.getSource().sendSuccess(
                        () -> Component.literal("Spada OP: 8 teste + Netherite Sword al centro."),
                        false
                    );
                    context.getSource().sendSuccess(
                        () -> Component.literal("Spear OP: 8 teste + Netherite Spear al centro."),
                        false
                    );
                    context.getSource().sendSuccess(
                        () -> Component.literal("Emperor Crown: 8 teste + Empty Crown al centro."),
                        false
                    );
                    context.getSource().sendSuccess(
                        () -> Component.literal("Puo esistere UNA SOLA copia per tipo. Se viene distrutta, la ricetta si riapre."),
                        false
                    );
                    return 1;
                }))
                .then(Commands.literal("status").executes(context -> {
                    if (data == null) {
                        context.getSource().sendFailure(Component.literal("Nine Crowns: dati non ancora caricati."));
                        return 0;
                    }
                    context.getSource().sendSuccess(
                        () -> Component.literal(data.craftedStatus()),
                        false
                    );
                    return 1;
                }))
                .then(Commands.literal("testkit")
                    .requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
                    .then(Commands.literal("sword").executes(context -> giveTestKit(context.getSource().getPlayerOrException(), "sword")))
                    .then(Commands.literal("spear").executes(context -> giveTestKit(context.getSource().getPlayerOrException(), "spear")))
                    .then(Commands.literal("crown").executes(context -> giveTestKit(context.getSource().getPlayerOrException(), "crown")))
                )
                .then(Commands.literal("testdestroyheld")
                    .requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
                    .executes(context -> destroyHeldForTest(context.getSource().getPlayerOrException()))
                );

            dispatcher.register(root);
        });
    }

    private static void enforceNoEnderChest(ServerPlayer player) {
        var ender = player.getEnderChestInventory();
        boolean movedAny = false;

        for (int i = 0; i < ender.getContainerSize(); i++) {
            ItemStack stack = ender.getItem(i);
            if (!ModItems.isLegendary(stack)) continue;

            ItemStack moved = stack.copy();
            ender.setItem(i, ItemStack.EMPTY);

            if (!player.getInventory().add(moved)) {
                player.spawnAtLocation(player.level(), moved);
            }
            movedAny = true;
        }

        if (movedAny) {
            ender.setChanged();
            player.sendSystemMessage(Component.literal(
                "Gli oggetti leggendari non possono essere messi nella Ender Chest: sono stati restituiti."
            ));
        }
    }

    private static int giveTestKit(ServerPlayer player, String type) {
        if (data == null) return 0;
        if (data.roster.players().size() != Roster.SIZE || !data.roster.contains(player.getUUID())) {
            player.sendSystemMessage(Component.literal(
                "Test kit non disponibile: devono essere registrati tutti i 9 giocatori e tu devi essere uno di loro."
            ));
            return 0;
        }

        int heads = 0;
        for (var entry : data.roster.players().entrySet()) {
            if (entry.getKey().equals(player.getUUID())) continue;

            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            GameProfile profile = new GameProfile(entry.getKey(), entry.getValue());
            head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
            head.set(DataComponents.CUSTOM_NAME, Component.literal("Testa di " + entry.getValue()));
            giveOrDrop(player, head);
            heads++;
        }

        ItemStack center;
        if (type.equals("sword")) center = new ItemStack(Items.NETHERITE_SWORD);
        else if (type.equals("spear")) center = new ItemStack(Items.NETHERITE_SPEAR);
        else center = new ItemStack(ModItems.EMPTY_CROWN);

        giveOrDrop(player, center);
        player.sendSystemMessage(Component.literal(
            "Test kit " + type + ": ricevute " + heads + " teste corrette + oggetto centrale. Usa un crafting table 3x3."
        ));
        return 1;
    }

    private static int destroyHeldForTest(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (!ModItems.isLegendary(stack)) {
            player.sendSystemMessage(Component.literal("Tieni in mano un oggetto leggendario da distruggere per il test."));
            return 0;
        }

        Item item = stack.getItem();
        stack.setCount(0);
        if (data != null) data.release(item);
        player.sendSystemMessage(Component.literal("Oggetto leggendario distrutto: la sua ricetta e di nuovo disponibile."));
        return 1;
    }

    private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.spawnAtLocation(player.level(), stack);
        }
    }

    public static void lightning(ServerPlayer player) {
        if (data == null || player.isSpectator()) return;

        long now = player.level().getServer().overworld().getGameTime();
        long rechargeTicks = Math.max(1, config.lightningRechargeSeconds) * 20L;
        long remaining = data.lightningRemaining(now);

        if (remaining > 0) {
            player.sendSystemMessage(Component.literal(
                "Fulmini non pronti. Attendi " + ((remaining + 19) / 20) + " s."
            ));
            return;
        }

        HitResult hit = ProjectileUtil.getHitResultOnViewVector(
            player,
            entity -> entity instanceof LivingEntity && entity.isAlive() && !entity.isSpectator(),
            config.lightningRange
        );

        if (!(hit instanceof EntityHitResult entityHit) || !(entityHit.getEntity() instanceof LivingEntity target)) {
            player.sendSystemMessage(Component.literal(
                "Mira a un bersaglio entro " + (int) config.lightningRange + " blocchi."
            ));
            return;
        }

        if (target instanceof ServerPlayer other && !player.canHarmPlayer(other)) return;

        if (!data.consumeLightning(now, rechargeTicks)) return;

        var level = player.level();

        // Several visual bolts, but ONE normal damage event so armor and Protection
        // reduce the total 12 damage normally instead of each tiny bolt separately.
        int boltCount = Math.max(1, config.lightningVisualBolts);
        double radius = 0.65;
        for (int i = 0; i < boltCount; i++) {
            LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
            if (bolt == null) continue;

            double angle = (Math.PI * 2.0 * i) / boltCount;
            double x = target.getX() + Math.cos(angle) * radius;
            double z = target.getZ() + Math.sin(angle) * radius;
            bolt.setPos(x, target.getY(), z);
            bolt.setVisualOnly(true);
            bolt.setCause(player);
            level.addFreshEntity(bolt);
        }

        target.hurtServer(
            level,
            level.damageSources().playerAttack(player),
            config.lightningDamage
        );
    }
}
