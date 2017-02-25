package com.qingstor.sdk.request

import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, Uri}

object QSSigner {

  def getHeadAuthorization(request: HttpRequest,
                           accessKeyID: String,
                           secretAccessKey: String): String = {
    val emptyHeader = RawHeader("empty", "")
    val method: String = request.method.value
    val contentMD5: String =
      request.getHeader("Content-MD5").orElse(emptyHeader).value()
    val contentType: String =
      request.getHeader("Content-Type").orElse(emptyHeader).value()
    val date: String = request.getHeader("Date").get().value()
    val canonicalizedHeaders: String = parseCanonicalizedHeaders(
      request.headers)
    val canonicalizedResource: String = parseCanonicalizedResource(request.uri)
    val stringToSign = parseStringToSign(method,
                                         contentMD5,
                                         contentType,
                                         date,
                                         canonicalizedHeaders,
                                         canonicalizedResource)
    "QS " + accessKeyID + ":" + calAuthorization(stringToSign, secretAccessKey)
  }

  def getQueryAuthorization(request: HttpRequest,
                            accessKeyID: String,
                            secretAccessKey: String,
                            expire: Long): Map[String, String] = {
    val emptyHeader = RawHeader("empty", "")
    val method: String = request.method.value
    val contentMD5: String =
      request.getHeader("Content-MD5").orElse(emptyHeader).value()
    val contentType: String =
      request.getHeader("Content-Type").orElse(emptyHeader).value()
    val expireString: String = expire.toString
    val canonicalizedHeaders: String = parseCanonicalizedHeaders(
      request.headers)
    val canonicalizedResource: String = parseCanonicalizedResource(request.uri)
    val stringToSign = parseStringToSign(method,
                                         contentMD5,
                                         contentType,
                                         expireString,
                                         canonicalizedHeaders,
                                         canonicalizedResource)
    val authorization = calAuthorization(stringToSign, secretAccessKey)
    Map[String, String](
      "access_key_id" -> accessKeyID,
      "expires" -> expireString,
      "signature" -> URLEncoder.encode(authorization, "UTF-8")
    )
  }

  private def parseCanonicalizedHeaders(headers: Seq[HttpHeader]): String = {
    var map = Map[String, String]()
    headers.foreach { header =>
      if (header.name().startsWith("X-QS-"))
        map += (header.name().toLowerCase -> header.value())
    }
    map = Map(map.toSeq.sortBy(_._1): _*)
    map.mkString("\n").replace(" -> ", ":")
  }

  private def parseCanonicalizedResource(uri: Uri): String = {
    val pramsToSign = Map(
      "acl" -> true,
      "cors" -> true,
      "delete" -> true,
      "mirror" -> true,
      "part_number" -> true,
      "policy" -> true,
      "stats" -> true,
      "upload_id" -> true,
      "uploads" -> true,
      "response-expires" -> true,
      "response-cache-control" -> true,
      "response-content-type" -> true,
      "response-content-language" -> true,
      "response-content-encoding" -> true,
      "response-content-disposition" -> true
    )

    val path = uri.path.toString()
    val queries = uri.query().toMap
    var subQueries = Map.empty[String, String]
    var subQueriesString = ""
    queries.keys.foreach { key =>
      if (pramsToSign.getOrElse(key, false))
        subQueries += (key -> queries(key))
    }
    if (subQueries.nonEmpty) {
      subQueries = Map(subQueries.toSeq.sortBy(_._1): _*)
      for (query <- subQueries) {
        subQueriesString += (query._1 + (if (query._2.nonEmpty) "=" + query._2
                                         else "") + "&")
      }
      subQueriesString =
        subQueriesString.substring(0, subQueriesString.lastIndexOf("&"))
    }
    if (subQueries.isEmpty)
      path + subQueriesString
    else
      path + "?" + subQueriesString
  }

  private def parseStringToSign(method: String,
                                contentMD5: String,
                                contentType: String,
                                date: String,
                                headers: String,
                                resource: String): String = {
    val lineMD5 = Option[String](contentMD5).getOrElse("")
    val lineType = Option[String](contentType).getOrElse("")
    val lineHeaders = if (headers.isEmpty) headers else headers + "\n"
    method + "\n" + lineMD5 + "\n" + lineType + "\n" + date + "\n" + lineHeaders + resource
  }

  private def calAuthorization(stringToSign: String,
                               secretAccessKey: String): String = {
    val secret =
      new SecretKeySpec(secretAccessKey.getBytes("UTF-8"), "HmacSHA256")
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(secret)
    val signData = mac.doFinal(stringToSign.getBytes("UTF-8"))
    Base64.getEncoder.encodeToString(signData)
  }
}