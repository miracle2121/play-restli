package com.linkedin.restli.server.play

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

import akka.stream.javadsl.Source
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import com.linkedin.r2.message.stream.entitystream.{EntityStreams, WriteHandle, Writer}
import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Environment}
import play.api.http._
import play.api.libs.streams.Accumulator
import play.api.mvc._
import play.api.routing.Router
import play.core.j.JavaHandlerComponents
import play.core.j.JavaHelpers._
import play.mvc.Http.{RawBuffer, RequestBody}
import play.utils.Threads

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Promise}

/** Created by rli on 2/16/16.
  *
  * Overrides Play's default routing behavior and let rest.li handle the request
  */
@Singleton
class RestliServerHttpRequestHandler @Inject() (configuration: Configuration,
                                                env: Environment,
                                                restliServerApi: RestliServerApi,
                                                restliStreamServerApi: RestliServerStreamApi,
                                                router: Router,
                                                errorHandler: HttpErrorHandler,
                                                httpConfig: HttpConfiguration,
                                                httpFilters: HttpFilters,
                                                components: JavaHandlerComponents,
                                                playBodyParsers: PlayBodyParsers,
                                                actionBuilder: DefaultActionBuilder
                                               )(implicit ec: ExecutionContext)
  extends JavaCompatibleHttpRequestHandler(router, errorHandler, httpConfig, httpFilters, components) {

  private[server] val memoryThresholdBytes: Long = configuration.underlying.getBytes("restli.memoryThresholdBytes")
  private[server] val SupportedRestliMethods = Set("GET", "POST", "PUT", "PATCH", "HEAD", "DELETE", "OPTIONS")
  private[server] val useStream: Boolean = configuration.get[Boolean]("restli.useStream")

  require(memoryThresholdBytes.isValidInt, "restli.memoryThresholdBytes not a valid 32bit integer.")

  private def restliRequestHandler: Handler = {
    actionBuilder.async(playBodyParsers.byteString(memoryThresholdBytes.toInt)) { scalaRequest =>
      val javaContext = createJavaContext(scalaRequest.map(data => new RequestBody(new RawBuffer {
        override def size(): java.lang.Long = data.size.toLong

        override def asBytes(maxLength: Int): ByteString = data.take(maxLength)

        override def asBytes(): ByteString = data

        override def asFile(): File = null
      })), components.contextComponents)

      val callback = new RestliTransportCallback()
      restliServerApi.handleRequest(javaContext.request(), callback)
      buildResult[Array[Byte]](callback, (result, body) => result(body))
    }
  }

  private def restliStreamRequestHandler: Handler = new EssentialAction {
    override def apply(requestHeader: RequestHeader): Accumulator[ByteString, Result] = {
      trait EntityStreamWriter extends Writer {
        def write(bytes: ByteString): Future[EntityStreamWriter]
        def done(): Unit
        def error(ex: Throwable): Unit
      }

      val writer: EntityStreamWriter = new EntityStreamWriter {
        val abortException = new AtomicReference[Throwable]()
        var writeHandle: Option[WriteHandle] = None
        val queue = new ConcurrentLinkedQueue[(Promise[EntityStreamWriter], ByteString)]()

        override def onAbort(e: Throwable): Unit = {
          // Abort all remaining items
          abortException.set(e)
          while (true) {
            queue.poll() match {
              case null =>
                return
              case (promise, _) =>
                promise.failure(e)
            }
          }
        }

        override def onWritePossible(): Unit = {
          queue.synchronized {
            writeHandle.foreach { wh =>
              while (wh.remaining() > 0 && !queue.isEmpty) {
                queue.remove() match {
                  case (promise, data) =>
                    wh.write(com.linkedin.data.ByteString.copy(data.toArray))
                    promise.success(this)
                }
              }
            }
          }
        }

        override def onInit(wh: WriteHandle): Unit = {
          writeHandle.synchronized {
            writeHandle = Some(wh)
          }
        }

        override def write(bytes: ByteString): Future[EntityStreamWriter] = {
          if (abortException.get() != null) {
            // Already aborted
            Future.failed(abortException.get())
          } else {
            // Enqueue
            val promise = Promise[EntityStreamWriter]()
            queue.add((promise, bytes))
            onWritePossible()
            promise.future
          }
        }

        override def done(): Unit = {
          Threads.withContextClassLoader(env.classLoader) {
            writeHandle.foreach(_.done())
          }
        }

        override def error(ex: Throwable): Unit = {
          Threads.withContextClassLoader(env.classLoader) {
            writeHandle.foreach(_.error(ex))
          }
        }
      }

      val sink = Sink.foldAsync[EntityStreamWriter, ByteString](writer)((thisWriter, bytes) => {
        thisWriter.write(bytes)
      }).mapMaterializedValue(_.map(_.done()).recover {
        case ex: Throwable => writer.error(ex)
      })

      val javaContext = createJavaContext(Request(requestHeader, new RequestBody(EntityStreams.newEntityStream(writer))), components.contextComponents)

      val callback: RestliStreamTransportCallback = new RestliStreamTransportCallback()
      restliStreamServerApi.handleRequest(javaContext.request(), callback)
      val resultFuture = buildResult[Source[ByteString, _]](callback, (result, body) => result.copy(body = HttpEntity.Streamed(body.asScala, None, result.body.contentType)))

      Accumulator(sink.mapMaterializedValue(_ => resultFuture))
    }
  }

  private def buildResult[T](callback: BaseRestliTransportCallback[_, _ <: BaseGenericResponse[T], T], addBody: (Results.Status, T) => Result) = {
    callback.getPromise.future.map { response =>
      // Ensure that we *only* include headers from the RestResponse and not any that the Play Status object may add by default
      val result = addBody(Results.Status(response.getStatus), response.getBody)
      // Purposely parse the set-cookie header again to protected against the case
      // where intermediate filter accidentally generate ill-formed cookie headers.
      val cookies = response.getCookies.asScala.foldLeft(Seq.empty[Cookie]){ (cookies, cookieHeader) =>
        cookies ++ Cookies.fromSetCookieHeader(Option(cookieHeader)).toSeq
      }
      val contentType = Option(response.getHeaders.get(HeaderNames.CONTENT_TYPE))
      // Setting result header Content-Type no longer takes effect. The type has to be set at the result directly.
      val resultCopy = result.copy(header = result.header.copy(headers = response.getHeaders.asScala.filter(_._1 != HeaderNames.CONTENT_TYPE).toMap)).withCookies(cookies:_*)
      contentType.map(resultCopy.as).getOrElse(resultCopy)
    }
  }

  /** Overrides the default play routing behavior. It first looks for a route that could handle this request.
    * If such a route is not found, then handle the request as a restli request.
    */
  override def routeRequest(rh: RequestHeader): Option[Handler] = {
    super.routeRequest(rh).orElse {
      if (SupportedRestliMethods.contains(rh.method)) {
        if (useStream) {
          Some(restliStreamRequestHandler)
        } else {
          Some(restliRequestHandler)
        }
      } else {
        None
      }
    }
  }
}
