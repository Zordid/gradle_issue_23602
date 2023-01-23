package utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.producer.Callback
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import javax.jms.BytesMessage
import javax.jms.Destination
import javax.jms.JMSContext
import javax.jms.JMSProducer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend inline fun <reified K : Any?, reified V : Any> Producer<K, V>.dispatch(record: ProducerRecord<K?, V?>): RecordMetadata =
    withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val callback = Callback { metadata: RecordMetadata?, exception: Exception? ->
                when {
                    exception != null -> continuation.resumeWithException(exception)
                    metadata == null -> continuation.resumeWithException(
                        IllegalStateException("Metadata returned is null, but no exception from Kafka")
                    )

                    else -> continuation.resume(metadata)
                }
            }
            val future = send(record, callback)
            continuation.invokeOnCancellation { future.cancel(true) }
        }
    }

suspend inline fun JMSProducer.dispatch(destination: Destination, body: String?): Int =
    runInterruptible(Dispatchers.IO) {
        send(destination, body)
        body?.length ?: 0
    }

suspend inline fun JMSProducer.dispatch(destination: Destination, body: ByteArray?): Int =
    runInterruptible(Dispatchers.IO) {
        send(destination, body)
        body?.size ?: 0
    }

suspend inline fun JMSProducer.dispatch(destination: Destination, body: BytesMessage): Int =
    runInterruptible(Dispatchers.IO) {
        send(destination, body)
        body.bodyLength.toInt()
    }

fun JMSContext.createBodyLessBytesMessage(): BytesMessage =
    createBytesMessage()
