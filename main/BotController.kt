package fredboat.main

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import fredboat.agent.FredBoatAgent
import fredboat.audio.player.AudioConnectionFacade
import fredboat.audio.player.PlayerRegistry
import fredboat.config.property.*
import fredboat.db.api.GuildConfigService
import fredboat.db.api.GuildModulesService
import fredboat.db.api.GuildPermsService
import fredboat.db.api.PrefixService
import fredboat.event.EventListenerBoat
import fredboat.feature.metrics.BotMetrics
import fredboat.feature.metrics.Metrics
import fredboat.jda.JdaEntityProvider
import fredboat.metrics.OkHttpEventMetrics
import fredboat.util.ratelimit.Ratelimiter
import fredboat.util.rest.Http
import net.dv8tion.jda.bot.sharding.ShardManager
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService

/**
 * Class responsible for controlling FredBoat at large
 */
@Component
class BotController(private val configProvider: ConfigPropertiesProvider,
                    val audioConnectionFacade: AudioConnectionFacade,
                    val shardManager: ShardManager,
                    //central event listener that all events by all shards pass through
                    val mainEventListener: EventListenerBoat,
                    val shutdownHandler: ShutdownHandler,
                    val executor: ExecutorService,
                    val playerRegistry: PlayerRegistry,
                    val jdaEntityProvider: JdaEntityProvider,
                    val botMetrics: BotMetrics,
                    @param:Qualifier("loadAudioPlayerManager") val audioPlayerManager: AudioPlayerManager,
                    val ratelimiter: Ratelimiter,
                    val guildConfigService: GuildConfigService,
                    val guildModulesService: GuildModulesService,
                    val guildPermsService: GuildPermsService,
                    val prefixService: PrefixService) {

    companion object {
        @JvmStatic
        val HTTP = Http(Http.DEFAULT_BUILDER.newBuilder()
                .eventListener(OkHttpEventMetrics("default", Metrics.httpEventCounter))
                .build())
    }

    val appConfig: AppConfig
        get() = configProvider.appConfig

    val audioSourcesConfig: AudioSourcesConfig
        get() = configProvider.audioSourcesConfig

    val backendConfig: BackendConfig
        get() = configProvider.backendConfig


    val credentials: Credentials
        get() = configProvider.credentials

    init {
        Runtime.getRuntime().addShutdownHook(
                Thread(Runnable { FredBoatAgent.shutdown() }, "FredBoat main shutdownhook")
        )
    }
}
