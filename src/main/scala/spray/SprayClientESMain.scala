package spray

import java.text.SimpleDateFormat
import java.util.Calendar
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util._

import akka.actor.ActorSystem
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import spray.can.Http
import spray.client.pipelining._
import spray.http._


object SprayESClient extends App {
  val longTimeout = 30 minutes
  val rand = new java.util.Random
  implicit val system = ActorSystem()
  implicit val timeout = Timeout(longTimeout)
  import system.dispatcher

  def await(f: Future[_]) = Await.result(f, longTimeout)

  val pipeline: Future[SendReceive] =
    for (
      Http.HostConnectorInfo(connector, _) <-
        IO(Http) ? Http.HostConnectorSetup("localhost", port = 9200)
    ) yield sendReceive(connector)

  def setupIndex() = {
    await(pipeline flatMap (_(Delete(s"/members"))))
    await(pipeline flatMap (_(Put(s"/members"))))
    await(pipeline flatMap (_(Post(s"/members/member/_mapping", """
      {
        "member":{
            "name" : {"type": "string", "index": "not_analyzed"},
            "age" : {"type": "integer"},
            "properties":{
              "transactions": {
                "type": "nested",
                "properties": {
                  "genre": {"type": "string"},
                  "date": {"type": "date"}
                }
              }
            }
          }
      }"""))))
  }

  def createMember(id: Int): Future[HttpResponse] = {
    val age = 12 + rand.nextInt(50)
    val transactionCount = rand.nextInt(averageTxCountPerMember * 2)
    val transactions = (0 until transactionCount) map createTransaction
    val request = Put(s"/members/member/$id", s"""
      { 
        "name": "Member $id", 
        "age": $age,
        "transactions": [ ${transactions.mkString(",")} ]
      } 
    """)
    pipeline flatMap (_(request))
  }

  val dateFormat = new SimpleDateFormat("yyyy-MM-dd")
  def createTransaction(id: Int) = {
    val genres = List("action", "horror", "fiction")
    def randomGenre(): String = genres(rand.nextInt(genres.size))
    def randomDate(): String = {
      val cal = Calendar.getInstance()
      cal.set(Calendar.YEAR, 2014)
      cal.set(Calendar.DAY_OF_MONTH, 1 + rand.nextInt(28))
      cal.set(Calendar.MONTH, 1 + rand.nextInt(12))
      dateFormat.format(cal.getTime)
    }

    val genre = randomGenre()
    val date = randomDate()
    s"""{ "id": $id, "genre": "$genre", "date": "$date"}"""
  }

  def verifyResponseStatus(response: HttpResponse) = 
    response.status match {
      case StatusCodes.OK | StatusCodes.Created ⇒ //all good
      case _ ⇒ println("problem!", response)
    }

  val memberCount = 10 * 1000 * 1000
  val averageTxCountPerMember = 10

  setupIndex()
  println("index setup complete")

  val memberCreations = (0 until memberCount) map createMember
  Await.result(Future.sequence(memberCreations), longTimeout) foreach verifyResponseStatus
  println("setting up members complete")

  system.shutdown()
}

