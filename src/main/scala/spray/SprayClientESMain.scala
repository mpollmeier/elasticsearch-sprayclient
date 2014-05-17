package spray

import java.text.SimpleDateFormat
import java.util.Calendar
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util._

import akka.actor._
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import spray.can.Http
import spray.client.pipelining._
import spray.http._

object SprayESClient extends App {
  import MemberCreator._
  val longTimeout = 30 minutes
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



  setupIndex()
  println("index setup complete")

  val memberCount = 10 * 1000 * 1000
  val memberCreator = system.actorOf(Props(classOf[MemberCreator], pipeline))

  (0 until memberCount) foreach { id ⇒ 
    Await.ready(memberCreator ? CreateMember(id), longTimeout)
  }

  println("setting up members complete")
  system.shutdown()
  println("shutdown complete")
}

object MemberCreator {
  case class CreateMember(id: Int)
  case object MemberCreated
}
class MemberCreator(pipeline: Future[SendReceive]) extends Actor {
  import MemberCreator._
  val longTimeout = 30 minutes
  val averageTxCountPerMember = 10
  val rand = new java.util.Random
  implicit val timeout = Timeout(longTimeout)
  import context.dispatcher

  def receive = {
    case CreateMember(id) ⇒ 
      val respondTo = sender
      createMember(id) onComplete {
        case _ ⇒ respondTo ! MemberCreated
      }
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
}

