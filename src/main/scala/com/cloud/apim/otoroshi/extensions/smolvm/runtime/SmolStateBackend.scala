package com.cloud.apim.otoroshi.extensions.smolvm.runtime

import akka.util.ByteString
import io.lettuce.core.SetArgs
import otoroshi.env.Env
import otoroshi.statefulclients.LettuceStatefulClientConfig
import otoroshi.storage.RedisLike
import play.api.Logger

import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Promise}

/**
 * Minimal external state used to make the lazy instance pool cluster-safe: a small set of
 * hash / counter / lock operations on a shared store. Two backends:
 *  - [[LettuceStateBackend]] : a dedicated Redis reached through otoroshi's StatefulClient
 *    (cluster-wide, the recommended setup), opened from a config/env `state.uri`.
 *  - [[RedisLikeStateBackend]] : fallback on otoroshi's own datastore redis (`env.datastores.redis`)
 *    when no `state.uri` is configured.
 *
 * Values are stored as UTF-8 JSON strings.
 */
trait SmolStateBackend {
  def hgetAll(key: String)(implicit ec: ExecutionContext): Future[Map[String, String]]
  def hset(key: String, field: String, value: String)(implicit ec: ExecutionContext): Future[Unit]
  def hdel(key: String, field: String)(implicit ec: ExecutionContext): Future[Unit]
  def incr(key: String)(implicit ec: ExecutionContext): Future[Long]
  def acquireLock(key: String, ttlMs: Long)(implicit ec: ExecutionContext): Future[Boolean]
  def releaseLock(key: String)(implicit ec: ExecutionContext): Future[Unit]
  def del(key: String)(implicit ec: ExecutionContext): Future[Unit]
}

object SmolStateBackend {
  private val logger = Logger("cloud-apim-smolmachine")

  /** Pick the external Redis (StatefulClient) when a uri is set, otherwise the datastore redis. */
  def apply(env: Env, clientId: String, uri: Option[String]): SmolStateBackend = uri.map(_.trim).filter(_.nonEmpty) match {
    case Some(u) =>
      logger.info(s"[smolmachine] using external Redis state via StatefulClient ($clientId)")
      new LettuceStateBackend(env, clientId, u)
    case None    =>
      logger.warn("[smolmachine] no 'state.uri' configured — falling back to otoroshi datastore redis for placement state (set a redis uri for a dedicated, cluster-safe state)")
      new RedisLikeStateBackend(env.datastores.redis, env)
  }
}

/** External Redis reached via otoroshi's StatefulClient manager (Lettuce, ByteString codec). */
class LettuceStateBackend(env: Env, clientId: String, uri: String) extends SmolStateBackend {

  // fetch the connection through the manager on each op: cheap (TrieMap lookup) and handles reconnects
  private def cmds =
    env.statefulClientsManager.client(clientId, LettuceStatefulClientConfig(uri)).async()

  private def fromJava[T](rf: io.lettuce.core.RedisFuture[T]): Future[T] = {
    val p = Promise[T]()
    rf.whenComplete(new java.util.function.BiConsumer[T, Throwable] {
      override def accept(res: T, err: Throwable): Unit =
        if (err != null) p.failure(err) else p.success(res)
    })
    p.future
  }

  private def bs(s: String): ByteString = ByteString(s.getBytes(StandardCharsets.UTF_8))

  override def hgetAll(key: String)(implicit ec: ExecutionContext): Future[Map[String, String]] =
    fromJava(cmds.hgetall(key)).map(_.asScala.map { case (k, v) => (k, v.utf8String) }.toMap)

  override def hset(key: String, field: String, value: String)(implicit ec: ExecutionContext): Future[Unit] =
    fromJava(cmds.hset(key, field, bs(value))).map(_ => ())

  override def hdel(key: String, field: String)(implicit ec: ExecutionContext): Future[Unit] =
    fromJava(cmds.hdel(key, field)).map(_ => ())

  override def incr(key: String)(implicit ec: ExecutionContext): Future[Long] =
    fromJava(cmds.incr(key)).map(_.longValue())

  override def acquireLock(key: String, ttlMs: Long)(implicit ec: ExecutionContext): Future[Boolean] =
    fromJava(cmds.set(key, bs("1"), SetArgs.Builder.nx().px(ttlMs))).map(r => r != null && r == "OK")

  override def releaseLock(key: String)(implicit ec: ExecutionContext): Future[Unit] =
    fromJava(cmds.del(key)).map(_ => ())

  override def del(key: String)(implicit ec: ExecutionContext): Future[Unit] =
    fromJava(cmds.del(key)).map(_ => ())
}

/** Fallback backed by otoroshi's own datastore redis (`env.datastores.redis`). */
class RedisLikeStateBackend(redis: RedisLike, env: Env) extends SmolStateBackend {

  private implicit val ev: Env = env

  private def bs(s: String): ByteString = ByteString(s.getBytes(StandardCharsets.UTF_8))

  override def hgetAll(key: String)(implicit ec: ExecutionContext): Future[Map[String, String]] =
    redis.hgetall(key).map(_.map { case (k, v) => (k, v.utf8String) }.toMap)

  override def hset(key: String, field: String, value: String)(implicit ec: ExecutionContext): Future[Unit] =
    redis.hsetBS(key, field, bs(value)).map(_ => ())

  override def hdel(key: String, field: String)(implicit ec: ExecutionContext): Future[Unit] =
    redis.hdel(key, field).map(_ => ())

  override def incr(key: String)(implicit ec: ExecutionContext): Future[Long] =
    redis.incr(key)

  override def acquireLock(key: String, ttlMs: Long)(implicit ec: ExecutionContext): Future[Boolean] =
    redis.setnxBS(key, bs("1"), Some(ttlMs))

  override def releaseLock(key: String)(implicit ec: ExecutionContext): Future[Unit] =
    redis.del(key).map(_ => ())

  override def del(key: String)(implicit ec: ExecutionContext): Future[Unit] =
    redis.del(key).map(_ => ())
}
