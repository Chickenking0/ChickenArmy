package com.chickenking.army;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCDeathEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPC.Metadata;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

public class ChickenArmy extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
   private static final int MAX_ARMY_SIZE = 50;
   private static final double TARGET_RANGE = (double)24.0F;
   private final Set<Integer> armyNpcIds = new HashSet();
   private final Map<Integer, UUID> currentTargets = new HashMap();
   private final Map<Integer, Long> lastAttackAt = new HashMap();
   private BukkitTask targetTask;
   private UUID commandedTarget;

   public void onEnable() {
      Plugin citizens = this.getServer().getPluginManager().getPlugin("Citizens");
      if (citizens != null && citizens.isEnabled() && CitizensAPI.getNPCRegistry() != null) {
         this.armyNpcIds.addAll(this.getConfig().getIntegerList("army-npc-ids"));
         this.getCommand("chickenarmy").setExecutor(this);
         this.getCommand("chickenarmy").setTabCompleter(this);
         this.getServer().getPluginManager().registerEvents(this, this);
         this.targetTask = (new BukkitRunnable() {
            public void run() {
               ChickenArmy.this.updateTargets();
            }
         }).runTaskTimer(this, 20L, 10L);
         Bukkit.getScheduler().runTask(this, () -> {
            for(int npcId : new HashSet(this.armyNpcIds)) {
               NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
               if (npc != null) {
                  WeaponType weapon = this.weaponTypeOf(npc, npcId);
                  this.equipFighter(npc, weapon);
                  this.setTablistVisible(npc);
               }
            }

         });
         this.getLogger().info("ChickenArmy 2.0 aktiviert - Citizens-Spieler-NPCs bereit.");
      } else {
         this.getLogger().severe("Citizens ist nicht geladen. ChickenArmy wird deaktiviert.");
         this.getServer().getPluginManager().disablePlugin(this);
      }
   }

   public void onDisable() {
      if (this.targetTask != null) {
         this.targetTask.cancel();
      }

      this.saveNpcIds();
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player player) {
         if (args.length == 0) {
            this.sendUsage(player);
            return true;
         } else {
            switch (args[0].toLowerCase(Locale.ROOT)) {
               case "spawn":
                  if (args.length < 2) {
                     this.sendUsage(player);
                     return true;
                  }

                  this.spawnArmy(player, args[1]);
                  break;
               case "attack":
                  if (args.length < 2) {
                     this.sendUsage(player);
                     return true;
                  }

                  this.commandArmyAttack(player, args[1]);
                  break;
               case "tp":
                  this.teleportToArmyNpc(player, args.length > 1 ? args[1] : null);
                  break;
               default:
                  this.sendUsage(player);
            }

            return true;
         }
      } else {
         sender.sendMessage(String.valueOf(ChatColor.RED) + "Dieser Befehl kann nur von einem Spieler ausgeführt werden!");
         return true;
      }
   }

   private void sendUsage(Player player) {
      player.sendMessage(String.valueOf(ChatColor.YELLOW) + "ChickenArmy Befehle:");
      player.sendMessage(String.valueOf(ChatColor.GRAY) + "/Chickenarmy spawn <Anzahl>");
      player.sendMessage(String.valueOf(ChatColor.GRAY) + "/Chickenarmy attack <Spieler>");
      player.sendMessage(String.valueOf(ChatColor.GRAY) + "/Chickenarmy tp [NPC-Name]");
   }

   private void spawnArmy(Player player, String amountText) {
      int count;
      try {
         count = Integer.parseInt(amountText);
         if (count <= 0) {
            throw new NumberFormatException();
         }
      } catch (NumberFormatException var9) {
         player.sendMessage(String.valueOf(ChatColor.RED) + "Bitte gib eine gültige Anzahl ein!");
         return;
      }

      if (count > 50) {
         count = 50;
         player.sendMessage(String.valueOf(ChatColor.YELLOW) + "Das Maximum wurde auf 50 NPCs begrenzt, um Lags zu vermeiden.");
      }

      Location spawnLocation = player.getLocation();
      int spawned = 0;

      for(int i = 0; i < count; ++i) {
         WeaponType weapon = ChickenArmy.WeaponType.values()[ThreadLocalRandom.current().nextInt(ChickenArmy.WeaponType.values().length)];
         NPC npc = this.spawnFighter(spawnLocation, i + 1, weapon);
         if (npc != null) {
            ++spawned;
         }
      }

      this.saveNpcIds();
      String var10001 = String.valueOf(ChatColor.GREEN);
      player.sendMessage(var10001 + "Es wurden " + spawned + " zufällig benannte Soldaten mit Netherite-Rüstung, Schild und zufälliger Waffe gespawnt.");
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      List<String> choices = new ArrayList();
      if (args.length == 1) {
         choices.addAll(List.of("spawn", "attack", "tp"));
      } else if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
         choices.addAll(List.of("1", "5", "10", "20", "50"));
      } else if (args.length == 2 && args[0].equalsIgnoreCase("attack")) {
         for(Player online : Bukkit.getOnlinePlayers()) {
            choices.add(online.getName());
         }
      } else if (args.length == 2 && args[0].equalsIgnoreCase("tp")) {
         for(int npcId : this.armyNpcIds) {
            NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
            if (npc != null && npc.isSpawned()) {
               choices.add(npc.getName());
            }
         }
      }

      String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
      return choices.stream().filter((choice) -> choice.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
   }

   private void teleportToArmyNpc(Player player, String npcName) {
      NPC selected = null;
      double closestDistance = Double.MAX_VALUE;

      for(int npcId : this.armyNpcIds) {
         NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
         if (npc != null && npc.isSpawned()) {
            Entity var10 = npc.getEntity();
            if (var10 instanceof LivingEntity) {
               LivingEntity entity = (LivingEntity)var10;
               if (entity.getWorld().equals(player.getWorld())) {
                  if (npcName != null) {
                     if (npc.getName().equalsIgnoreCase(npcName)) {
                        selected = npc;
                        break;
                     }
                  } else {
                     double distance = entity.getLocation().distanceSquared(player.getLocation());
                     if (distance < closestDistance) {
                        selected = npc;
                        closestDistance = distance;
                     }
                  }
               }
            }
         }
      }

      if (selected != null) {
         Entity destination = selected.getEntity();
         if (destination instanceof LivingEntity) {
            LivingEntity entity = (LivingEntity)destination;
            Location destination = entity.getLocation().clone().add((double)1.5F, (double)0.0F, (double)0.0F);
            destination.setYaw(player.getLocation().getYaw());
            destination.setPitch(player.getLocation().getPitch());
            player.teleport(destination);
            String var16 = String.valueOf(ChatColor.GREEN);
            player.sendMessage(var16 + "Teleportiert zu " + selected.getName() + ".");
            return;
         }
      }

      String var10001 = String.valueOf(ChatColor.RED);
      player.sendMessage(var10001 + (npcName == null ? "Es steht kein Soldat in deiner Welt." : "Kein gespawnter Soldat mit diesem Namen in deiner Welt."));
   }

   private void commandArmyAttack(Player commander, String targetName) {
      Player target = Bukkit.getPlayerExact(targetName);
      if (target != null && target.isOnline()) {
         this.commandedTarget = target.getUniqueId();
         this.currentTargets.clear();
         int ordered = 0;

         for(int npcId : new HashSet(this.armyNpcIds)) {
            NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
            if (npc != null && npc.isSpawned() && npc.getEntity() != null && npc.getEntity().getWorld().equals(target.getWorld())) {
               this.setNpcTarget(npc, target);
               ++ordered;
            }
         }

         String var8 = String.valueOf(ChatColor.DARK_RED);
         commander.sendMessage(var8 + "Angriff befohlen! " + String.valueOf(ChatColor.RED) + ordered + " Spieler-NPCs greifen jetzt " + target.getName() + " an.");
      } else {
         String var10001 = String.valueOf(ChatColor.RED);
         commander.sendMessage(var10001 + "Dieser Spieler muss online sein: " + targetName);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onArmyNpcDeath(NPCDeathEvent event) {
      NPC npc = event.getNPC();
      int npcId = npc.getId();
      if (this.armyNpcIds.contains(npcId)) {
         event.getDrops().clear();
         event.setDroppedExp(0);
         String name = npc.getName();
         Bukkit.getScheduler().runTask(this, () -> {
            NPC deadNpc = CitizensAPI.getNPCRegistry().getById(npcId);
            if (deadNpc != null) {
               deadNpc.destroy();
            }

            this.armyNpcIds.remove(npcId);
            this.currentTargets.remove(npcId);
            this.lastAttackAt.remove(npcId);
            this.saveNpcIds();
            String var10000 = String.valueOf(ChatColor.GRAY);
            Bukkit.broadcastMessage(var10000 + name + " ist gefallen und hat die ChickenArmy verlassen.");
         });
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onArmyNpcShieldBlock(EntityDamageByEntityEvent event) {
      NPC npc = CitizensAPI.getNPCRegistry().getNPC(event.getEntity());
      if (npc != null && this.armyNpcIds.contains(npc.getId())) {
         ItemStack shield = ((Equipment)npc.getOrAddTrait(Equipment.class)).get(EquipmentSlot.OFF_HAND);
         if (shield != null && shield.getType() == Material.SHIELD) {
            Entity attacker = event.getDamager();
            if (attacker instanceof Projectile) {
               Projectile projectile = (Projectile)attacker;
               ProjectileSource var7 = projectile.getShooter();
               if (var7 instanceof Entity) {
                  Entity shooter = (Entity)var7;
                  attacker = shooter;
               }
            }

            if (attacker instanceof LivingEntity) {
               Location npcLocation = event.getEntity().getLocation();
               Vector towardAttacker = attacker.getLocation().toVector().subtract(npcLocation.toVector()).setY(0);
               Vector facing = npcLocation.getDirection().setY(0);
               if (towardAttacker.lengthSquared() != (double)0.0F && facing.lengthSquared() != (double)0.0F && !(facing.normalize().dot(towardAttacker.normalize()) < 0.15)) {
                  event.setDamage((double)0.0F);
                  npc.data().set(Metadata.USING_OFFHAND_ITEM, true);
                  Bukkit.getScheduler().runTaskLater(this, () -> {
                     NPC current = CitizensAPI.getNPCRegistry().getById(npc.getId());
                     if (current != null) {
                        current.data().set(Metadata.USING_OFFHAND_ITEM, false);
                     }

                  }, 8L);
               }
            }
         }
      }
   }

   private NPC spawnFighter(Location location, int number, WeaponType weapon) {
      Location spawn = this.findSpawnLocation(location, number);
      String npcName = this.makeRandomNpcName();
      NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, npcName);
      npc.data().set(Metadata.DEFAULT_PROTECTED, false);
      npc.data().set(Metadata.DAMAGE_OTHERS, true);
      npc.data().set(Metadata.DROPS_ITEMS, false);
      npc.data().set(Metadata.PICKUP_ITEMS, false);
      npc.data().set(Metadata.SHOULD_SAVE, true);
      npc.data().set(Metadata.NAMEPLATE_VISIBLE, true);
      npc.data().set(Metadata.REMOVE_FROM_TABLIST, false);
      npc.data().set(Metadata.REMOVE_FROM_PLAYERLIST, false);
      npc.data().set(Metadata.RESPAWN_DELAY, 100);
      this.equipFighter(npc, weapon);
      if (!npc.spawn(spawn)) {
         npc.destroy();
         this.getLogger().warning("NPC konnte nicht gespawnt werden: " + npcName);
         return null;
      } else {
         this.armyNpcIds.add(npc.getId());
         return npc;
      }
   }

   private String makeRandomNpcName() {
      String name;
      boolean exists;
      do {
         String var10000 = UUID.randomUUID().toString().replace("-", "");
         name = "Soldier" + var10000.substring(0, 8);
         exists = false;

         for(NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc.getName().equalsIgnoreCase(name)) {
               exists = true;
               break;
            }
         }
      } while(exists);

      return name;
   }

   private void setTablistVisible(NPC npc) {
      npc.data().set(Metadata.REMOVE_FROM_TABLIST, false);
      npc.data().set(Metadata.REMOVE_FROM_PLAYERLIST, false);
   }

   private WeaponType weaponTypeOf(NPC npc, int fallbackNumber) {
      ItemStack hand = ((Equipment)npc.getOrAddTrait(Equipment.class)).get(EquipmentSlot.HAND);
      if (hand != null) {
         WeaponType var10000;
         switch (hand.getType()) {
            case NETHERITE_SWORD -> var10000 = ChickenArmy.WeaponType.SWORD;
            case NETHERITE_AXE -> var10000 = ChickenArmy.WeaponType.AXE;
            case MACE -> var10000 = ChickenArmy.WeaponType.MACE;
            default -> var10000 = null;
         }

         WeaponType storedWeapon = var10000;
         if (storedWeapon != null) {
            return storedWeapon;
         }
      }

      return npc.getName().toLowerCase(Locale.ROOT).contains("chicken") ? ChickenArmy.WeaponType.MACE : ChickenArmy.WeaponType.values()[Math.floorMod(fallbackNumber, ChickenArmy.WeaponType.values().length)];
   }

   private Location findSpawnLocation(Location center, int number) {
      double angle = (double)number * 2.399963;
      double distance = (double)2.0F + (double)(number % 4) * 0.8;
      Location spawn = center.clone().add(Math.cos(angle) * distance, (double)0.0F, Math.sin(angle) * distance);
      return spawn.getWorld().getHighestBlockAt(spawn).getLocation().add((double)0.5F, (double)1.0F, (double)0.5F);
   }

   private void equipFighter(NPC npc, WeaponType weapon) {
      Equipment equipment = (Equipment)npc.getOrAddTrait(Equipment.class);
      equipment.set(EquipmentSlot.HELMET, this.maxArmor(Material.NETHERITE_HELMET, "helmet"));
      equipment.set(EquipmentSlot.CHESTPLATE, this.maxArmor(Material.NETHERITE_CHESTPLATE, "chestplate"));
      equipment.set(EquipmentSlot.LEGGINGS, this.maxArmor(Material.NETHERITE_LEGGINGS, "leggings"));
      equipment.set(EquipmentSlot.BOOTS, this.maxArmor(Material.NETHERITE_BOOTS, "boots"));
      ItemStack var10000;
      switch (weapon.ordinal()) {
         case 0 -> var10000 = this.maxSword();
         case 1 -> var10000 = this.maxAxe();
         case 2 -> var10000 = this.maxMace();
         default -> throw new MatchException((String)null, (Throwable)null);
      }

      ItemStack weaponItem = var10000;
      equipment.set(EquipmentSlot.HAND, weaponItem);
      equipment.set(EquipmentSlot.OFF_HAND, this.maxShield());
   }

   private ItemStack maxArmor(Material material, String slot) {
      ItemStack item = new ItemStack(material);
      item.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
      item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
      item.addUnsafeEnchantment(Enchantment.MENDING, 1);
      if (slot.equals("helmet")) {
         item.addUnsafeEnchantment(Enchantment.RESPIRATION, 3);
         item.addUnsafeEnchantment(Enchantment.AQUA_AFFINITY, 1);
      } else if (slot.equals("leggings")) {
         item.addUnsafeEnchantment(Enchantment.SWIFT_SNEAK, 3);
      } else if (slot.equals("boots")) {
         item.addUnsafeEnchantment(Enchantment.FEATHER_FALLING, 4);
         item.addUnsafeEnchantment(Enchantment.DEPTH_STRIDER, 3);
         item.addUnsafeEnchantment(Enchantment.SOUL_SPEED, 3);
      }

      return item;
   }

   private ItemStack maxSword() {
      ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
      item.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
      item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
      item.addUnsafeEnchantment(Enchantment.MENDING, 1);
      item.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 2);
      item.addUnsafeEnchantment(Enchantment.KNOCKBACK, 2);
      item.addUnsafeEnchantment(Enchantment.LOOTING, 3);
      item.addUnsafeEnchantment(Enchantment.SWEEPING_EDGE, 3);
      return item;
   }

   private ItemStack maxAxe() {
      ItemStack item = new ItemStack(Material.NETHERITE_AXE);
      item.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
      item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
      item.addUnsafeEnchantment(Enchantment.MENDING, 1);
      item.addUnsafeEnchantment(Enchantment.EFFICIENCY, 5);
      item.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 2);
      return item;
   }

   private ItemStack maxMace() {
      ItemStack item = new ItemStack(Material.MACE);
      item.addUnsafeEnchantment(Enchantment.DENSITY, 5);
      item.addUnsafeEnchantment(Enchantment.WIND_BURST, 3);
      item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
      item.addUnsafeEnchantment(Enchantment.MENDING, 1);
      return item;
   }

   private ItemStack maxShield() {
      ItemStack shield = new ItemStack(Material.SHIELD);
      shield.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
      shield.addUnsafeEnchantment(Enchantment.MENDING, 1);
      return shield;
   }

   private void setNpcTarget(NPC npc, LivingEntity target) {
      if (npc.isSpawned() && npc.getEntity() != null && npc.getEntity().getWorld().equals(target.getWorld())) {
         npc.getNavigator().setTarget(target, true);
         this.currentTargets.put(npc.getId(), target.getUniqueId());
      }
   }

   private void updateTargets() {
      Player playerTarget = this.commandedTarget == null ? null : Bukkit.getPlayer(this.commandedTarget);
      if (this.commandedTarget != null && (playerTarget == null || !playerTarget.isOnline() || playerTarget.isDead())) {
         this.commandedTarget = null;
         this.currentTargets.clear();
         playerTarget = null;
      }

      for(int npcId : new HashSet(this.armyNpcIds)) {
         NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
         if (npc == null) {
            this.armyNpcIds.remove(npcId);
            this.currentTargets.remove(npcId);
         } else if (npc.isSpawned()) {
            Entity current = npc.getEntity();
            if (current instanceof LivingEntity) {
               LivingEntity npcEntity = (LivingEntity)current;
               if (playerTarget != null) {
                  if (npcEntity.getWorld().equals(playerTarget.getWorld())) {
                     UUID previous = (UUID)this.currentTargets.get(npcId);
                     if (!playerTarget.getUniqueId().equals(previous) || !npc.getNavigator().isNavigating() && npcEntity.getLocation().distanceSquared(playerTarget.getLocation()) > (double)4.0F) {
                        this.setNpcTarget(npc, playerTarget);
                     }

                     this.attackIfClose(npc, npcEntity, playerTarget);
                  }
               } else {
                  current = this.currentTargets.containsKey(npcId) ? Bukkit.getEntity((UUID)this.currentTargets.get(npcId)) : null;
                  if (current instanceof Monster) {
                     Monster monster = (Monster)current;
                     if (monster.isValid() && !monster.isDead() && monster.getWorld().equals(npcEntity.getWorld()) && monster.getLocation().distanceSquared(npcEntity.getLocation()) <= (double)576.0F) {
                        if (!npc.getNavigator().isNavigating() && monster.getLocation().distanceSquared(npcEntity.getLocation()) > (double)4.0F) {
                           this.setNpcTarget(npc, monster);
                        }

                        this.attackIfClose(npc, npcEntity, monster);
                        continue;
                     }
                  }

                  Monster nearest = this.findNearestMonster(npcEntity);
                  if (nearest == null) {
                     npc.getNavigator().cancelNavigation();
                     this.currentTargets.remove(npcId);
                  } else {
                     this.setNpcTarget(npc, nearest);
                     this.attackIfClose(npc, npcEntity, nearest);
                  }
               }
            }
         }
      }

   }

   private void attackIfClose(NPC npc, LivingEntity attacker, LivingEntity target) {
      if (!target.isDead() && target.isValid() && attacker.getWorld().equals(target.getWorld()) && !(attacker.getLocation().distanceSquared(target.getLocation()) > (double)4.0F) && attacker.hasLineOfSight(target)) {
         long now = System.currentTimeMillis();
         WeaponType weapon = this.weaponTypeOf(npc, npc.getId());
         long cooldownMillis = weapon == ChickenArmy.WeaponType.MACE ? 1300L : (weapon == ChickenArmy.WeaponType.AXE ? 900L : 650L);
         long lastAttack = (Long)this.lastAttackAt.getOrDefault(npc.getId(), 0L);
         if (now - lastAttack >= cooldownMillis) {
            double var10000;
            switch (weapon.ordinal()) {
               case 0 -> var10000 = (double)11.0F;
               case 1 -> var10000 = (double)13.0F;
               case 2 -> var10000 = (double)9.0F;
               default -> throw new MatchException((String)null, (Throwable)null);
            }

            double damage = var10000;
            target.damage(damage, attacker);
            this.lastAttackAt.put(npc.getId(), now);
         }
      }
   }

   private Monster findNearestMonster(LivingEntity npcEntity) {
      World world = npcEntity.getWorld();
      Monster nearest = null;
      double nearestDistance = (double)576.0F;

      for(Entity entity : world.getNearbyEntities(npcEntity.getLocation(), (double)24.0F, (double)24.0F, (double)24.0F)) {
         if (entity instanceof Monster monster) {
            if (monster.isValid() && !monster.isDead()) {
               double distance = entity.getLocation().distanceSquared(npcEntity.getLocation());
               if (distance < nearestDistance) {
                  nearest = monster;
                  nearestDistance = distance;
               }
            }
         }
      }

      return nearest;
   }

   private void saveNpcIds() {
      List<Integer> ids = new ArrayList(this.armyNpcIds);
      this.getConfig().set("army-npc-ids", ids);
      this.saveConfig();
   }

   private static enum WeaponType {
      SWORD,
      AXE,
      MACE;

      // $FF: synthetic method
      private static WeaponType[] $values() {
         return new WeaponType[]{SWORD, AXE, MACE};
      }
   }
}
