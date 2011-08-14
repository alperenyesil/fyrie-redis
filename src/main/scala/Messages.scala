package net.fyrie
package redis
package messages

import akka.util.ByteString
import akka.actor.IO
import akka.dispatch.Promise

import types.RedisType

private[redis] sealed trait Message
private[redis] sealed trait RequestMessage extends Message
private[redis] case class Request(bytes: ByteString) extends RequestMessage
private[redis] case class MultiRequest(multi: ByteString, cmds: Seq[(ByteString, Promise[RedisType])], exec: ByteString) extends RequestMessage
private[redis] case object Disconnect extends Message
private[redis] case object Run extends Message
private[redis] case class MultiRun(promises: Seq[Promise[RedisType]]) extends Message
private[redis] case class Socket(handle: IO.SocketHandle) extends Message
private[redis] case object Received extends Message
