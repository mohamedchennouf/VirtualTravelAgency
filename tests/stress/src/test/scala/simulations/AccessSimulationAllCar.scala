package simulations

import java.util.UUID

import scala.language.postfixOps

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._


class AccessSimulationAllCar extends Simulation {

  val httpConf =
    http
      .baseURL("http://localhost:9090/cars-hotels-reservation-service-rest/cars")
      .acceptHeader("application/json")
      .header("Content-Type", "application/json")

  val stressSample =
    scenario("Request All Cars Reservations")
        .repeat(10)
        {
          exec(session =>
            session.set("ssn", UUID.randomUUID().toString)
          )
            .exec(
              http("localhost:9090/cars-hotels-reservation-service-rest/cars")
                .get("")
                .check(status.is(200))
            )
        }

  setUp(stressSample.inject(rampUsers(20) over (10 seconds)).protocols(httpConf))
}