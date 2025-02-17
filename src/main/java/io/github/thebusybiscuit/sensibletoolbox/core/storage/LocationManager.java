package io.github.thebusybiscuit.sensibletoolbox.core.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import io.github.thebusybiscuit.sensibletoolbox.helpers.Validate;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.configuration.file.YamlConfiguration;

import io.github.thebusybiscuit.sensibletoolbox.SensibleToolboxPlugin;
import io.github.thebusybiscuit.sensibletoolbox.api.SensibleToolbox;
import io.github.thebusybiscuit.sensibletoolbox.api.items.BaseSTBBlock;
import io.github.thebusybiscuit.sensibletoolbox.api.items.BaseSTBItem;
import io.github.thebusybiscuit.sensibletoolbox.utils.STBUtil;
import me.desht.dhutils.Debugger;
import me.desht.dhutils.MiscUtil;
import me.desht.dhutils.blocks.PersistableLocation;
import me.desht.dhutils.text.LogUtils;

/**
 * This class is responsible for managing the data stored at a given {@link Location}.
 * This utilizes a {@link DatabaseManager} in the background and connects to a local
 * SQLite database.
 * 
 * @author desht
 * @author TheBusyBiscuit
 * 
 * @see DatabaseManager
 * @see DatabaseTask
 *
 */
public final class LocationManager {

    private static LocationManager instance;

    private final Set<String> deferredBlocks = new HashSet<>();

    private final PreparedStatement queryStmt;
    private final PreparedStatement queryTypeStmt;

    // The saving interval (in ms)
    private int saveInterval;
    private long lastSave;
    private long totalTicks;
    private long totalTime;
    private final DatabaseManager databaseManager;
    private final Thread updaterTask;
    private static final BlockAccess blockAccess = new BlockAccess();

    // tracks those blocks (on a per-world basis) which need to do something on a server tick
    private final Map<UUID, Set<BaseSTBBlock>> allTickers = new HashMap<>();
    // indexes all loaded blocks by world and (frozen) location
    private final Map<UUID, Map<String, BaseSTBBlock>> blockIndex = new HashMap<>();
    // tracks the pending updates by (frozen) location since the last save was done
    private final Map<String, UpdateRecord> pendingUpdates = new HashMap<>();
    // a blocking queue is used to pass actual updates over to the DB writer thread
    private final BlockingQueue<UpdateRecord> updateQueue = new LinkedBlockingQueue<>();

    private LocationManager(@Nonnull SensibleToolboxPlugin plugin) throws SQLException {
        saveInterval = plugin.getConfig().getInt("save_interval", 30) * 1000;
        lastSave = System.currentTimeMillis();

        databaseManager = new DatabaseManager(plugin.getLogger());
        databaseManager.getConnection().setAutoCommit(false);
        queryStmt = databaseManager.getConnection().prepareStatement("SELECT * FROM " + DatabaseManager.getFullTableName("blocks") + " WHERE world_id = ?");
        queryTypeStmt = databaseManager.getConnection().prepareStatement("SELECT * FROM " + DatabaseManager.getFullTableName("blocks") + " WHERE world_id = ? and type = ?");
        updaterTask = new Thread(new DatabaseTask(this), "STB - Database Thread");
    }

    @Nullable
    public static synchronized LocationManager getManager() {
        if (instance == null) {
            SensibleToolboxPlugin plugin = SensibleToolboxPlugin.getInstance();

            try {
                instance = new LocationManager(plugin);
                instance.updaterTask.start();
            } catch (IllegalStateException | LinkageError e) {
                plugin.getLogger().log(Level.SEVERE, "An Exception disturbed the initialization of our LocationManager", e);
                plugin.getServer().getPluginManager().disablePlugin(plugin);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Cannot get the LocationManager", e);
            }
        }

        return instance;
    }

    @Nonnull
    DatabaseManager getDatabaseConnection() {
        return databaseManager;
    }

    public void addTicker(@Nonnull BaseSTBBlock stb) {
        Validate.notNull(stb, "Cannot add a ticker that is null!");

        Location loc = stb.getLocation();
        World w = loc.getWorld();
        Set<BaseSTBBlock> tickerSet = allTickers.get(w.getUID());

        if (tickerSet == null) {
            tickerSet = new HashSet<>();
            allTickers.put(w.getUID(), tickerSet);
        }

        tickerSet.add(stb);
        Debugger.getInstance().debug(2, "Added ticking block " + stb);
    }

    @Nonnull
    private Map<String, BaseSTBBlock> getWorldIndex(@Nonnull World w) {
        Map<String, BaseSTBBlock> index = blockIndex.get(w.getUID());

        if (index == null) {
            index = new HashMap<>();
            blockIndex.put(w.getUID(), index);
        }

        return index;
    }

    public void registerLocation(Location loc, BaseSTBBlock stb, boolean isPlacing) {
        BaseSTBBlock stb2 = get(loc);

        if (stb2 != null) {
            LogUtils.warning("Attempt to register duplicate STB block " + stb + " @ " + loc + " - existing block " + stb2);
            return;
        }

        stb.setLocation(blockAccess, loc);

        String locStr = MiscUtil.formatLocation(loc);
        getWorldIndex(loc.getWorld()).put(locStr, stb);
        stb.preRegister(blockAccess, loc, isPlacing);

        if (isPlacing) {
            addPendingDatabaseOperation(loc, locStr, DatabaseOperation.INSERT);
        }

        if (stb.getTickRate() > 0) {
            addTicker(stb);
        }

        Debugger.getInstance().debug("Registered " + stb + " @ " + loc);
    }

    public void updateLocation(Location loc) {
        addPendingDatabaseOperation(loc, MiscUtil.formatLocation(loc), DatabaseOperation.UPDATE);
    }

    public void unregisterLocation(Location loc, BaseSTBBlock stb) {
        if (stb != null) {
            stb.onBlockUnregistered(loc);
            String locStr = MiscUtil.formatLocation(loc);
            addPendingDatabaseOperation(loc, locStr, DatabaseOperation.DELETE);
            getWorldIndex(loc.getWorld()).remove(locStr);
            Debugger.getInstance().debug("Unregistered " + stb + " @ " + loc);
        } else {
            LogUtils.warning("Attempt to unregister non-existent STB block @ " + loc);
        }
    }

    /**
     * Move an existing STB block to a new location. Note that this method doesn't do any
     * redrawing of blocks.
     *
     * @param oldLoc
     *            the STB block's old location
     * @param newLoc
     *            the STB block's new location
     */
    public void moveBlock(BaseSTBBlock stb, Location oldLoc, Location newLoc) {

        // TODO: translate multi-block structures

        String locStr = MiscUtil.formatLocation(oldLoc);
        addPendingDatabaseOperation(oldLoc, locStr, DatabaseOperation.DELETE);
        getWorldIndex(oldLoc.getWorld()).remove(locStr);

        stb.moveTo(blockAccess, oldLoc, newLoc);

        locStr = MiscUtil.formatLocation(newLoc);
        addPendingDatabaseOperation(newLoc, locStr, DatabaseOperation.INSERT);
        getWorldIndex(newLoc.getWorld()).put(locStr, stb);

        Debugger.getInstance().debug("moved " + stb + " from " + oldLoc + " to " + newLoc);
    }

    private void addPendingDatabaseOperation(Location loc, String locStr, DatabaseOperation op) {
        UpdateRecord existingRec = pendingUpdates.get(locStr);

        switch (op) {
            case INSERT:
                if (existingRec == null) {
                    // brand new insertion
                    pendingUpdates.put(locStr, new UpdateRecord(DatabaseOperation.INSERT, loc));
                } else if (existingRec.getOp() == DatabaseOperation.DELETE) {
                    // re-inserting where a block was just deleted
                    pendingUpdates.put(locStr, new UpdateRecord(DatabaseOperation.UPDATE, loc));
                }
                break;
            case UPDATE:
                if (existingRec == null || existingRec.getOp() != DatabaseOperation.INSERT) {
                    pendingUpdates.put(locStr, new UpdateRecord(DatabaseOperation.UPDATE, loc));
                }
                break;
            case DELETE:
                if (existingRec != null && existingRec.getOp() == DatabaseOperation.INSERT) {
                    // remove a recent insertion
                    pendingUpdates.remove(locStr);
                } else {
                    pendingUpdates.put(locStr, new UpdateRecord(DatabaseOperation.DELETE, loc));
                }
                break;
            default:
                throw new IllegalArgumentException("Unexpected operation: " + op);
        }
    }

    /**
     * Get the STB block at the given location.
     *
     * @param loc
     *            the location to check at
     * 
     * @return the STB block at the given location, or null if no matching item
     */
    @Nullable
    public BaseSTBBlock get(Location loc) {
        return get(loc, false);
    }

    /**
     * Get the STB block at the given location, or if the location contains a
     * sign, possibly at the location the sign is attached to.
     *
     * @param loc
     *            the location to check at
     * @param checkSigns
     *            if true, and the location contains a sign, check at
     *            the location that the sign is attached to
     * 
     * @return the STB block at the given location, or null if no matching item
     */
    @Nullable
    public BaseSTBBlock get(Location loc, boolean checkSigns) {
        Block b = loc.getBlock();

        if (checkSigns && Tag.WALL_SIGNS.isTagged(b.getType())) {
            WallSign sign = (WallSign) b.getBlockData();
            b = b.getRelative(sign.getFacing().getOppositeFace());
        }

        BaseSTBBlock stb = (BaseSTBBlock) STBUtil.getMetadataValue(b, BaseSTBBlock.STB_BLOCK);

        if (stb != null) {
            return stb;
        } else {
            // perhaps it's part of a multi-block structure
            return (BaseSTBBlock) STBUtil.getMetadataValue(b, BaseSTBBlock.STB_MULTI_BLOCK);
        }
    }

    /**
     * Get the STB block of the given type at the given location.
     *
     * @param loc
     *            the location to check at
     * @param type
     *            the type of STB block required
     * @param <T>
     *            a subclass of BaseSTBBlock
     * 
     * @return the STB block at the given location, or null if no matching item
     */
    @Nullable
    public <T extends BaseSTBBlock> T get(Location loc, Class<T> type) {
        return get(loc, type, false);
    }

    /**
     * Get the STB block of the given type at the given location.
     *
     * @param loc
     *            the location to check at
     * @param type
     *            the type of STB block required
     * @param <T>
     *            a subclass of BaseSTBBlock
     * @param checkSigns
     *            if true, and the location contains a sign, check at
     *            the location that the sign is attached to
     * 
     * @return the STB block at the given location, or null if no matching item
     */
    @Nullable
    public <T extends BaseSTBBlock> T get(Location loc, Class<T> type, boolean checkSigns) {
        BaseSTBBlock stbBlock = get(loc, checkSigns);

        if (stbBlock != null && type.isAssignableFrom(stbBlock.getClass())) {
            return type.cast(stbBlock);
        } else {
            return null;
        }
    }

    /**
     * Get all the STB blocks in the given chunk
     *
     * @param chunk
     *            the chunk to check
     * 
     * @return a {@link List} of STB block objects
     */
    @Nonnull
    public List<BaseSTBBlock> get(@Nonnull Chunk chunk) {
        List<BaseSTBBlock> res = new ArrayList<>();

        for (BaseSTBBlock stb : listBlocks(chunk.getWorld(), false)) {
            PersistableLocation pLoc = stb.getPersistableLocation();

            if ((int) pLoc.getX() >> 4 == chunk.getX() && (int) pLoc.getZ() >> 4 == chunk.getZ()) {
                res.add(stb);
            }
        }

        return res;
    }

    public void tick() {
        long now = System.nanoTime();

        for (World world : Bukkit.getWorlds()) {
            tickWorld(world);
        }

        totalTicks++;
        totalTime += System.nanoTime() - now;

        if (System.currentTimeMillis() - lastSave > saveInterval) {
            save();
        }
    }

    private void tickWorld(@Nonnull World world) {
        Set<BaseSTBBlock> tickerSet = allTickers.get(world.getUID());

        if (tickerSet != null) {
            Iterator<BaseSTBBlock> iter = tickerSet.iterator();

            while (iter.hasNext()) {
                BaseSTBBlock stb = iter.next();

                if (stb.isPendingRemoval()) {
                    Debugger.getInstance().debug("Removing block " + stb + " from tickers list");
                    iter.remove();
                } else {
                    PersistableLocation pLoc = stb.getPersistableLocation();
                    int x = (int) pLoc.getX();
                    int z = (int) pLoc.getZ();

                    if (world.isChunkLoaded(x >> 4, z >> 4)) {
                        stb.tick();

                        if (stb.getTicksLived() % stb.getTickRate() == 0) {
                            stb.onServerTick();
                        }
                    }
                }
            }
        }
    }

    public void save() {
        // send any pending updates over to the DB updater thread via a BlockingQueue
        if (!pendingUpdates.isEmpty()) {
            // TODO: may want to do this over a few ticks to reduce the risk of lag spikes
            for (UpdateRecord rec : pendingUpdates.values()) {
                BaseSTBBlock stb = get(rec.getLocation());

                if (stb == null && rec.getOp() != DatabaseOperation.DELETE) {
                    LogUtils.severe("STB block @ " + rec.getLocation() + " is null, but should not be!");
                    continue;
                }

                if (stb != null) {
                    rec.setType(stb.getItemTypeID());
                    rec.setData(stb.freeze().saveToString());
                }

                updateQueue.add(rec);
            }

            updateQueue.add(UpdateRecord.commitRecord());
            pendingUpdates.clear();
        }

        lastSave = System.currentTimeMillis();
    }

    public void loadFromDatabase(@Nonnull World world, @Nullable String wantedType) throws SQLException {
        ResultSet rs = getResultsFor(world, wantedType);

        while (rs.next()) {
            String type = rs.getString(5);

            if (deferredBlocks.contains(type) && !type.equals(wantedType)) {
                continue;
            }

            int x = rs.getInt(2);
            int y = rs.getInt(3);
            int z = rs.getInt(4);
            String data = rs.getString(6);

            try {
                YamlConfiguration conf = new YamlConfiguration();
                conf.loadFromString(data);
                BaseSTBItem stbItem = SensibleToolbox.getItemRegistry().getItemById(type, conf);

                if (stbItem != null) {
                    Location loc = new Location(world, x, y, z);

                    if (stbItem instanceof BaseSTBBlock) {
                        registerLocation(loc, (BaseSTBBlock) stbItem, false);
                    } else {
                        LogUtils.severe("STB item " + type + " @ " + loc + " is not a block!");
                    }
                } else {
                    // defer it - should hopefully be registered by another plugin later
                    Debugger.getInstance().debug("deferring load for unrecognised block type '" + type + "'");
                    deferBlockLoad(type);
                }
            } catch (Exception e) {
                e.printStackTrace();
                LogUtils.severe(String.format("Can't load STB block at %s,%d,%d,%d: %s", world.getName(), x, y, z, e.getMessage()));
            }
        }

        rs.close();
    }

    @Nonnull
    private ResultSet getResultsFor(@Nonnull World world, @Nullable String wantedType) throws SQLException {
        if (wantedType == null) {
            queryStmt.setString(1, world.getUID().toString());
            return queryStmt.executeQuery();
        } else {
            queryTypeStmt.setString(1, world.getUID().toString());
            queryTypeStmt.setString(2, wantedType);
            return queryTypeStmt.executeQuery();
        }
    }

    public void load() throws SQLException {
        for (World w : Bukkit.getWorlds()) {
            loadFromDatabase(w, null);
        }
    }

    private void deferBlockLoad(@Nonnull String typeID) {
        deferredBlocks.add(typeID);
    }

    /**
     * Load all blocks for the given block type. Called when a block is registered after the
     * initial DB load is done.
     *
     * @param type
     *            the block type
     * @throws SQLException
     *             if there is a problem loading from the DB
     */
    public void loadDeferredBlocks(@Nonnull String type) throws SQLException {
        if (deferredBlocks.contains(type)) {
            for (World world : Bukkit.getWorlds()) {
                loadFromDatabase(world, type);
            }

            deferredBlocks.remove(type);
        }
    }

    /**
     * The given world has just become unloaded..
     *
     * @param world
     *            the world that has been unloaded
     */
    public void unloadWorld(@Nonnull World world) {
        save();

        Map<String, BaseSTBBlock> map = blockIndex.get(world.getUID());

        if (map != null) {
            map.clear();
            blockIndex.remove(world.getUID());
        }
    }

    /**
     * The given world has just become loaded.
     *
     * @param world
     *            the world that has been loaded
     */
    public void loadWorld(@Nonnull World world) {
        if (!blockIndex.containsKey(world.getUID())) {
            try {
                loadFromDatabase(world, null);
            } catch (SQLException e) {
                e.printStackTrace();
                LogUtils.severe("can't load STB data for world " + world.getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Get a list of all STB blocks for the given world.
     *
     * @param world
     *            the world to query
     * @param sorted
     *            if true, the array is sorted by block type
     * 
     * @return a {@link List} of STB block objects
     */
    @Nonnull
    public List<BaseSTBBlock> listBlocks(World world, boolean sorted) {
        Collection<BaseSTBBlock> list = getWorldIndex(world).values();
        return sorted ? MiscUtil.asSortedList(list) : new ArrayList<>(list);
    }

    /**
     * Get the average time in nanoseconds that the plugin has spent ticking tickable blocks
     * since the plugin started up.
     *
     * @return the average time spent ticking blocks
     */
    public long getAverageTimePerTick() {
        return totalTime / totalTicks;
    }

    /**
     * Set the save interval; any changes will be written to the persisted DB this often.
     *
     * @param saveInterval
     *            the save interval, in seconds.
     */
    public void setSaveInterval(int saveInterval) {
        this.saveInterval = saveInterval * 1000;
    }

    /**
     * Shut down the location manager after ensuring all pending changes are written to the DB,
     * and the DB thread has exited. This may block the main thread for a short time, but should only
     * be called when the plugin is being disabled.
     */
    public void shutdown() {
        updateQueue.add(UpdateRecord.finishingRecord());

        try {
            // 5 seconds is hopefully enough for the DB thread to finish its work
            updaterTask.join(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
            Thread.currentThread().interrupt();
        }

        try {
            databaseManager.getConnection().close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    UpdateRecord getUpdateRecord() throws InterruptedException {
        return updateQueue.take();
    }
}
