package me.desht.sensibletoolbox.items;

import com.comphenix.attribute.AttributeStorage;
import me.desht.dhutils.Debugger;
import me.desht.dhutils.ItemGlow;
import me.desht.dhutils.LogUtils;
import me.desht.sensibletoolbox.STBFreezable;
import me.desht.sensibletoolbox.SensibleToolboxPlugin;
import me.desht.sensibletoolbox.api.Chargeable;
import me.desht.sensibletoolbox.api.STBBlock;
import me.desht.sensibletoolbox.api.STBItem;
import me.desht.sensibletoolbox.blocks.*;
import me.desht.sensibletoolbox.blocks.machines.*;
import me.desht.sensibletoolbox.gui.InventoryGUI;
import me.desht.sensibletoolbox.items.components.CircuitBoard;
import me.desht.sensibletoolbox.items.components.MachineFrame;
import me.desht.sensibletoolbox.items.components.SimpleCircuit;
import me.desht.sensibletoolbox.items.energycells.FiftyKEnergyCell;
import me.desht.sensibletoolbox.items.energycells.TenKEnergyCell;
import me.desht.sensibletoolbox.items.itemroutermodules.*;
import me.desht.sensibletoolbox.items.machineupgrades.EjectorUpgrade;
import me.desht.sensibletoolbox.items.machineupgrades.SpeedUpgrade;
import me.desht.sensibletoolbox.recipes.CustomRecipeManager;
import me.desht.sensibletoolbox.storage.LocationManager;
import me.desht.sensibletoolbox.util.STBUtil;
import org.apache.commons.lang.Validate;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.sql.SQLException;
import java.util.*;

public abstract class BaseSTBItem implements STBFreezable, Comparable<STBItem>, InventoryGUI.InventoryGUIListener, STBItem {
	public static final ChatColor LORE_COLOR = ChatColor.GRAY;
	protected static final ChatColor DISPLAY_COLOR = ChatColor.YELLOW;
	private static final String STB_LORE_PREFIX = ChatColor.DARK_GRAY.toString() + ChatColor.ITALIC + "\u25b9";
	public static final String SUFFIX_SEPARATOR = " \uff1a ";

	private static final Map<String, Class<? extends BaseSTBItem>> id2class = new HashMap<String, Class<? extends BaseSTBItem>>();
	private static final Map<Material,Class<? extends STBItem>> customSmelts = new HashMap<Material, Class<? extends STBItem>>();
	private static final Map<String, Class<? extends STBItem>> customIngredients = new HashMap<String, Class<? extends STBItem>>();
	private static final Map<String,String> id2plugin = new HashMap<String, String>();
	public static final int MAX_ITEM_ID_LENGTH = 32;

	private final String typeID;
	private final String providerName;
	private Map<Enchantment,Integer> enchants;

	public static void registerItems(SensibleToolboxPlugin plugin) {
		registerItem(new AngelicBlock(), plugin);
		registerItem(new EnderLeash(), plugin);
		registerItem(new RedstoneClock(), plugin);
		registerItem(new BlockUpdateDetector(), plugin);
		registerItem(new BagOfHolding(), plugin);
		registerItem(new WateringCan(), plugin);
		registerItem(new MoistureChecker(), plugin);
		registerItem(new AdvancedMoistureChecker(), plugin);
		registerItem(new WoodCombineHoe(), plugin);
		registerItem(new IronCombineHoe(), plugin);
		registerItem(new GoldCombineHoe(), plugin);
		registerItem(new DiamondCombineHoe(), plugin);
		registerItem(new TrashCan(), plugin);
		registerItem(new PaintBrush(), plugin);
		registerItem(new PaintRoller(), plugin);
		registerItem(new PaintCan(), plugin);
		registerItem(new Elevator(), plugin);
		registerItem(new TapeMeasure(), plugin);
		registerItem(new CircuitBoard(), plugin);
		registerItem(new SimpleCircuit(), plugin);
		registerItem(new BuildersMultiTool(), plugin);
		registerItem(new Floodlight(), plugin);
		registerItem(new MachineFrame(), plugin);
		registerItem(new Smelter(), plugin);
		registerItem(new Masher(), plugin);
		registerItem(new Sawmill(), plugin);
		registerItem(new IronDust(), plugin);
		registerItem(new GoldDust(), plugin);
		registerItem(new ItemRouter(), plugin);
		registerItem(new BlankModule(), plugin);
		registerItem(new PullerModule(), plugin);
		registerItem(new DropperModule(), plugin);
		registerItem(new SenderModule(), plugin);
		registerItem(new DistributorModule(), plugin);
		registerItem(new AdvancedSenderModule(), plugin);
		registerItem(new ReceiverModule(), plugin);
		registerItem(new SorterModule(), plugin);
		registerItem(new VacuumModule(), plugin);
		registerItem(new StackModule(), plugin);
		registerItem(new SpeedModule(), plugin);
		registerItem(new TenKEnergyCell(), plugin);
		registerItem(new FiftyKEnergyCell(), plugin);
		registerItem(new FiftyKBatteryBox(), plugin);
		registerItem(new SpeedUpgrade(), plugin);
		registerItem(new EjectorUpgrade(), plugin);
		registerItem(new HeatEngine(), plugin);
		registerItem(new BasicSolarCell(), plugin);
		registerItem(new RecipeBook(), plugin);
		registerItem(new Multimeter(), plugin);
		registerItem(new BigStorageUnit(), plugin);
		registerItem(new HyperStorageUnit(), plugin);
		if (plugin.isProtocolLibEnabled()) {
			registerItem(new SoundMuffler(), plugin);
		}
	}

	public static void registerItem(BaseSTBItem item, Plugin plugin) {
		String id = item.getItemTypeID();
		if (!plugin.getConfig().getBoolean("items_enabled." + id)) {
			return;
		}
		Validate.isTrue(id.length() <= MAX_ITEM_ID_LENGTH, "Item ID '" + id + "' is too long! (32 chars max)");
		id2class.put(id, item.getClass());
		id2plugin.put(id, plugin.getDescription().getName());

		Bukkit.getPluginManager().addPermission(new Permission("stb.interact." + id, PermissionDefault.TRUE));

		if (item instanceof STBBlock) {
			Bukkit.getPluginManager().addPermission(new Permission("stb.place." + id, PermissionDefault.TRUE));
			Bukkit.getPluginManager().addPermission(new Permission("stb.break." + id, PermissionDefault.TRUE));
			Bukkit.getPluginManager().addPermission(new Permission("stb.interact_block." + id, PermissionDefault.TRUE));
			try {
				LocationManager.getManager().loadDeferredBlocks(id);
			} catch (SQLException e) {
				LogUtils.severe("There was a problem restoring blocks of type '" + id + "' from persisted storage:");
				e.printStackTrace();
			}
		}
	}

	public static void setupRecipes() {
		for (String key : id2class.keySet()) {
			STBItem item = getItemById(key);
			Recipe r = item.getRecipe();
			if (r != null) {
				Bukkit.addRecipe(r);
			}
			for (Recipe r2 : item.getExtraRecipes()) {
				Bukkit.addRecipe(r2);
			}
			ItemStack stack = item.getSmeltingResult();
			if (stack != null) {
				Bukkit.addRecipe(new FurnaceRecipe(stack, item.getMaterial()));
				customSmelts.put(item.getMaterial(), item.getClass());
			}
		}
		for (String key : id2class.keySet()) {
			STBItem item = getItemById(key);
			if (item instanceof BaseSTBMachine) {
				((BaseSTBMachine)item).addCustomRecipes(CustomRecipeManager.getManager());
			}
		}
	}

	/**
	 * Given an item (whose material has previously been registered as a Bukkit FurnaceRecipe ingrediet),
	 * return the STB item class which this item must be a type of.
	 *
 	 * @param stack the item stack to check
	 * @return the required STB class, or null if no custom smelting restriction has been registered
	 */
	public static Class<? extends STBItem> getCustomSmelt(ItemStack stack) {
		return customSmelts.get(stack.getType());
	}

	/**
	 * Get a set of all known STB item ID's.
	 *
	 * @return all know STB item ID's
	 */
	public static Set<String> getItemIds() {
		return id2class.keySet();
	}

	/**
	 * Construct and return an STB item from a supplied ItemStack.
	 *
	 * @param stack the item stack
	 * @return the STB item, or null if the item stack is not an STB item
	 */
	public static BaseSTBItem getItemFromItemStack(ItemStack stack) {
		if (!isSTBItem(stack)) {
			return null;
		}
		Configuration conf = BaseSTBItem.getItemAttributes(stack);
		BaseSTBItem item = getItemById(conf.getString("*TYPE"), conf);
		if (item != null) {
			item.storeEnchants(stack);
		}
		return item;
	}

	/**
	 * Construct and return an STB item from a supplied ItemStack.  The item must be an instance of
	 * the supplied class (or a subclass of the supplied class).
	 *
	 * @param stack the ItemStack
	 * @param type the required class
	 * @param <T> parametrised type, a subclass of BaseSTBItem
	 * @return the STB item, or null if the item stack is not an STB item of the desired class
	 */
	public static <T extends BaseSTBItem> T getItemFromItemStack(ItemStack stack, Class<T> type) {
		STBItem item = getItemFromItemStack(stack);
		if (item != null && type.isAssignableFrom(item.getClass())) {
			return type.cast(item);
		} else {
			return null;
		}
	}

	/**
	 * Construct and return an STB item.
	 *
	 * @param id the item ID
	 * @return the STB item
	 */
	public static STBItem getItemById(String id) {
		return getItemById(id, null);
	}

	/**
	 * Construct and return an STB item.
	 *
	 * @param id the item ID
	 * @param conf item's frozen configuration data
	 * @return the STB item
	 */
	public static BaseSTBItem getItemById(String id, ConfigurationSection conf) {
		Class<? extends BaseSTBItem> c = id2class.get(id);
		if (c == null) {
			return null;
		}
		try {
			BaseSTBItem item;
			if (conf == null) {
				Constructor<? extends BaseSTBItem> cons = c.getConstructor();
				item = cons.newInstance();
			} else {
				Constructor<? extends BaseSTBItem> cons = c.getConstructor(ConfigurationSection.class);
				item = cons.newInstance(conf);
			}
			return item;
		} catch (Exception e) {
			LogUtils.warning("failed to create STB item from item ID: " + id);
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Check if the given item stack is an STB item.
	 *
	 * @param stack the item stack to check
	 * @return true if this item stack is an STB item
	 */
	public static boolean isSTBItem(ItemStack stack) {
		return isSTBItem(stack, null);
	}

	/**
	 * Check if the given item stack is an STB item of the given STB subclass
	 *
	 * @param stack the item stack to check
	 * @param c a subclass of BaseSTBItem
	 * @return true if this item stack is an STB item of (or extending) the given class
	 */
	public static boolean isSTBItem(ItemStack stack, Class<? extends STBItem> c) {
		if (stack == null || !stack.hasItemMeta()) {
			return false;
		}
		ItemMeta im = stack.getItemMeta();
		if (im.hasLore()) {
			List<String> lore = im.getLore();
			if (!lore.isEmpty() && lore.get(0).startsWith(STB_LORE_PREFIX)) {
				if (c != null) {
					Configuration conf = BaseSTBItem.getItemAttributes(stack);
					Class<? extends STBItem> c2 = id2class.get(conf.getString("*TYPE"));
					return c2 != null && c.isAssignableFrom(c2);
				} else {
					return true;
				}
			}
		}
		return false;
	}

	private static Configuration getItemAttributes(ItemStack stack) {
		AttributeStorage storage = AttributeStorage.newTarget(stack, SensibleToolboxPlugin.UNIQUE_ID);
		YamlConfiguration conf = new YamlConfiguration();
		try {
			String s = storage.getData("");
			if (s != null) {
				conf.loadFromString(s);
				if (Debugger.getInstance().getLevel() > 2) {
					Debugger.getInstance().debug(3, "get item attributes for " + STBUtil.describeItemStack(stack) + ":");
					for (String k : conf.getKeys(false)) { Debugger.getInstance().debug(3, "- " + k + " = " + conf.get(k)); }
				}
				return conf;
			} else {
				throw new IllegalStateException("ItemStack " + stack + " has no STB attribute data!");
			}
		} catch (InvalidConfigurationException e) {
			e.printStackTrace();
			return new MemoryConfiguration();
		}
	}

	protected BaseSTBItem() {
		typeID = getClass().getSimpleName().toLowerCase();
		providerName = id2plugin.get(typeID);
	}

	protected BaseSTBItem(ConfigurationSection conf) {
		typeID = getClass().getSimpleName().toLowerCase();
		providerName = id2plugin.get(typeID);
	}

	private void storeEnchants(ItemStack stack) {
		enchants = stack.getEnchantments();
	}

	@Override
	public final Class<? extends STBItem> getCraftingRestriction(Material mat) {
		return customIngredients.get(getItemTypeID() + ":" + mat);
	}

	@Override
	public final boolean isIngredientFor(ItemStack result) {
		STBItem item = BaseSTBItem.getItemFromItemStack(result);
		if (item == null) {
			return false;
		}
		Class<? extends STBItem> c = item.getCraftingRestriction(getMaterial());
		return c == getClass();
	}

	@Override
	public final Material getMaterial() { return getMaterialData().getItemType(); }

	@Override
	public String getDisplaySuffix() { return null; }

	@Override
	public String[] getExtraLore() { return new String[0]; }

	@Override
	public Recipe[] getExtraRecipes() { return new Recipe[0]; }

	/**
	 * Register one or more STB items as custom ingredients in the crafting recipe for
	 * this item.  This will ensure that only these items, and not the vanilla item which
	 * uses the same material, will work in the crafting recipe.
	 *
	 * @param items the STB items to register as custom ingredients
	 */
	protected final void registerCustomIngredients(STBItem... items) {
		for (STBItem item : items) {
			customIngredients.put(getItemTypeID() + ":" + item.getMaterial(), item.getClass());
		}
	}

	@Override
	public boolean hasGlow() { return false; }

	/**
	 * Called when a player interacts with a block or air while holding an STB item.
	 *
	 * @param event the interaction event.
	 */
	public void onInteractItem(PlayerInteractEvent event) { }

	/**
	 * Called when a player attempts to consume an STB item (which must be food or potion).
	 *
	 * @param event the consume event
	 */
	public void onItemConsume(PlayerItemConsumeEvent event) { }

	/**
	 * Called when a player interacts with an entity while holding an STB item.
	 *
	 * @param event the interaction event
	 */
	public void onInteractEntity(PlayerInteractEntityEvent event) { }

	/**
	 * Called when a player rolls the mouse wheel while sneaking and holding an STB item.
	 *
	 * @param event the held item change event
	 */
	public void onItemHeld(PlayerItemHeldEvent event) { }

	@Override
	public ItemStack getSmeltingResult() { return null; }

	@Override
	public boolean isEnchantable() {
		return true;
	}

	/**
	 * Called when a block is broken while holding an STB item.  If the block being broken is an STB
	 * block, this event handler will be called before the event handler for the block being broken.
	 * The handler is called with EventPriority.MONITOR, so the event outcome must not be altered by
	 * this handler.
	 *
	 * @param event the block break event
	 */
	public void onBreakBlockWithItem(BlockBreakEvent event) {
	}

	@Override
	public ItemStack toItemStack() {
		return toItemStack(1);
	}

	@Override
	public ItemStack toItemStack(int amount) {
		ItemStack res = getMaterialData().toItemStack(amount);

		ItemMeta im = res.getItemMeta();
		String suffix = getDisplaySuffix() == null ? "" : SUFFIX_SEPARATOR + getDisplaySuffix();
		im.setDisplayName(DISPLAY_COLOR + getItemName() + suffix);
		im.setLore(buildLore());
		res.setItemMeta(im);
		if (enchants != null) {
			res.addUnsafeEnchantments(enchants);
		}
		if (SensibleToolboxPlugin.getInstance().isProtocolLibEnabled()) {
			ItemGlow.setGlowing(res, hasGlow());
		}

		if (this instanceof Chargeable && res.getType().getMaxDurability() > 0) {
			// encode the STB item's charge level into the itemstack's damage bar
			Chargeable ch = (Chargeable) this;
			short max = res.getType().getMaxDurability();
			double d = ch.getCharge() / (double) ch.getMaxCharge();
			short dur = (short)(max * d);
			res.setDurability((short)(max - dur));
		}

		// any serialized data from the object goes in the ItemStack attributes
		YamlConfiguration conf = freeze();
		conf.set("*TYPE", getItemTypeID());
		AttributeStorage storage = AttributeStorage.newTarget(res, SensibleToolboxPlugin.UNIQUE_ID);
		String data = conf.saveToString();
		storage.setData(data);
		Debugger.getInstance().debug(3, "serialize " + this + " to itemstack:\n" + data);
		return storage.getTarget();
	}

	private List<String> buildLore() {
		String[] lore = getLore();
		String[] lore2 = getExtraLore();
		List<String> res = new ArrayList<String>(lore.length + lore2.length + 1);
		res.add(STB_LORE_PREFIX + getProviderName() + " (STB) item");
		for (String l : lore) {
			res.add(LORE_COLOR + l);
		}
		Collections.addAll(res, lore2);
		return res;
	}

	@Override
	public YamlConfiguration freeze() {
		return new YamlConfiguration();
	}

	@Override
	public String toString() {
		return "STB Item [" + getItemName() + "]";
	}

	@Override
	public int compareTo(STBItem other) {
		return getItemName().compareTo(other.getItemName());
	}

	@Override
	public String getItemTypeID() {
		return typeID; // getClass().getSimpleName().toLowerCase();
	}

	@Override
	public boolean isWearable() {
		return STBUtil.isWearable(getMaterial());
	}

	@Override
	public boolean onSlotClick(HumanEntity player, int slot, ClickType click, ItemStack inSlot, ItemStack onCursor) {
		return false;
	}

	@Override
	public boolean onPlayerInventoryClick(HumanEntity player, int slot, ClickType click, ItemStack inSlot, ItemStack onCursor) {
		return true;
	}

	@Override
	public int onShiftClickInsert(HumanEntity player, int slot, ItemStack toInsert) {
		return 0;
	}

	@Override
	public boolean onShiftClickExtract(HumanEntity player, int slot, ItemStack toExtract) {
		return true;
	}

	@Override
	public boolean onClickOutside(HumanEntity player) {
		return false;
	}

	@Override
	public void onGUIClosed(HumanEntity player) {
	}

	public String getProviderName() {
		return providerName;
	}
}
