package com.qingstor.sdk.request

import akka.actor.ActorSystem
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.ActorMaterializer
import com.qingstor.sdk.constant.QSConstants
import com.qingstor.sdk.model.QSModels._
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future

class QSRequest(_operation: Operation, _input: Input) {
  private val input = _input
  val operation: Operation = _operation
  val HTTPRequest: HttpRequest = build()

  def send()(implicit system: ActorSystem, mat: ActorMaterializer): Future[HttpResponse] = {
    val config = ConfigFactory.parseString(
      s"""
         |akka.http {
         |  host-connection-pool {
         |    client {
         |      connecting-timeout = "5s"
         |      connection-timeout = "5s"
         |      user-agent-header = "${QSConstants.UserAgent}"
         |    }
         |    max-retries = ${operation.config.connectionRetries}
         |  }
         |}
      """.stripMargin
    ).withFallback(ConfigFactory.defaultReference())

    Http(system).singleRequest(request = sign(HTTPRequest), settings = ConnectionPoolSettings(config))
  }

  private def build(): HttpRequest = {
    require(operation.config != null, "Configuration can't be empty")
    val id = operation.config.accessKeyId
    val secret = operation.config.secretAccessKey
    require(id != null && secret != null && id != "" && secret != "",
      "Access Key ID or Secret Access Key can't be empty")

    val builder = RequestBuilder(operation, input)
    builder.build
  }

  private def sign(request: HttpRequest = HTTPRequest): HttpRequest = {
    val accessKeyID = operation.config.accessKeyId
    val secretAccessKey = operation.config.secretAccessKey
    val authString = QSSigner.getHeadAuthorization(request, accessKeyID, secretAccessKey)

    request.addHeader(RawHeader("Authorization", authString))
  }
}

object QSRequest {
  def apply(operation: Operation, input: Input): QSRequest = new QSRequest(operation, input)
  def apply(operation: Operation): QSRequest = new QSRequest(operation, null)

  def signQueries(request: QSRequest, liveTime: Long): Uri = {
    val expires = System.currentTimeMillis() + liveTime
    val accessKeyID = request.operation.config.accessKeyId
    val secretAccessKey = request.operation.config.secretAccessKey
    val authQueries = QSSigner.getQueryAuthorization(
      request.HTTPRequest,
      accessKeyID,
      secretAccessKey,
      expires
    )
    val oriQueries = request.HTTPRequest.uri.query().toMap

    request.HTTPRequest.uri.withQuery(Uri.Query(oriQueries ++ authQueries))
  }
}
