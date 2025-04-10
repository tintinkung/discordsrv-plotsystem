package github.tintinkung.discordps;

import github.scarsz.discordsrv.api.events.Event;
import github.scarsz.discordsrv.dependencies.google.common.util.concurrent.ThreadFactoryBuilder;
import github.scarsz.discordsrv.util.SchedulerUtil;
import github.tintinkung.discordps.api.DiscordPlotSystemAPI;
import github.tintinkung.discordps.core.listeners.DiscordSRVListener;
import github.tintinkung.discordps.core.listeners.PluginLoadedListener;
import github.scarsz.discordsrv.DiscordSRV;


import github.tintinkung.discordps.core.database.DatabaseConnection;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import github.scarsz.discordsrv.dependencies.kyori.adventure.text.format.NamedTextColor;
import github.tintinkung.discordps.core.utils.CoordinatesUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.*;

import static github.scarsz.discordsrv.dependencies.kyori.adventure.text.Component.text;

public final class DiscordPS extends DiscordPlotSystemAPI {
    private static final String VERSION = "1.0.4";

    /**
     * Plot-System plugin Util we referenced to use (CoordinateConversion class)
     * considering that there are running Plot-System instance the server this bot is running on.
     * The method we use are convertToGeo else we need to make an api call for it.
     */
    private static final String PS_UTIL = "com.alpsbte.plotsystem.utils.conversion.CoordinateConversion";
    private static final String PS_UTIL_CONVERT_TO_GEO = "convertToGeo";

    public static final String PLOT_SYSTEM_SYMBOL = "Plot-System"; // PlotSystem main class symbol
    public static final String DISCORD_SRV_SYMBOL = "DiscordSRV"; // DiscordSRV main class symbol

    public static final int LOADING_TIMEOUT = 5000;


    private YamlConfiguration config;
    private DiscordSRVListener discordSrvHook;

    private String shuttingDown = null;

    public @NotNull YamlConfiguration getConfig() {
        return config;
    }

    public static DiscordPS getPlugin() {
        return (DiscordPS) plugin;
    }

    @Override
    public void onEnable() {
        plugin = this;

        // Create configs
        createConfig();

        Thread initThread = getInitThread();
        initThread.start();

        // Plugin startup logic
        Bukkit.getConsoleSender().sendMessage(text("[", NamedTextColor.DARK_GRAY)
                .append(text("Discord Plot System", NamedTextColor.AQUA))
                .append(text("v" + VERSION, NamedTextColor.GOLD))
                .append(text("] Loaded successfully!")).content());
    }

    @Override
    public void onDisable() {
        final ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("DiscordPlotSystem - Shutdown").build();
        try(final ExecutorService executor = Executors.newSingleThreadExecutor(threadFactory)) {
            executor.invokeAll(Collections.singletonList(() -> {
                // Unsubscribe to DiscordSRV
                if(isDiscordSrvHookEnabled()) {
                    if(discordSrvHook.isReady()) {
                        DiscordSRV
                            .getPlugin()
                            .getJda()
                            .removeEventListener(discordSrvHook.getOnDiscordDisconnect());
                    }
                    DiscordSRV.api.unsubscribe(discordSrvHook);
                }

                // shutdown scheduler tasks
                SchedulerUtil.cancelTasks(this);


                // unregister event listeners because of garbage reloading plugins
                HandlerList.unregisterAll(this);

                DiscordPS.warning("==============================================================");
                DiscordPS.warning(shuttingDown);
                DiscordPS.warning(". . . Disabling DiscordPlotSystem V" + VERSION);
                DiscordPS.warning("==============================================================");

                return null;
            }), 15, TimeUnit.SECONDS);

            executor.shutdownNow();
        } catch (InterruptedException | NullPointerException ex) {
            error(ex);
            DiscordPS.warning("==============================================================");
            DiscordPS.warning("Failed to shutdown DiscordPlotSystem properly");
            DiscordPS.warning(". . . Disabling DiscordPlotSystem V" + VERSION);
            DiscordPS.warning("==============================================================");
        }
        super.onDisable();
    }

    public void disablePlugin(@NotNull String shutdownMessage) {
        this.shuttingDown = shutdownMessage;
        SchedulerUtil.runTask(
        this,
            () -> Bukkit.getPluginManager().disablePlugin(this)
        );
    }

    private @NotNull Thread getInitThread() {
        Thread initThread = new Thread(this::init, "DiscordPlotSystem - Initialization");
        initThread.setUncaughtExceptionHandler((t, e) -> {
            DiscordPS.error(e);
            disablePlugin("DiscordPlotSystem failed to load properly: " + e.getMessage());
        });
        return initThread;
    }

    private void init() {

        Plugin discordSRV = getServer().getPluginManager().getPlugin(DISCORD_SRV_SYMBOL);
        Plugin plotSystem = getServer().getPluginManager().getPlugin(PLOT_SYSTEM_SYMBOL);

        if(plotSystem != null) {
            DiscordPS.info("PlotSystem is loaded (enabled: " + getServer().getPluginManager().isPluginEnabled(plotSystem) + ")");
            subscribeToPlotSystemUtil();
        } else {
            DiscordPS.warning("PlotSystem is not enabled: continuing without coordinate conversion optimization");
        }

        if (discordSRV != null) {
            DiscordPS.info("DiscordSRV is loaded (enabled: " + getServer().getPluginManager().isPluginEnabled(discordSRV) + ")");
            subscribeToDiscordSRV(discordSRV);
        } else {
            DiscordPS.error("DiscordSRV is not enabled: continuing without discord support");
            DiscordPS.error("DiscordSRV is not currently enabled (Plot System will not be manage).");
        }

        if(discordSRV == null || plotSystem == null) {

            // Subscribe to DiscordSRV later if it somehow hasn't enabled yet.
            Bukkit.getPluginManager().registerEvents(new PluginLoadedListener(this), this);

            // Timeout if it takes too long to load
            SchedulerUtil.runTaskLater(this, () -> {
                if (!isDiscordSrvHookEnabled()) {
                    this.disablePlugin("DiscordSRV never loaded. timed out.");
                }
            }, LOADING_TIMEOUT);
        }

        // Initialize database connection
        try {
            if(DatabaseConnection.InitializeDatabase()) {
                DiscordPS.info("Successfully initialized database connection.");
            } else {

                this.disablePlugin("Could not initialize database connection due to a misconfigured config file.");
            }
        } catch (Exception ex) {
            DiscordPS.error(ex.getMessage(), ex);

            this.disablePlugin("Could not initialize database connection.");
        }
    }

    private void createConfig() {
        File createConfig = new File(getDataFolder(), "config.yml");
        if (!createConfig.exists()) {
            createConfig.getParentFile().mkdirs();
            saveResource("config.yml", false);
        }
        config = new YamlConfiguration();
        try {
            config.load(createConfig);
        } catch (Exception e) {
            DiscordPS.error("An error occurred!", e);
        }
    }

    public void subscribeToDiscordSRV(Plugin plugin) {
        DiscordPS.info("subscribing to DiscordSRV: " + plugin);

        if (!DISCORD_SRV_SYMBOL.equals(plugin.getName()) || !(plugin instanceof DiscordSRV)) {
            DiscordPS.error(new IllegalArgumentException("Not DiscordSRV: " + plugin));
            return;
        }

        if (isDiscordSrvHookEnabled()) {
            DiscordPS.error(new IllegalStateException(
                "Already subscribed to DiscordSRV. Did the server reload? ... If so, don't do that!"
            ));
            return;
        }

        DiscordSRV.api.subscribe(discordSrvHook = new DiscordSRVListener(this));
        DiscordPS.info("Subscribed to DiscordSRV: Plot System will be manage by its JDA instance.");
    }

    public void subscribeToPlotSystemUtil() {
        try {
            Class<?> conversionUtil = Class.forName(PS_UTIL);
            Method convertToGeo = conversionUtil.getMethod(PS_UTIL_CONVERT_TO_GEO, double.class, double.class);

            CoordinatesUtil.initCoordinatesFunction((xCords, yCords) -> {
                try {
                    return (double[]) convertToGeo.invoke(null, xCords, yCords);
                } catch (IllegalAccessException ex) {
                    throw new RuntimeException("Access error on method: " + convertToGeo.getName(), ex);
                } catch (InvocationTargetException ex) {
                    throw new RuntimeException("Method call failed due to: " + ex.getCause().getMessage(), ex);
                }
            });

            DiscordPS.info("Successfully validated Plot-System symbol reference.");
        }
        catch (ClassNotFoundException | NoSuchMethodException ex) {
            DiscordPS.error("Failed to get Plot-System class reference, coordinates conversion will be disabled", ex);
        }
    }

    public boolean isShuttingDown() {
        return shuttingDown != null;
    }


    public boolean isDiscordSrvHookEnabled() {
        return discordSrvHook != null;
    }

    public <E extends Event> E callDiscordSRVEvent(E event) {
        return DiscordSRV.api.callEvent(event);
    }

    @Nullable
    public TextChannel getStatusChannelOrNull() {
        return getDiscordChannelOrNull(config.getString(ConfigPaths.PLOT_STATUS));
    }

    @Nullable
    public TextChannel getDiscordChannelOrNull(String channelID) {
        return isDiscordSrvHookEnabled() ? DiscordSRV.getPlugin().getJda().getTextChannelById(channelID) : null;
    }

    @Nullable
    public TextChannel getDiscordChannelOrNull(long channelID) {
        return isDiscordSrvHookEnabled() ? DiscordSRV.getPlugin().getJda().getTextChannelById(channelID) : null;
    }

    // log messages
    public static void info(String message) {
        DiscordPlotSystemAPI.info(message);
    }
    public static void warning(String message) {
        DiscordPlotSystemAPI.warning(message);
    }
    public static void error(String message) {
        DiscordPlotSystemAPI.error(message);
    }
    public static void error(Throwable throwable) {
        logThrowable(throwable, DiscordPS::error);
    }
    public static void error(String message, Throwable throwable) {
        DiscordPlotSystemAPI.error(message);
        DiscordPlotSystemAPI.error(throwable);
    }
}
