package kafka

import io.kotest.matchers.ints.shouldBeExactly
import org.junit.jupiter.api.ClassOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestClassOrder

@TestClassOrder(OrderAnnotation::class)
@Order(2)
class KafkaIT {

    @Test
    fun `dummy test`() {
        // just a random dumb test
        1 shouldBeExactly 1
    }

}