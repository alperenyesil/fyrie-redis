package net.fyrie
package redis
package actors

import messages._
import types._
import pubsub._

import akka.actor.{ Actor, ActorRef, IO, IOManager, Scheduler, ActorInitializationException }
import Actor.{ actorOf }
import akka.util.ByteString
import akka.event.EventHandler

import java.util.concurrent.TimeUnit
import java.net.ConnectException

import scala.collection.mutable.Queue

private[redis] final class RedisClientSession(ioManager: ActorRef, host: String, port: Int, config: RedisClientConfig) extends Actor {

  var socket: IO.SocketHandle = _
  var worker: ActorRef = _

  val waiting = Queue.empty[RequestMessage]
  var requests = 0L
  var requestCallbacks = Seq.empty[(Long, Long) ⇒ Unit]

  override def preStart = {
    EventHandler info (this, "Connecting")
    worker = actorOf(new RedisClientWorker(ioManager, host, port, config))
    self link worker
    socket = IO.connect(ioManager, host, port, worker)
    worker ! Socket(socket)
  }

  def receive = {

    case RequestCallback(callback) ⇒
      requestCallbacks +:= callback

    case msg: ResultCallback ⇒
      worker forward msg

    case req: Request ⇒
      socket write req.bytes
      worker forward Run
      onRequest(req)

    case req: MultiRequest ⇒
      sendMulti(req)
      worker forward MultiRun(req.cmds.map(_._2))
      onRequest(req)

    case Received ⇒
      waiting.dequeue

    case Socket(handle) ⇒
      socket = handle
      if (config.retryOnReconnect) {
        waiting foreach {
          case req: Request      ⇒ socket write req.bytes
          case req: MultiRequest ⇒ sendMulti(req)
        }
        if (waiting.nonEmpty) EventHandler info (this, "Retrying " + waiting.length + " commands")
      }

    case Disconnect ⇒
      EventHandler info (this, "Shutting down")
      worker ! Disconnect
      self.stop()

  }

  def onRequest(req: RequestMessage): Unit = {
    if (config.retryOnReconnect) waiting enqueue req
    requests += 1L
    if (requestCallbacks.nonEmpty) {
      val atTime = System.currentTimeMillis
      requestCallbacks foreach (_(requests, atTime))
    }
  }

  def sendMulti(req: MultiRequest): Unit = {
    socket write req.multi
    req.cmds foreach { cmd ⇒
      socket write cmd._1
    }
    socket write req.exec
  }

}

private[redis] final class RedisSubscriberSession(listener: ActorRef)(ioManager: ActorRef, host: String, port: Int, config: RedisClientConfig) extends Actor {

  var socket: IO.SocketHandle = _
  var worker: ActorRef = _

  var client: RedisClientSub = _

  override def preStart = {
    client = new RedisClientSub(self, config)
    EventHandler info (this, "Connecting")
    worker = actorOf(new RedisClientWorker(ioManager, host, port, config))
    self link worker
    socket = IO.connect(ioManager, host, port, worker)
    worker ! Socket(socket)
    worker ! Subscriber(listener)
  }

  def receive = {

    case Subscribe(channels) ⇒
      socket write (client.subscribe(channels))

    case Unsubscribe(channels) ⇒
      socket write (client.unsubscribe(channels))

    case PSubscribe(patterns) ⇒
      socket write (client.psubscribe(patterns))

    case PUnsubscribe(patterns) ⇒
      socket write (client.punsubscribe(patterns))

    case Disconnect ⇒
      EventHandler info (this, "Shutting down")
      socket.close
      self.stop()

  }

}

private[redis] final class RedisClientWorker(ioManager: ActorRef, host: String, port: Int, config: RedisClientConfig) extends Actor {
  import Protocol._
  import akka.util.iteratee._

  var socket: IO.SocketHandle = _

  val state = new IterateeRef(Iteratee.unit)

  var results = 0L
  var resultCallbacks = Seq.empty[(Long, Long) ⇒ Unit]

  def receive = {

    case IO.Read(handle, bytes) ⇒
      state(bytes)

    case IO.Connected(handle) ⇒

    case ResultCallback(callback) ⇒
      resultCallbacks +:= callback

    case Socket(handle) ⇒
      socket = handle

    case IO.Closed(handle, Some(cause: ConnectException)) if socket == handle && config.autoReconnect ⇒
      EventHandler info (this, "Connection refused, retrying in 1 second")
      Scheduler.scheduleOnce(self, IO.Closed(handle, None), 1, TimeUnit.SECONDS)

    case IO.Closed(handle, cause) if socket == handle && config.autoReconnect ⇒
      EventHandler info (this, "Reconnecting" + (cause map (e ⇒ ", cause: " + e.toString) getOrElse ""))
      socket = IO.connect(ioManager, host, port, self)
      sendToSupervisor(Socket(socket))

    case IO.Closed(handle, cause) if socket == handle ⇒
      EventHandler info (this, "Connection closed" + (cause map (e ⇒ ", cause: " + e.toString) getOrElse ""))
      sendToSupervisor(Disconnect)

    case Run ⇒
      val source = channel
      for {
        _ ← state
        result ← readResult
      } yield {
        source tryTell result
        onResult()
      }

    case msg: MultiRun ⇒
      val source = channel
      for {
        _ ← state
        _ ← readResult
        _ ← (Iteratee.unit /: msg.promises)((iter, promise) ⇒
          for (_ ← iter; result ← readResult) yield promise completeWithResult result)
        exec ← readResult
      } yield {
        source tryTell exec
        onResult()
      }

    case Subscriber(listener) ⇒
      state flatMap (_ ⇒ subscriber(listener))

    case Disconnect ⇒
      // TODO: Complete all waiting requests with a RedisConnectionException
      socket.close
      self.stop()

  }

  def onResult() {
    if (config.retryOnReconnect) sendToSupervisor(Received)
    results += 1L
    if (resultCallbacks.nonEmpty) {
      val atTime = System.currentTimeMillis
      resultCallbacks foreach (_(results, atTime))
    }
  }

  def sendToSupervisor(msg: Any) {
    try {
      self.supervisor foreach (_ ! msg)
    } catch {
      case e: ActorInitializationException ⇒ // ignore, probably shutting down
    }
  }

  def subscriber(listener: ActorRef): Iteratee[Unit] = readResult flatMap { result ⇒
    result match {
      case RedisMulti(Some(List(RedisBulk(Some(Protocol.message)), RedisBulk(Some(channel)), RedisBulk(Some(message))))) ⇒
        listener ! pubsub.Message(channel, message)
      case RedisMulti(Some(List(RedisBulk(Some(Protocol.pmessage)), RedisBulk(Some(pattern)), RedisBulk(Some(channel)), RedisBulk(Some(message))))) ⇒
        listener ! pubsub.PMessage(pattern, channel, message)
      case RedisMulti(Some(List(RedisBulk(Some(Protocol.subscribe)), RedisBulk(Some(channel)), RedisInteger(count)))) ⇒
        listener ! pubsub.Subscribed(channel, count)
      case RedisMulti(Some(List(RedisBulk(Some(Protocol.unsubscribe)), RedisBulk(Some(channel)), RedisInteger(count)))) ⇒
        listener ! pubsub.Unsubscribed(channel, count)
      case RedisMulti(Some(List(RedisBulk(Some(Protocol.psubscribe)), RedisBulk(Some(pattern)), RedisInteger(count)))) ⇒
        listener ! pubsub.PSubscribed(pattern, count)
      case RedisMulti(Some(List(RedisBulk(Some(Protocol.punsubscribe)), RedisBulk(Some(pattern)), RedisInteger(count)))) ⇒
        listener ! pubsub.PUnsubscribed(pattern, count)
      case other ⇒
        throw RedisProtocolException("Unexpected response")
    }
    subscriber(listener)
  }

  def readResult: Iteratee[RedisType] =
    take(1) flatMap {
      _.head.toChar match {

        case '+' ⇒
          takeUntil(EOL) map (bytes ⇒ RedisString(bytes.utf8String))

        case '-' ⇒
          takeUntil(EOL) map (bytes ⇒ RedisError(bytes.utf8String))

        case ':' ⇒
          takeUntil(EOL) map (bytes ⇒ RedisInteger(bytes.utf8String.toLong))

        case '$' ⇒
          takeUntil(EOL) flatMap {
            _.utf8String.toInt match {
              case -1 ⇒ Iteratee(RedisBulk.notfound)
              case 0  ⇒ Iteratee(RedisBulk.empty)
              case n  ⇒ for (bytes ← take(n); _ ← takeUntil(EOL)) yield RedisBulk(Some(bytes))
            }
          }

        case '*' ⇒
          takeUntil(EOL) flatMap {
            _.utf8String.toInt match {
              case -1 ⇒ Iteratee(RedisMulti.notfound)
              case 0  ⇒ Iteratee(RedisMulti.empty)
              case n ⇒
                ((Iteratee.unit map (_ ⇒ new Array[RedisType](n))) /: (0 until n)) { (iter, i) ⇒
                  for (ar ← iter; r ← readResult) yield {
                    ar(i) = r
                    ar
                  }
                } map (ar ⇒ RedisMulti(Some(ar.toList)))
            }
          }

        case x ⇒
          throw RedisProtocolException("Invalid result type: " + x.toByte)
      }
    }
}
