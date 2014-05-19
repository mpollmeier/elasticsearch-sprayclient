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
import akka.routing.RoundRobinRouter
import akka.util.Timeout
import spray.can.Http
import spray.client.pipelining._
import spray.http._

object ElasticSearchTryout extends App {
  import MemberCreator._
  val longTimeout = 300 minutes
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
              "books": {
                "type": "nested",
                "properties": {
                  "author": {"type": "string"},
                  "borrowedOn": {"type": "date"}
                }
              }
            }
          }
      }"""))))
  }

  setupIndex()
  println("index setup complete")

  val memberCount = 1 * 1000  * 1000
  val memberCreator = system.actorOf(Props(classOf[MemberCreator], pipeline)
    .withRouter(RoundRobinRouter(nrOfInstances = 5)))

  val futures = (0 until memberCount) map { id ⇒ 
    memberCreator ? CreateMember(id)
  }
  Await.ready(Future.sequence(futures), longTimeout)

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
      if(id % 1000 == 0) println(s"creating member $id ($self)")
      val respondTo = sender
      Await.ready(createMember(id), longTimeout)
      respondTo ! MemberCreated
  }

  def createMember(id: Int): Future[HttpResponse] = {
    val age = 12 + rand.nextInt(50)
    val bookCount = rand.nextInt(averageTxCountPerMember * 2)
    val books = (0 until bookCount) map createBook
    val request = Put(s"/members/member/$id", s"""
      { 
        "name": "Member $id", 
        "age": $age,
        "books": [ ${books.mkString(",")} ]
      } 
    """)
    pipeline flatMap (_(request))
  }

  val dateFormat = new SimpleDateFormat("yyyy-MM-dd")
  def createBook(id: Int) = {
    val authors = List("ranicki", "klein", "lessing")
    def randomAuthor(): String = authors(rand.nextInt(authors.size))
    def randomDate(): String = {
      val cal = Calendar.getInstance()
      cal.set(Calendar.YEAR, 2014)
      cal.set(Calendar.DAY_OF_MONTH, 1 + rand.nextInt(28))
      cal.set(Calendar.MONTH, 1 + rand.nextInt(12))
      dateFormat.format(cal.getTime)
    }

    val author = randomAuthor()
    val date = randomDate()
    s"""{ "id": $id, "author": "$author", "borrowedOn": "$date"}"""
  }

  def verifyResponseStatus(response: HttpResponse) = 
    response.status match {
      case StatusCodes.OK | StatusCodes.Created ⇒ //all good
      case _ ⇒ println("problem!", response)
    }
}

