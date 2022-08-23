import com.graphql.generated.GetNewTrade
import com.expediagroup.graphql.client.spring.GraphQLWebClient
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import com.graphql.generated.getnewtrade.PublicSyndication
import discord4j.core.DiscordClient
import discord4j.core.event.domain.message.MessageCreateEvent
import discord4j.core.`object`.entity.Message
import discord4j.core.`object`.entity.channel.MessageChannel
import discord4j.core.spec.EmbedCreateSpec
import discord4j.rest.util.Color
import kotlinx.coroutines.runBlocking
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*
import java.util.*
import kotlin.collections.HashMap
import kotlin.concurrent.timerTask
//BREAK STATEMENT FOR STOPPING PROGRAM
var stopExecution = false
//DATA STORAGE: HASHMAP
val sentMessages = HashMap<String, Alert>()
val timer = Timer()
//INSERT ALL SYNDICATION ID's HERE
val syndicationIDs = listOf("530140303")

//TODO: FOR LATER USE WHEN @ ROLES IN DISCORD FOR ALERTS
//var mentions = hashMapOf<String,String>("530140303" to "@Location", "497719629" to "@LUNERA")

val endpoint = GraphQLWebClient(url = "https://api.tuned.com/graphql")
//ALERT LAYOUT
data class Alert(
    val isClosed: Boolean,
    val botName: String,
    val synID: String,
    val placeTime: String,
    val currPair: String,
    val fillPrice: String,
    val profitPercent: String,
    val positionType: String,
    val exchange: String,
    val nodeID: String,
    val nickname: String,
){
    companion object {
        @Throws(IllegalArgumentException::class)
        fun fromPublicSyndication(publicSyndication: PublicSyndication): Alert {
            val botName = publicSyndication.name.toString()
            val isClosed = publicSyndication.closedTrades?.edges?.get(0)?.node?.isClosed?: false
            val synID = publicSyndication.id
            val placedTime: Instant? = publicSyndication.closedTrades?.edges?.get(0)?.node?.orders?.get(0)?.placedTime?.let { Instant.parse(it)}
            val currPair = publicSyndication.closedTrades?.edges?.get(0)?.node?.orders?.get(0)?.currencyPairDetails?.pair?.toString()
            val fillPrice = String.format("%.2f",publicSyndication.closedTrades?.edges?.get(0)?.node?.orders?.get(0)?.filledPrice.toString().toDouble())
            var profitPercent = publicSyndication.closedTrades?.edges?.get(0)?.node?.profitPercentage.toString()
            val exchange = publicSyndication.exchange.toString()
            val nodeID = publicSyndication.closedTrades?.edges?.get(0)?.node?.id.toString()
            val nickname = publicSyndication.owner.nickname.toString()
            val positionType: String
            if (isClosed) {
                positionType = "CLOSE"
                //Turn into % and round
                val floatProfitPercent : Float = (BigDecimal((profitPercent.toFloat() * 100).toString()).setScale(3, RoundingMode.HALF_EVEN)).toFloat()
                profitPercent = floatProfitPercent.toString().trim() + "%"
            } else {
                //No realized profit on open positions
                positionType = publicSyndication.closedTrades?.edges?.get(0)?.node?.orders?.get(0)?.side.toString()
            }
            val placeTime = OffsetDateTime.ofInstant(placedTime, ZoneId.systemDefault())
            val hour = if (placeTime.hour < 10)  "0" + placeTime.hour.toString() else placeTime.hour.toString()
            val minute = if (placeTime.minute < 10) "0" + placeTime.minute.toString() else placeTime.minute.toString()
            val second = if (placeTime.second < 10) "0" + placeTime.second.toString() else placeTime.second.toString()
            val placeTimeString = placeTime.monthValue.toString() + "/" + placeTime.dayOfMonth.toString() + "/" + placeTime.year.toString() + " " + hour + ":" + minute + ":" + second
            checkNotNull(currPair) {"currPair cannot be null"}
            checkNotNull(placedTime) {"placedTime cannot be null"}
            //Set alert specs
            return Alert(
                isClosed = isClosed,
                botName = botName,
                synID = synID,
                currPair = currPair.toString(),
                placeTime = placeTimeString,
                fillPrice = fillPrice,
                profitPercent = profitPercent,
                positionType = positionType,
                exchange = exchange,
                nodeID = nodeID,
                nickname = nickname
            )
        }
    }
}

fun main() {
    //Connect to discord service
    val file = File(".discord-token")
    val key = file.useLines { it.firstOrNull() }
    checkNotNull(key) { "Discord client key cannot be null" }
    val client = DiscordClient.create(key)
    val gateway = client.login().block()
    gateway?.on(MessageCreateEvent::class.java)?.subscribe { event: MessageCreateEvent ->
        val message: Message = event.message
        if ("!stop" == message.content) {
            //Stop Execution & Rebuild
            val channel: MessageChannel = message.channel.block() as MessageChannel
            stopExecution = true
            timer.cancel()
            channel.createMessage("I am off! Please create a new build to run again").block()
        }
        if ("!run" == message.content) {
            stopExecution = false
            val channel: MessageChannel = message.channel.block() as MessageChannel
            channel.createMessage("I am on!").block()

            timer.scheduleAtFixedRate(timerTask {
                if(!stopExecution){
                    checkTrades(channel)
                }
            },0,1*30*1000)
            //Execute every 30 seconds, can be modified
        }
    }
    gateway?.onDisconnect()?.block()
}

fun checkTrades(channel : MessageChannel){
    for (syndID in syndicationIDs){
        var result: GraphQLClientResponse<GetNewTrade.Result>
        runBlocking {
            //Make the query and store result
            val query = GetNewTrade(variables = GetNewTrade.Variables(id = syndID))
            result = endpoint.execute(query)
        }
        //PARSE DATA FROM QUERY
        val publicSyndication = result.data?.publicSyndication
        checkNotNull(publicSyndication) {"Public Syndication Cannot be Null!"}
        val alert = try { Alert.fromPublicSyndication(publicSyndication) } catch (e: IllegalArgumentException) { null }
        //Ensure no invalid data parsed
        if(alert != null){
            //check if the key is in there, if not, FIRST QUERY
            if(sentMessages.containsKey(syndID) && sentMessages[syndID]!!.nodeID == alert.nodeID && alert.isClosed == sentMessages[syndID]!!.isClosed ) {
                println("Already Sent For SynID: ${alert.synID}: " + Instant.now().toString() + ", NODEID: ALERT: ${alert.nodeID} ${sentMessages[syndID]!!.nodeID}  and CLOSED: ${alert.isClosed} ${sentMessages[syndID]!!.isClosed}")
            } else {
                println("NEW QUERY FOUND")
                val embed: EmbedCreateSpec = EmbedCreateSpec.builder()
                    .color(Color.of(37,167,232))
                    .title("\uD83D\uDEA8 NEW TRADE ALERT! \uD83D\uDEA8")
                    .url("https://app.tuned.com/bots/community/${syndID}")
                    .description("A new trade has been detected by " + alert.botName + "!")
                    .addField("\u200B", "\u200B", false)
                    .addField("Date Placed", alert.placeTime, false)
                    .addField("Exchange Name", alert.exchange, false)
                    .addField("Position Type", alert.positionType, false)
                    .addField("\u200B", "\u200B", false)
                    .addField("Currency Pair", alert.currPair, true)
                    .addField("Filled Price", alert.fillPrice, true)
                    .addField("Profit %", alert.profitPercent, true)
                    .addField("\u200B", "\u200B", false)
                    .addField("Subscribe to Trade with Tuned", "https://app.tuned.com/t/${alert.nickname}/${syndID}", true)
                    .image("https://static.ffbbbdc6d3c353211fe2ba39c9f744cd.com/wp-content-learn/uploads/2021/11/19125448/Tuned-Trading-Platform-1024x386.jpg")
                    .timestamp(Instant.now())
                    .build()
                channel.createMessage(embed).block()
                sentMessages[alert.synID] = alert
                }
            }
        }
    }

