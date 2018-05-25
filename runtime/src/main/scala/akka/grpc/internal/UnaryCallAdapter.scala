/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.grpc.internal

import java.util.concurrent.CompletionStage

import akka.annotation.InternalApi
import akka.grpc.GrpcSingleResponse
import akka.util.OptionVal
import io.grpc._

import scala.compat.java8.FutureConverters._
import scala.concurrent.{ Future, Promise }

/**
 * gRPC Netty based client listener transforming callbacks into a future response
 *
 * INTERNAL API
 */
@InternalApi
private[akka] final class UnaryCallAdapter[Res] extends ClientCall.Listener[Res] {
  private val responsePromise = Promise[Res]()

  override def onMessage(message: Res): Unit = {
    // close over var and make final
    if (!responsePromise.trySuccess(message)) {
      throw Status.INTERNAL.withDescription("More than one value received for unary call")
        .asRuntimeException()
    }
  }

  override def onClose(status: Status, trailers: Metadata): Unit = {
    if (status.isOk) {
      if (!responsePromise.isCompleted)
        // No value received so mark the future as an error
        responsePromise.tryFailure(
          Status.INTERNAL.withDescription("No value received for unary call")
            .asRuntimeException(trailers))
    } else {
      responsePromise.tryFailure(status.asRuntimeException(trailers))
    }
  }

  def future: Future[Res] = responsePromise.future
  def cs: CompletionStage[Res] = future.toJava
}

/**
 * gRPC Netty based client listener transforming callbacks into a future response
 *
 * INTERNAL API
 */
// needs to be a separate class because of CompletionStage error handling not bubling
// exceptions like Scala Futures do ;( flip side is that it saves some garbage
@InternalApi
private[akka] final class UnaryCallWithMetadataAdapter[Res] extends ClientCall.Listener[Res] {
  private val responsePromise = Promise[GrpcSingleResponse[Res]]()
  private var headers: OptionVal[Metadata] = OptionVal.None
  private val trailerPromise = Promise[Metadata]()

  // always invoked before message
  override def onHeaders(headers: Metadata): Unit = {
    this.headers = OptionVal.Some(headers)
  }

  override def onMessage(message: Res): Unit = {
    // close over var and make final
    val headersOnMessage = headers.x
    val responseWithMetadata = new GrpcSingleResponse[Res] {
      def headers: Metadata = headersOnMessage
      def getHeaders() = headersOnMessage
      def value: Res = message
      def getValue: Res = message

      def trailers: Future[Metadata] = trailerPromise.future
      def getTrailers: CompletionStage[Metadata] = trailerPromise.future.toJava
    }
    if (!responsePromise.trySuccess(responseWithMetadata)) {
      throw Status.INTERNAL.withDescription("More than one value received for unary call")
        .asRuntimeException()
    }
  }

  override def onClose(status: Status, trailers: Metadata): Unit = {
    if (status.isOk) {
      if (!responsePromise.isCompleted)
        // No value received so mark the future as an error
        responsePromise.tryFailure(
          Status.INTERNAL.withDescription("No value received for unary call")
            .asRuntimeException(trailers))
      trailerPromise.success(trailers)
    } else {
      responsePromise.tryFailure(status.asRuntimeException(trailers))
      trailerPromise.success(trailers)
    }
  }

  def future: Future[GrpcSingleResponse[Res]] = responsePromise.future
  def cs: CompletionStage[GrpcSingleResponse[Res]] = future.toJava
}