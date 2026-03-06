package simulations
import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
class OutboxPerformanceTest extends Simulation {
val httpProtocol = http
.baseUrl("http://localhost:8080")
.acceptHeader("application/json")
.contentTypeHeader("application/json")
// Scenario 1: Create Transactions
val createTransactionScenario = scenario("Create Transactions")
.exec(
http("Create Transaction")
.post("/api/transactions")
.body(StringBody(
"""{
| "transactionReference": "TXN-${randomUUID()}",
| "customerId": "CUST-${randomInt(1000)}",
| "amount": ${randomDouble(100, 10000)},
| "currency": "USD",
| "senderAccount": "ACC-${randomInt(1000)}",
| "receiverAccount": "ACC-${randomInt(1000)}",
| "description": "Performance test transaction"
|}""".stripMargin))
.check(status.is(201))
.check(jsonPath("$.success").is("true"))
)
.pause(1)
// Scenario 2: Get Transaction
val getTransactionScenario = scenario("Get Transactions")
.exec(
http("Get All Transactions")
.get("/api/transactions")
.check(status.is(200))
.check(jsonPath("$.success").is("true"))
)
.pause(1)
// Scenario 3: Check Outbox Stats
val checkOutboxStatsScenario = scenario("Check Outbox Stats")
.exec(
http("Get Outbox Stats")
.get("/api/outbox/stats")
.check(status.is(200))
.check(jsonPath("$.success").is("true"))
)
.pause(2)
// Load Test Configuration
setUp(
createTransactionScenario.inject(
rampUsers(100) during (30 seconds),
constantUsersPerSec(50) during (60 seconds)
),
getTransactionScenario.inject(
rampUsers(50) during (30 seconds),
constantUsersPerSec(20) during (60 seconds)
),
checkOutboxStatsScenario.inject(
rampUsers(20) during (30 seconds),
constantUsersPerSec(10) during (60 seconds)
)
).protocols(httpProtocol)
.assertions(
global.responseTime.max.lt(5000),
global.responseTime.mean.lt(1000),
global.successfulRequests.percent.gt(95)
)
}
