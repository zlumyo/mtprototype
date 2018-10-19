package com.mtprototype.main

import scodec.bits._
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Framing, Tcp}
import akka.stream.scaladsl.Framing.FramingException
import akka.stream.scaladsl.Tcp.ServerBinding
import akka.util.ByteString

import scala.concurrent.Future
import scala.collection.JavaConverters.mapAsScalaConcurrentMapConverter
import scala.collection.concurrent
import scala.util.{Failure, Random, Success}
import java.util.concurrent.ConcurrentHashMap

class MtServer private (val host: String, val port: Int) {

  import MtServer._
  import TLUtils._

  implicit private val system = ActorSystem("MtPrototype")
  implicit private val materializer = ActorMaterializer()
  implicit private val ec = system.dispatcher

  /**
    * Key is client_nonce
    */
  private val clients: concurrent.Map[ByteVector, ClientInfo] =
    new ConcurrentHashMap[ByteVector, ClientInfo]().asScala

  private val severLogic = Flow[ByteString]
    .via(Framing.lengthField(4, 16, Int.MaxValue)) // the length of body is after 16 first bytes
    .map(bs => ByteVector(bs.toByteBuffer))
    .map(handleRequest)
    .takeWhile(_._2 != ClientState.Closed)
    .map(pair => ByteString(pair._1.toByteBuffer))
    .recover {
      case _: FramingException =>
        println("Error during framing data")
        ByteString(INVALID_REQ_ANSWER.toByteBuffer)
      case ex: Throwable =>
        println(s"An error has occurred during request handling. Message: '${ex.getMessage}'")
        ByteString(INVALID_REQ_ANSWER.toByteBuffer)
    }

  private val binding: Future[ServerBinding] = Tcp().bindAndHandle(severLogic, host, port)

  binding.onComplete {
    case Success(value) =>
      println(s"Server has started at ${value.localAddress.getHostName}:${value.localAddress.getPort}")
    case Failure(exception) =>
      println(s"Server launching has failed with exception message '${exception.getMessage}'")
  }

  private def handleRequest(bv: ByteVector): (ByteVector, ClientState.Value) = {
    // Check auth key with first 8 bytes
    if (!(bv.take(8) === UNENCRYPTED_AUTH_KEY_ID)) {
      (INVALID_REQ_ANSWER, ClientState.Error)
    } else {
      val messageId = bv.slice(8, 16).reverse.toHex // extract ID of message
      println(s"Got message with id '$messageId'")
      val messageLength = bv.slice(16, 20).reverse.toInt(signed = true /* or false ? */)  // extract length of message
      val body = bv.drop(20).take(messageLength) // all rest is body of message

      val firstConstructor = body.take(4).reverse // extract constructor of request type (TL scheme)
      val nonce = body.slice(4, 20) // extract client's unique ID

      firstConstructor match {
        case REQ_PQ_CONSTRUCTOR =>
          println(s"Client '${nonce.toHex}' is requesting (p,q)-exchange...")
          val serverNonce = generateServerNonce
          val (response, p, q) = reqPqHandler(nonce, serverNonce)
          clients.put(nonce, ClientInfo(ClientState.WaitDHParams, serverNonce, p, q)) // TODO What if client exists and has state "Closed"? Sounds impossible?
          (response, ClientState.WaitDHParams)
        case REQ_DH_PARAMS_CONSTRUCTOR =>
          val serverNonce = body.slice(20, 36)  // extract client version of server's unique ID
          val client = clients.get(nonce)
          if (client.isDefined) {
            val responseOpt = reqDhParamsHandler(client.get, nonce, serverNonce, body.drop(36))
            responseOpt match {
              case Some(response) =>
                println(s"Client '${nonce.toHex}' brought _valid_ DH-params")
                clients.update(nonce, client.get.copy(state = ClientState.Closed))
                (response, ClientState.Closed)
              case None =>
                println(s"Client '${nonce.toHex}' brought _invalid_ DH-params")
                (INVALID_REQ_ANSWER, ClientState.Error)
            }
          } else {
            (INVALID_REQ_ANSWER, ClientState.Error)
          }
        case _ =>
          println(s"Message '$messageId' has unknown constructor")
          (INVALID_REQ_ANSWER, ClientState.Error)
      }
    }
  }

  /**
    * Handler for "(p, q)-authorization request"
    *
    * @return (Serialized response, p, q)
    */
  private def reqPqHandler(nonce: ByteVector, serverNonce: ByteVector): (ByteVector, Long, Long) = {
    val start = UNENCRYPTED_AUTH_KEY_ID
    val messageId = ByteVector.fromLong(generateMessageId, ordering = ByteOrdering.LittleEndian)

    val (p, q) = PrimeUtils.getBigPair
    val pq = TLUtils.serializeString(ByteVector.fromLong(p * q).toHex).get
    val vectorCtor = VECTOR_LONG_CONSTRUCTOR.reverse
    val vectorSize = ByteVector.fromInt(1, ordering = ByteOrdering.LittleEndian)
    val fingerprint = little64bits.reverse

    val intLen = (RES_PQ_CONSTRUCTOR.size + nonce.size + serverNonce.size + pq.size + vectorCtor.size + vectorSize.size + fingerprint.size).toInt
    val messageLength = ByteVector.fromInt(intLen, ordering = ByteOrdering.LittleEndian)

    val response = start ++ messageId ++ messageLength ++ RES_PQ_CONSTRUCTOR.reverse ++ nonce ++
      serverNonce ++ pq ++ vectorCtor ++ vectorSize ++ fingerprint

    (response, p, q)
  }

  /**
    * Handler for "Start DH-keys exchange"
    *
    * @return Serialized response
    */
  private def reqDhParamsHandler(
    client: ClientInfo,
    nonce: ByteVector,
    serverNonce: ByteVector,
    restBody: ByteVector
  ): Option[ByteVector] = {
    for {
      (p, withoutP) <- deserializeString(restBody)
        .flatMap(x => ByteVector.fromHex(x._1).map(_ -> x._2))
      (q, withoutQ) <- deserializeString(withoutP)
        .flatMap(x => ByteVector.fromHex(x._1).map(_ -> x._2))
      publicKeyFingerprint = withoutQ.take(8).reverse
      _  <- if (
        client.serverNonce == serverNonce &&
          client.p == p.toLong() &&
          client.q == q.toLong() &&
        publicKeyFingerprint == little64bits
      )
        Some(())
      else
        None
    } yield {
      val encryptedData = withoutQ.drop(8)

      // TODO decrypt data... and build response

//      val start = UNENCRYPTED_AUTH_KEY_ID
//      val messageId = ByteVector.fromLong(generateMessageId, ordering = ByteOrdering.LittleEndian)

      ByteVector.empty
    }
  }
}

object MtServer {
  private val randomEngine = new Random()

  val UNENCRYPTED_AUTH_KEY_ID: ByteVector = ByteVector.fill(8)(0x00)
  val INVALID_REQ_ANSWER: ByteVector = hex"FFFFFFFF"

  // Example of server's public rsa
  val PUBLIC_RSA = "MIIBITANBgkqhkiG9w0BAQEFAAOCAQ4AMIIBCQKCAQB6zed/BUJOCzj4HN06136xvlUA2K3bd16Sbj+FZmdv/+CiS3TIID4fj/AXPyLnBOEnu+bAffhOuiMmQIxGsEWNGYrcsOAOng/gcZ2E3xrQHZQn51UMkSS9UBWazx99KZzzgFXBkUcc2cCf3WjugF4XIjbkvoIh35R/rZ2lDd4DTslP7gFngjMTz/SdWWlk8w265gMjxnctY18mEVWxh7gP8EVCcBmsP/irYoKkF5iERD5k4DBXlkMyouLIizpeVypVF0FCcTfQYSq5WcLTneIKba8bzqBYBGu/iidNEiLfbdIypboEvAZPJqZL/UDkQciDFXQslRPzol5v9FphOLDBAgMBAAE="

  /**
    * SHA-1 of server's public RSA
    */
  val sha1PublicRsa: Array[Byte] = {
    val md = java.security.MessageDigest.getInstance("SHA-1")
    md.digest(PUBLIC_RSA.getBytes("US-ASCII"))
  }

  /**
    * Little 64 bits of SHA-1 of server's public RSA
    */
  val little64bits: ByteVector = {
    val bv = ByteVector(sha1PublicRsa)
    bv.drop(bv.length - 8)
  }

  /**
    * According to MTProto docs it's about (unixtime * 2**32)
    */
  private def generateMessageId = {
    (System.currentTimeMillis() / 1000L) * (2L << 31)
  }

  /**
    * Start server with specified params
    *
    * @param host TCP-address
    * @param port TCP-port
    * @return Instance of server
    */
  def start(host: String, port: Int): MtServer = new MtServer(host, port)

  private def generateServerNonce: ByteVector = {
    val buffer = new Array[Byte](16)
    randomEngine.nextBytes(buffer)
    ByteVector(buffer)
  }

  case class ClientInfo(state: ClientState.Value, serverNonce: ByteVector, p: Long, q: Long)
}
