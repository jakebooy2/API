package com.sheepybot;

import com.google.gson.JsonParser;
import com.moandjiezana.toml.Toml;
import com.sedmelluq.discord.lavaplayer.tools.PlayerLibrary;
import com.sheepybot.api.entities.command.RootCommandRegistry;
import com.sheepybot.api.entities.database.Database;
import com.sheepybot.api.entities.database.auth.DatabaseInfo;
import com.sheepybot.api.entities.event.RootEventRegistry;
import com.sheepybot.api.entities.language.I18n;
import com.sheepybot.api.entities.module.Module;
import com.sheepybot.api.entities.module.loader.ModuleLoader;
import com.sheepybot.api.entities.scheduler.Scheduler;
import com.sheepybot.internal.command.CommandRegistryImpl;
import com.sheepybot.internal.event.EventRegistryImpl;
import com.sheepybot.internal.module.ModuleLoaderImpl;
import com.sheepybot.listeners.GuildMessageListener;
import com.sheepybot.listeners.JdaGenericListener;
import com.sheepybot.util.BotUtils;
import com.sheepybot.util.Objects;
import com.sheepybot.util.Options;
import net.dv8tion.jda.api.JDAInfo;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.login.LoginException;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;

/**
 * The main class for the bot
 */
public class Bot {

    /**
     * Logger instance
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(Bot.class);

    /**
     * User-Agent header used for web queries
     */
    @SuppressWarnings("ConstantConditions")
    //Intellij thinks everything from BotInfo won't change, but it does at compile time thanks to gradle
    public static final String USER_AGENT = BotInfo.BOT_NAME + (BotInfo.VERSION_MAJOR.startsWith("@") ? "" : " v" + BotInfo.VERSION);

    /**
     * A {@link JsonParser} instance
     */
    public static final JsonParser JSON_PARSER = new JsonParser();

    /**
     * A {@link MediaType}
     */
    public static final MediaType MEDIA_JSON = MediaType.parse("application/json;charset=utf8");

    /**
     * The {@link OkHttpClient} used for executing web requests
     */
    public static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();

        if (request.header("User-Agent") == null) {
            request = request.newBuilder().header("User-Agent", Bot.USER_AGENT).build();
        }

        return chain.proceed(request);
    }).build();

    /**
     * The thread group used for our {@link ExecutorService}s
     */
    private static final ThreadGroup THREAD_GROUP = new ThreadGroup("Executor-Thread-Group");

    /**
     * A convenience {@link BiFunction} to create a new {@link Thread} with the {@code threadName} and {@code threadExecutor}
     */
    private static final BiFunction<String, Runnable, Thread> THREAD_FUNCTION = (threadName, threadExecutor) -> new Thread(THREAD_GROUP, threadExecutor, threadName);

    /**
     * A scheduled {@link ExecutorService}
     */
    public static final ExecutorService SCHEDULED_EXECUTOR_SERVICE = Executors.newScheduledThreadPool(30, threadExecutor -> THREAD_FUNCTION.apply("Cached Thread Service", threadExecutor));

    /**
     * A single threaded {@link ScheduledExecutorService}.
     */
    public static final ScheduledExecutorService SINGLE_EXECUTOR_SERVICE = Executors.newSingleThreadScheduledExecutor(threadExecutor -> THREAD_FUNCTION.apply("Single Thread Service", threadExecutor));

    private static Bot instance;

    static {
        THREAD_GROUP.setMaxPriority(Thread.NORM_PRIORITY);
    }

    private long startTime;
    private boolean running;
    private Toml config;
    private RootCommandRegistry commandRegistry;
    private RootEventRegistry eventRegistry;
    private ModuleLoader moduleLoader;
    private ShardManager shardManager;
    private Database database;

    public static void main(final String[] args) {
        Bot bot = null;
        try {
            (bot = new Bot()).start(args); //start in current directory
        } catch (final Throwable ex) {
            LOGGER.info("An error occurred during startup and the API has to shutdown...", ex);
            if (bot != null) {
                bot.shutdown();
            }
        }
    }

    /**
     * @return The current instance
     */
    public static Bot get() {
        return Bot.instance;
    }

    private void start(final String[] args) throws IOException, LoginException {
        Objects.checkArgument(!this.running, "bot is already running");

        Bot.instance = this;

        this.running = true;
        this.startTime = System.currentTimeMillis();

        final Options options = Options.parse(args);

        final Options.Option buildInfo = options.getOption("buildinfo");
        if (buildInfo != null) {
            LOGGER.info("Detected build info flag, logging build info then exiting...");

            LOGGER.info("----------------- JDA -----------------");
            LOGGER.info(String.format("JDA Version: %s", JDAInfo.VERSION));
            LOGGER.info(String.format("Rest API Version: %d", JDAInfo.DISCORD_REST_VERSION));
            LOGGER.info(String.format("Audio Gateway Version: %d", JDAInfo.AUDIO_GATEWAY_VERSION));
            LOGGER.info("----------------- Bot -----------------");
            LOGGER.info(String.format("API Version: %s", BotInfo.VERSION));
            LOGGER.info(String.format("Commit Long: %s", BotInfo.GIT_COMMIT));
            LOGGER.info(String.format("Branch: %s", BotInfo.GIT_BRANCH));
            LOGGER.info(String.format("Build Date: %s", BotInfo.BUILD_DATE));
//            LOGGER.info(String.format("Build Author: %s", BotInfo.BUILD_AUTHOR));
            LOGGER.info(String.format("Lava Player Version: %s", PlayerLibrary.VERSION));
            LOGGER.info(String.format("JVM Version: %s", System.getProperty("java.version")));
            LOGGER.info("---------------------------------------");

            return;
        }

        //noinspection ResultOfMethodCallIgnored
        ModuleLoaderImpl.MODULE_DIRECTORY.mkdirs();
        I18n.extractLanguageFiles(); //also extracting internal language files so people can change how we respond

        final File file = new File("bot.toml");
        if (!file.exists()) {
            LOGGER.info("Couldn't find required file bot.toml when starting, creating it...");

            FileUtils.copyURLToFile(this.getClass().getResource("/bot.toml"), file); //config wasn't found so copy internal one (resources/bot.toml)

            LOGGER.info(String.format("File bot.toml was created at %s, please configure it then restart the bot.", file.getCanonicalPath()));
        } else {

            LOGGER.info("Loading configuration...");

            this.config = new Toml().read(file);

            if (this.config.getString("jda.token").isEmpty()) {
                LOGGER.info("No discord token specified, please configure it in the bot.toml");
                return;
            }

            I18n.setDefaultI18n(this.config.getString("client.language"));

            if (this.config.getBoolean("db.enabled", false)) {
                LOGGER.info("Database has been enabled in configuration file, loading up connection pool...");
                this.database = new Database(new DatabaseInfo(this.config.getTable("db")));
            }

            LOGGER.info("Loading data managers...");

            this.commandRegistry = new CommandRegistryImpl();
            this.eventRegistry = new EventRegistryImpl();
            this.moduleLoader = new ModuleLoaderImpl();

            final String token = this.config.getString("jda.token");

            int shards = Math.toIntExact(this.config.getLong("jda.shard_total"));
            int recommendedShards = BotUtils.getRecommendedShards(token);

            if (shards != -1 && shards < recommendedShards) {
                LOGGER.info("Cannot use less than discords recommended shard count, using recommended shard count from discord instead.");
                shards = recommendedShards;
            }

            final List<GatewayIntent> gatewayIntents = BotUtils.getGatewayIntentsFromList(this.config.getList("jda.gateway_intents"));
            final List<CacheFlag> enabledCacheFlags = BotUtils.getCacheFlagsFromList(this.config.getList("jda.enabled_cache_flags"));
            final List<CacheFlag> disabledCacheFlags = BotUtils.getCacheFlagsFromList(this.config.getList("jda.disabled_cache_flags"));

            for (final CacheFlag flag : CacheFlag.values()) {
                if (flag.getRequiredIntent() != null && !gatewayIntents.contains(flag.getRequiredIntent()) && enabledCacheFlags.contains(flag)) {
                    LOGGER.info(String.format("Missing required gateway intent %s for cache flag %s, disabling cache flag...", flag.getRequiredIntent().name(), flag.name()));
                    enabledCacheFlags.remove(flag);
                    disabledCacheFlags.add(flag);
                }
            }

            final DefaultShardManagerBuilder builder = DefaultShardManagerBuilder.create(token, gatewayIntents)
                    .enableCache(enabledCacheFlags)
                    .disableCache(disabledCacheFlags)
                    .setChunkingFilter(BotUtils.getChunkingFilterFromString(this.config.getString("jda.chunking_filter")))
                    .setMemberCachePolicy(BotUtils.getMemberCachePolicyFromString(this.config.getString("jda.member_cache_policy")))
                    .setAutoReconnect(this.config.getBoolean("jda.auto_reconnect"))
                    .setEnableShutdownHook(false)
                    .setBulkDeleteSplittingEnabled(this.config.getBoolean("jda.bulk_delete_splitting"))
                    .setHttpClient(HTTP_CLIENT)
                    .setShardsTotal(shards)
                    .addEventListeners(
                            new GuildMessageListener(),
                            new JdaGenericListener()
                    );

            if (shards != -1) {

                int shardMin = Math.toIntExact(this.config.getLong("jda.shard_min", -1L));
                int shardMax = Math.toIntExact(this.config.getLong("jda.shard_max", -1L));

                if (shardMin < 0) shardMin = 0;
                if (shardMax < 0 || shardMax > shards) shardMax = (shards - 1);

                builder.setShards(shardMin, shardMax);

            }

            final String activity = this.config.getString("jda.activity");
            if (activity != null && !activity.isEmpty()) {
                builder.setActivity(Activity.of(Activity.ActivityType.DEFAULT, activity));
            }

            LOGGER.info("Starting shards and attempting to connect to the Discord API...");

            this.shardManager = builder.build();

            LOGGER.info("Loading language files...");

            I18n.loadI18n(this.getClass());

            LOGGER.info("Loading modules...");

            //If there were no modules to load it just returns an empty list, so no harm done
            final Collection<Module> modules = this.moduleLoader.loadModules();
            modules.forEach(this.moduleLoader::enableModule);

            LOGGER.info(String.format("Loaded %d modules", this.moduleLoader.getEnabledModules().size()));

            //register our shutdown hook so if something happens we get properly shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "API-Auto-Shutdown-Thread"));

            LOGGER.info(String.format("Startup completed! Took %dms, running api version: %s.", (System.currentTimeMillis() - this.startTime), BotInfo.VERSION));
        }
    }

    private void shutdown() {
        Objects.checkArgument(this.running, "bot not running");

        this.running = false;

        if (this.moduleLoader != null) {
            this.moduleLoader.disableModules();
        }

        if (this.shardManager != null) {
            this.shardManager.shutdown();
        }

        if (this.eventRegistry != null) {
            this.eventRegistry.unregisterAll();
        }

        if (this.commandRegistry != null) {
            this.commandRegistry.unregisterAll();
        }

        Scheduler.getInstance().shutdown();

        Bot.SCHEDULED_EXECUTOR_SERVICE.shutdownNow();
        Bot.SINGLE_EXECUTOR_SERVICE.shutdownNow();

        Bot.instance = null;
    }

    /**
     * @return The bot config
     */
    public Toml getConfig() {
        return this.config;
    }

    /**
     * @return The time of startup
     */
    public long getStartTime() {
        return this.startTime;
    }

    /**
     * @return The {@link Database} instance
     */
    public Database getDatabase() {
        return this.database;
    }

    /**
     * @return The {@link RootCommandRegistry} impl
     */
    public RootCommandRegistry getCommandRegistry() {
        return this.commandRegistry;
    }

    /**
     * @return The {@link RootEventRegistry} impl
     */
    public RootEventRegistry getEventRegistry() {
        return this.eventRegistry;
    }

    /**
     * @return The {@link ModuleLoader} impl
     */
    public ModuleLoader getModuleLoader() {
        return this.moduleLoader;
    }

    /**
     * @return The {@link ShardManager}
     */
    public ShardManager getShardManager() {
        return this.shardManager;
    }

}
