import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.testkit.TestKit
import akka.util.ByteString
import com.mtprototype.main.{MtServer, PrimeUtils, TLUtils}
import org.scalatest._
import scodec.bits._

import scala.collection.concurrent
import scala.concurrent.duration._

class MtServerSpec(_system: ActorSystem) extends TestKit(_system)
  with WordSpecLike with Matchers with BeforeAndAfterAll with PrivateMethodTester {

  def this() = this(ActorSystem("MtPrototypeSpec"))

  implicit private val materializer = ActorMaterializer()

  override def afterAll: Unit = {
    shutdown(system)
  }

  "A MtServer" should {
    "response with (nonce, server_nonce, pq, fingerprint) on (p,q)-exchange request" in {
      val severLogic_R = PrivateMethod[Flow[ByteString, ByteString, _]]('severLogic)
      val clients_R = PrivateMethod[concurrent.Map[ByteVector, MtServer.ClientInfo]]('clients)

      val server = MtServer.start("127.0.0.1", 14880)
      val serverLogic = server invokePrivate severLogic_R()
      val clients = server invokePrivate clients_R()

      val (pub, sub) = TestSource.probe[ByteString]
        .via(serverLogic)
        .toMat(TestSink.probe[ByteString])(Keep.both)
        .run()

      val testingRequest = ByteString((hex"00 00 00 00 00 00 00 00 4A 96 70 27 C4 7A E5 51 14" ++
        hex" 00 00 00 78 97 46 60 3E 05 49 82 8C CA 27 E9 66 B3 01 A4 8F EC E2 FC").toByteBuffer)
      val expectedResponse = ByteString((hex"00 00 00 00 00 00 00 00 01 C8 83 1E C9 7A E5 51" ++
        hex"40 00 00 00 63 24 16 05 3E 05 49 82 8C CA 27 E9 66 B3 01 A4 8F EC E2 FC" ++
        hex"A5 CF 4D 33 F4 A1 1E A8 77 BA 4A A5 73 90 73 30 08 17 ED 48 94 1A 08 F9" ++
        hex"81 00 00 00 15 C4 B5 1C 01 00 00 00 21 6B E8 6C 02 2B B4 C3").toByteBuffer)

      sub.request(n = 1)
      pub.sendNext(testingRequest)
      val result = ByteVector(sub.expectNext(10 minutes))

      result.take(8) shouldBe MtServer.UNENCRYPTED_AUTH_KEY_ID
      val expectedLen = result.slice(16, 20).toInt(signed = true, ordering = ByteOrdering.LittleEndian)
      (result.size - 20) shouldBe expectedLen
      result.slice(20, 24).reverse shouldBe TLUtils.RES_PQ_CONSTRUCTOR

      val clientNonce = hex"3E 05 49 82 8C CA 27 E9 66 B3 01 A4 8F EC E2 FC"
      val client = clients(hex"3E 05 49 82 8C CA 27 E9 66 B3 01 A4 8F EC E2 FC")
      result.slice(24, 40) shouldBe clientNonce
      result.slice(40, 56) shouldBe client.serverNonce

      val opt = TLUtils.deserializeString(result.drop(56))
      val (pq, rest) = opt match {
        case Some((str, more)) =>
          ByteVector.fromHex(str) match { case Some(bv) => (bv.toLong(), more); case None => fail("String 'pq' can't be deserialized") }
        case None => fail("String 'pq' can't be deserialized")
      }

      val factors = PrimeUtils.factorize(pq)
      factors.size shouldBe 2
      val (p, q) = (factors.min, factors.max)
      p shouldBe client.p
      q shouldBe client.q

      rest.take(4).reverse shouldBe TLUtils.VECTOR_LONG_CONSTRUCTOR
      rest.slice(4, 8) shouldBe ByteVector.fromInt(1, ordering = ByteOrdering.LittleEndian)
      rest.drop(8).reverse shouldBe MtServer.little64bits
    }
  }
}
