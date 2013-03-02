/*
 * Copyright (C) 2012-2013 Age Mooij (http://scalapenos.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.scalapenos.riak
package internal

import akka.actor._


private[riak] object RiakHttpClientHelper {
  import spray.http.HttpBody
  import spray.httpx.marshalling._

  /**
   * Spray Marshaller for turning RiakValue instances into HttpEntity instances so they can be sent to Riak.
   */
  implicit val RiakValueMarshaller: Marshaller[RiakValue] = new Marshaller[RiakValue] {
    def apply(riakValue: RiakValue, ctx: MarshallingContext) {
      ctx.marshalTo(HttpBody(riakValue.contentType, riakValue.data.getBytes(riakValue.contentType.charset.nioCharset)))
    }
  }
}

private[riak] class RiakHttpClientHelper(system: ActorSystem) extends RiakUrlSupport {
  import scala.concurrent.Future
  import scala.concurrent.Future._

  import spray.client.HttpClient
  import spray.client.pipelining._
  import spray.http.{HttpEntity, HttpHeader, HttpResponse}
  import spray.http.StatusCodes._
  import spray.http.HttpHeaders._

  import org.slf4j.LoggerFactory

  import SprayClientExtras._
  import SprayJsonSupport._
  import RiakHttpHeaders._
  import RiakHttpClientHelper._

  import system.dispatcher

  private val httpClient = system.actorOf(Props(new HttpClient()), "riak-http-client")
  private val settings = RiakClientExtension(system).settings
  private val log = LoggerFactory.getLogger(getClass)


  // ==========================================================================
  // Main HTTP Request Implementations
  // ==========================================================================

  def fetch(server: RiakServerInfo, bucket: String, key: String, resolver: ConflictResolver): Future[Option[RiakValue]] = {
    httpRequest(Get(url(server, bucket, key))).flatMap { response =>
      response.status match {
        case OK                 => successful(toRiakValue(response))
        case NotFound           => successful(None)
        case MultipleChoices    => resolveConflict(server, bucket, key, response, resolver)
        case BadRequest         => throw new ParametersInvalid("Does Riak even give us a reason for this?")
        case other              => throw new BucketOperationFailed(s"Fetch for key '$key' in bucket '$bucket' produced an unexpected response code '$other'.")
        // TODO: case NotModified => successful(None)
      }
    }
  }

  def fetch(server: RiakServerInfo, bucket: String, index: RiakIndex, resolver: ConflictResolver): Future[List[RiakValue]] = {
    httpRequest(Get(indexUrl(server, bucket, index))).flatMap { response =>
      response.status match {
        case OK              => fetchWithKeysReturnedByIndexLookup(server, bucket, response, resolver)
        case BadRequest      => throw new ParametersInvalid(s"""Invalid index name ("${index.fullName}") or value ("${index.value}").""")
        case other           => throw new BucketOperationFailed(s"""Fetch for index "${index.fullName}" with value "${index.value}" in bucket "${bucket}" produced an unexpected response code: ${other}.""")
      }
    }
  }

  def fetch(server: RiakServerInfo, bucket: String, indexRange: RiakIndexRange, resolver: ConflictResolver): Future[List[RiakValue]] = {
    httpRequest(Get(indexRangeUrl(server, bucket, indexRange))).flatMap { response =>
      response.status match {
        case OK              => fetchWithKeysReturnedByIndexLookup(server, bucket, response, resolver)
        case BadRequest      => throw new ParametersInvalid(s"""Invalid index name ("${indexRange.fullName}") or range ("${indexRange.start}" to "${indexRange.start}").""")
        case other           => throw new BucketOperationFailed(s"""Fetch for index "${indexRange.fullName}" with range "${indexRange.start}" to "${indexRange.start}" in bucket "${bucket}" produced an unexpected response code: ${other}.""")
      }
    }
  }

  def store(server: RiakServerInfo, bucket: String, key: String, value: RiakValue, returnBody: Boolean, resolver: ConflictResolver): Future[Option[RiakValue]] = {
    // TODO: add the Last-Modified value from the RiakValue as a header

    val vclockHeader = value.vclock.toOption.map(vclock => RawHeader(`X-Riak-Vclock`, vclock))
    val etagHeader = value.etag.toOption.map(etag => RawHeader(`ETag`, etag))
    val indexHeaders = value.indexes.map(toIndexHeader(_)).toList

    val request = addOptionalHeader(vclockHeader) ~>
                  addOptionalHeader(etagHeader) ~>
                  addHeaders(indexHeaders) ~>
                  httpRequest

    request(Put(url(server, bucket, key, StoreQueryParameters(returnBody)), value)).flatMap { response =>
      response.status match {
        case OK              => successful(toRiakValue(response))
        case NoContent       => successful(None)
        case MultipleChoices => resolveConflict(server, bucket, key, response, resolver)
        case BadRequest      => throw new ParametersInvalid("Does Riak even give us a reason for this?")
        case other           => throw new BucketOperationFailed(s"Store of value '$value' for key '$key' in bucket '$bucket' produced an unexpected response code '$other'.")
        // TODO: case PreconditionFailed => ... // needed when we support conditional request semantics
      }
    }
  }

  def delete(server: RiakServerInfo, bucket: String, key: String): Future[Unit] = {
    httpRequest(Delete(url(server, bucket, key))).map { response =>
      response.status match {
        case NoContent       => ()
        case NotFound        => ()
        case BadRequest      => throw new ParametersInvalid("Does Riak even give us a reason for this?")
        case other           => throw new BucketOperationFailed(s"Delete for key '$key' in bucket '$bucket' produced an unexpected response code '$other'.")
      }
    }
  }

  def getBucketProperties(server: RiakServerInfo, bucket: String): Future[RiakBucketProperties] = {
    import spray.httpx.unmarshalling._

    httpRequest(Get(bucketPropertiesUrl(server, bucket))).map { response =>
      response.status match {
        case OK => response.entity.as[RiakBucketProperties] match {
          case Right(properties) => properties
          case Left(error)       => throw new BucketOperationFailed(s"Fetching properties of bucket '$bucket' failed because the response entity could not be parsed.")
        }
        case other => throw new BucketOperationFailed(s"Fetching properties of bucket '$bucket' produced an unexpected response code '$other'.")
      }
    }
  }

  def setBucketProperties(server: RiakServerInfo, bucket: String, newProperties: Set[RiakBucketProperty[_]]): Future[Unit] = {
    import spray.json._

    val entity = JsObject("props" -> JsObject(newProperties.map(property => (property.name -> property.json)).toMap))

    httpRequest(Put(bucketPropertiesUrl(server, bucket), entity)).map { response =>
      response.status match {
        case NoContent            => ()
        case BadRequest           => throw new ParametersInvalid(s"Setting properties of bucket '$bucket' failed because the http request contained invalid data.")
        case UnsupportedMediaType => throw new BucketOperationFailed(s"Setting properties of bucket '$bucket' failed because the content type of the http request was not 'application/json'.")
        case other                => throw new BucketOperationFailed(s"Setting properties of bucket '$bucket' produced an unexpected response code '$other'.")
      }
    }
  }


  // ==========================================================================
  // Request building
  // ==========================================================================

  private lazy val clientId = java.util.UUID.randomUUID().toString
  private val clientIdHeader = if (settings.AddClientIdHeader) Some(RawHeader(`X-Riak-ClientId`, clientId)) else None

  private def httpRequest = {
    addOptionalHeader(clientIdHeader) ~>
    addHeader("Accept", "*/*, multipart/mixed") ~>
    sendReceive(httpClient)
  }


  // ==========================================================================
  // Response => RiakValue
  // ==========================================================================

  private def toRiakValue(response: HttpResponse): Option[RiakValue] = toRiakValue(response.entity, response.headers)
  private def toRiakValue(entity: HttpEntity, headers: List[HttpHeader]): Option[RiakValue] = {
    entity.toOption.flatMap { body =>
      val vClockOption       = headers.find(_.is(`X-Riak-Vclock`.toLowerCase)).map(_.value)
      val eTagOption         = headers.find(_.is("etag")).map(_.value)
      val lastModifiedOption = headers.find(_.is("last-modified"))
                                      .map(h => new DateTime(h.asInstanceOf[`Last-Modified`].date.clicks))
      val indexes            = toRiakIndexes(headers)

      // TODO: make sure the DateTime is always in the Zulu zone

      for (vClock <- vClockOption; eTag <- eTagOption; lastModified <- lastModifiedOption)
      yield RiakValue(body.asString, body.contentType, vClock, eTag, lastModified, indexes)
    }
  }

  // ==========================================================================
  // HttpHeader <=> RiakIndex
  // ==========================================================================

  // TODO: declare a config setting for whether we url encode the index name and/or value
  //       maybe even at the top-level (for bucket names and keys) so it matches the behaviour of the riak url compatibility setting

  private def toIndexHeader(index: RiakIndex): HttpHeader = {
    index match {
      case l: RiakLongIndex   => RawHeader(indexHeaderPrefix + urlEncode(l.fullName), l.value.toString)
      case s: RiakStringIndex => RawHeader(indexHeaderPrefix + urlEncode(s.fullName), urlEncode(s.value))
    }
  }

  private def toRiakIndexes(headers: List[HttpHeader]): Set[RiakIndex] = {
    val IndexNameAndType = (indexHeaderPrefix + "(.+)_(bin|int)$").r

    def toRiakIndex(header: HttpHeader): Set[RiakIndex] = {
      header.lowercaseName match {
        case IndexNameAndType(name, "int") => header.value.split(',').map(value => RiakIndex(urlDecode(name), value.trim.toLong)).toSet
        case IndexNameAndType(name, "bin") => header.value.split(',').map(value => RiakIndex(urlDecode(name), urlDecode(value.trim))).toSet
        case _                             => Set.empty[RiakIndex]
      }
    }

    headers.filter(_.lowercaseName.startsWith(indexHeaderPrefix))
           .flatMap(toRiakIndex(_))
           .toSet
  }

  case class RiakIndexQueryResponse(keys: List[String])
  object RiakIndexQueryResponse {
    import spray.json._
    import spray.json.DefaultJsonProtocol._

    implicit val format = jsonFormat1(RiakIndexQueryResponse.apply)
  }

  private def fetchWithKeysReturnedByIndexLookup(server: RiakServerInfo, bucket: String, response: HttpResponse, resolver: ConflictResolver): Future[List[RiakValue]] = {
    response.entity.toOption.map { body =>
      import spray.json._

      val keys = body.asString.asJson.convertTo[RiakIndexQueryResponse].keys

      traverse(keys)(fetch(server, bucket, _, resolver)).map(_.flatten)
    }.getOrElse(successful(Nil))
  }


  // ==========================================================================
  // Conflict Resolution
  // ==========================================================================

  private def resolveConflict(server: RiakServerInfo, bucket: String, key: String, response: HttpResponse, resolver: ConflictResolver): Future[Option[RiakValue]] = {
    import spray.http._
    import spray.httpx.unmarshalling._

    val vclockHeader = response.headers.find(_.is(`X-Riak-Vclock`.toLowerCase)).toList

    response.entity.as[MultipartContent] match {
      case Left(error) => throw new ConflictResolutionFailed(error.toString)
      case Right(multipartContent) => {
        val values = multipartContent.parts.flatMap(part => toRiakValue(part.entity, vclockHeader ++ part.headers)).toSet
        val value = resolver.resolve(values)

        // Store the resolved value back to Riak and return the resulting RiakValue
        store(server, bucket, key, value, true, resolver)
      }
    }
  }
}
