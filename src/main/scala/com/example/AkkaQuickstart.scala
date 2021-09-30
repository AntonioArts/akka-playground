/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.Done
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes
// for JSON serialization/deserialization following dependency is required:
// "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.7"
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

import scala.io.StdIn

import scala.concurrent.Future

object SprayJsonExample {

  // needed to run the route
  implicit val system = ActorSystem(Behaviors.empty, "SprayExample")
  // needed for the future map/flatmap in the end and future in fetchItem and saveOrder
  implicit val executionContext = system.executionContext

  var orders: List[Item] = Nil

  // domain model
  final case class Item(name: String, id: Long)
  final case class Order(items: List[Item])

  // formats for unmarshalling and marshalling
  implicit val itemFormat = jsonFormat2(Item)
  implicit val orderFormat = jsonFormat1(Order)

  // (fake) async database query api
  def fetchItem(itemId: Long): Future[Option[Item]] = Future {
    orders.find(o => o.id == itemId)
  }
  def fetchItems(): Future[List[Item]] = Future {
    orders
  }
  def saveOrder(order: Order): Future[Done] = {
    orders = order match {
      case Order(items) => items ::: orders
      case _            => orders
    }
    Future { Done }
  }
  def updateOrder(item: Item): Future[Done] = {
    orders = orders.map(o => if (o.id == item.id) item else o)
    Future { Done }
  }
  def removeItem(itemId: Long): Future[Done] = {
    orders = orders.filter(o => o.id != itemId)
    Future { Done }
  }

  def main(args: Array[String]): Unit = {
    val route: Route =
      concat(
        get {
          pathPrefix("item" / LongNumber) { id =>
            // there might be no item for a given id
            val maybeItem: Future[Option[Item]] = fetchItem(id)

            onSuccess(maybeItem) {
              case Some(item) => complete(item)
              case None       => complete(StatusCodes.NotFound)
            }
          }
        },
        get {
          path("items") {
            val maybeItems: Future[List[Item]] = fetchItems()

            onSuccess(maybeItems) { _ =>
              complete(maybeItems)
            }
          }
        },
        post {
          path("items") {
            entity(as[Order]) { order =>
              val saved: Future[Done] = saveOrder(order)
              onSuccess(saved) { _ => // we are not interested in the result value `Done` but only in the fact that it was successful
                complete(StatusCodes.Created -> "order created")
              }
            }
          }
        },
        put {
          path("item" / LongNumber) { id =>
            entity(as[Item]) { item =>
              val updated: Future[Done] = updateOrder(item)
              onSuccess(updated) { _ => // we are not interested in the result value `Done` but only in the fact that it was successful
                complete("order updated")
              }
            }
          }
        },
        delete {
          pathPrefix("item" / LongNumber) { id =>
            // there might be no item for a given id
            val maybeRemoved: Future[Done] = removeItem(id)
            onSuccess(maybeRemoved) { _ =>
              complete(StatusCodes.Gone -> "Removed")
            }
          } 
        }
      )

    val bindingFuture = Http().newServerAt("localhost", 8070).bind(route)
    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }
}