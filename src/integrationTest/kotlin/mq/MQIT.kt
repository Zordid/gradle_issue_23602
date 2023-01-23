package mq

import com.ibm.msg.client.jms.JmsConnectionFactory
import com.ibm.msg.client.jms.JmsFactoryFactory
import com.ibm.msg.client.wmq.WMQConstants
import eu.rekawek.toxiproxy.model.ToxicDirection
import io.kotest.matchers.ints.shouldBeExactly
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.junit.jupiter.api.*
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.ToxiproxyContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import utils.*
import javax.jms.JMSContext
import javax.jms.JMSProducer
import javax.jms.Queue
import kotlin.time.Duration

class KGenericContainer(imageName: String) : GenericContainer<KGenericContainer>(imageName)

private val logger = KotlinLogging.logger { }

@Testcontainers
@Timeout(60)
@TestClassOrder(ClassOrderer.OrderAnnotation::class)
@TestMethodOrder(OrderAnnotation::class)
@Order(1)
class MQIT {

    @BeforeEach
    fun setup() {
        clearQueue()
    }

    @Test
    @Order(1)
    fun `MQ source can receive a null Text message`() = runBlocking {
        val messages = listOf("One", null, "Three")
        prepareTextMessages(messages)
        delay(1000)
    }

    @Test
    @Order(2)
    fun `MQ source can receive a null Bytes message`() = runBlocking {
        val messages = listOf("One", null, "Three").map { it?.toByteArray() }
        prepareBytesMessages(messages)
        delay(1000)
    }

    @Test
    @Order(3)
    fun `a final test here`() {
        42 shouldBeExactly 42
    }

    companion object {
        private const val MQ_PORT = 1414
        private const val MQ_PASSWORD = "passwd"

        const val DEV_QUEUE = "DEV.QUEUE.1"

        private val network = Network.newNetwork()

        @Container
        private val mqContainer = createMqContainer().withNetwork(network)

        @Container
        private val proxyContainer = ToxiproxyContainer("shopify/toxiproxy:2.1.0")
            .withNetwork(network)
            .withNetworkAliases("toxiProxyAlias")

        private fun createMqContainer(): KGenericContainer = KGenericContainer("ibmcom/mq:9.1.5.0-r2")
            .withEnv(
                mapOf(
                    "LICENSE" to "accept",
                    "MQ_QMGR_NAME" to "QM1",
                    "MQ_APP_PASSWORD" to MQ_PASSWORD
                )
            )
            .withExposedPorts(MQ_PORT)

        private fun mqServerConfig(
            proxy: ToxiproxyContainer.ContainerProxy =
                proxyContainer.getProxy(mqContainer, MQ_PORT),
        ): MQServerConfig = MQServerConfig(
            connectionList = "${proxy.containerIpAddress}(${proxy.proxyPort})",
            queueManager = "QM1",
            channel = "DEV.APP.SVRCONN",
            appUser = "app",
            appPassword = MQ_PASSWORD,
            sslCipherSuite = "",
            sslCipherSpec = null,
            applicationName = "Agathe",
            additionalReconnectErrorCodes = emptyList(),
            customSettings = null,

            )

        private fun clearQueue(queueName: String = DEV_QUEUE) {
            logger.info { "Cleaning queue $queueName..." }
            assertDoesNotThrow {
                val mqServerConfig = mqServerConfig()

                val connectionFactory = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER)
                    .createConnectionFactory()
                    .apply { fillFromConfig(mqServerConfig) }

                val context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE)
                context.let {
                    val consumer = it.createConsumer(it.createQueue("queue:///$queueName"))
                    var count = 0
                    while (consumer.receiveNoWait() != null) {
                        count++
                    }
                    logger.info { "Swallowed $count messages, queue $queueName is empty now" }
                }
            }
        }

        private suspend fun prepareTextMessages(messages: List<String?>, queueName: String = DEV_QUEUE) =
            prepareMessages(messages, queueName) { _, producer, queue, text ->
                producer.dispatch(queue, text)
            }

        private suspend fun prepareBytesMessages(messages: List<ByteArray?>, queueName: String = DEV_QUEUE) =
            prepareMessages(messages, queueName) { context, producer, queue, bytes ->
                if (bytes == null)
                    producer.dispatch(queue, context.createBodyLessBytesMessage())
                else
                    producer.dispatch(queue, bytes)
            }

        private suspend fun <T> prepareMessages(
            messages: List<T?>,
            queueName: String,
            dispatch: suspend (JMSContext, JMSProducer, Queue, T?) -> Unit,
        ) {
            val mqServerConfig = mqServerConfig()

            val connectionFactory = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER)
                .createConnectionFactory()
                .apply { fillFromConfig(mqServerConfig) }
            connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE).use { context ->
                val producer = context.createProducer()
                val queue = context.createQueue("queue:///$queueName")
                messages.forEach { dispatch(context, producer, queue, it) }
                logger.info { "Sent ${messages.size} messages to $queueName" }
            }
        }

        fun JmsConnectionFactory.fillFromConfig(config: MQServerConfig) {
            with(config) {
                setStringProperty(WMQConstants.WMQ_CONNECTION_NAME_LIST, connectionList)
                setStringProperty(WMQConstants.WMQ_CHANNEL, channel)
                setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, queueManager)
                appUser?.let { setStringProperty(WMQConstants.USERID, it) }
                appPassword?.let { setStringProperty(WMQConstants.PASSWORD, it) }
                sslCipherSuite?.let { setStringProperty(WMQConstants.WMQ_SSL_CIPHER_SUITE, it) }
                sslCipherSpec?.let { setStringProperty(WMQConstants.WMQ_SSL_CIPHER_SPEC, it) }
                setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, applicationName)
                setBatchProperties(defaultMQProperties)
                customSettings?.let { setBatchProperties(it) }
            }
        }

        private suspend fun ToxiproxyContainer.ContainerProxy.breakNetwork(duration: Duration) {
            logger.info("Breaking network now for $duration")
            val timeouts = runInterruptible(Dispatchers.IO) {
                listOf(
                    toxics().timeout("UP", ToxicDirection.UPSTREAM, 5000),
                    toxics().timeout("DOWN", ToxicDirection.DOWNSTREAM, 5000)
                )
            }
            delay(duration.inWholeMilliseconds)
            logger.info("Enabling network again")
            runInterruptible {
                timeouts.forEach { it.remove() }
            }
        }

        val defaultMQProperties = mapOf(
            WMQConstants.WMQ_CONNECTION_MODE to WMQConstants.WMQ_CM_CLIENT,
            WMQConstants.USER_AUTHENTICATION_MQCSP to true,
            WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS to WMQConstants.WMQ_CLIENT_RECONNECT,
            WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT to 10,
        )

    }

}

data class MQServerConfig(
    val connectionList: String,
    val queueManager: String,
    val channel: String,
    val appUser: String?,
    val appPassword: String?,
    val sslCipherSuite: String?,
    val sslCipherSpec: String?,
    val applicationName: String,
    val additionalReconnectErrorCodes: List<Int>,
    val customSettings: Map<String, Any?>?,
)