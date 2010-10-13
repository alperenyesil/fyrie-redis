package net.fyrie.redis
package commands

import Command._
import handlers._
import serialization.{Parse, Format}

import scala.collection.mutable.ArrayBuilder

trait GenericCommands {

  /**
   * Request keys matching `pattern`.
   *
   * Returns: `Result[Stream[A]]`
   *
   * @param pattern Use "*" as wildcard
   *
   * @see <a href="http://code.google.com/p/redis/wiki/KeysCommand">Redis Command Reference</a>
   */
  case class keys[A](pattern: Any = "*")(implicit format: Format, parse: Parse[A]) extends Command(MultiBulkAsFlat[A])

  /**
   * Request a random key.
   *
   * Returns: `Result[A]`
   *
   * @see <a href="http://code.google.com/p/redis/wiki/RandomkeyCommand">Redis Command Reference</a>
   */
  case class randomkey[A]()(implicit parse: Parse[A]) extends Command(Bulk[A])

  /**
   * Rename a key.
   *
   * Returns: `Unit`
   *
   * @param oldkey The existing key to rename.
   * @param newkey The new key.
   *
   * @see <a href="http://code.google.com/p/redis/wiki/RenameCommand">Redis Command Reference</a>
   */
  case class rename(oldkey: Any, newkey: Any)(implicit format: Format) extends Command(OkStatus)

  /**
   * Rename a key if `newkey` does not already exist. Returns true if
   * successfully renamed.
   *
   * Returns: `Boolean`
   *
   * @param oldkey The existing key to rename.
   * @param newkey The new key.
   *
   * @see <a href="http://code.google.com/p/redis/wiki/RenamenxCommand">Redis Command Reference</a>
   */
  case class renamenx(oldkey: Any, newkey: Any)(implicit format: Format) extends Command(IntAsBoolean)

  /**
   * Request the number of keys in the current database.
   *
   * Returns: `Int`
   *
   * @see <a href="http://code.google.com/p/redis/wiki/DbsizeCommand">Redis Command Reference</a>
   */
  case object dbsize extends Command(ShortInt)

  /**
   * Tests if `key` exists.
   *
   * Returns: `Boolean`
   *
   * @param key The key to test the existance of.
   *
   * @see <a href="http://code.google.com/p/redis/wiki/ExistsCommand">Redis Command Reference</a>
   */
  case class exists(key: Any)(implicit format: Format) extends Command(IntAsBoolean)

  /**
   * Delete each key in `keys`. Returns the actual number of keys deleted.
   *
   * Note: Be aware that this command takes an `Iterable[Any]`, so if a
   * single `String` is passed it will be converted into a `Seq[Char]`
   * which is probably not what you want. Instead, provide a `List[String]`
   * or similar collection.
   *
   * Returns: `Int`
   *
   * @param keys A collection of keys to delete.
   *
   * @see <a href="http://code.google.com/p/redis/wiki/DelCommand">Redis Command Reference</a>
   */
  case class del(keys: Iterable[Any])(implicit format: Format) extends Command(ShortInt) {
    override def args = keys.iterator
  }

  /**
   * Requests the type of the value stored at `key`.
   *
   * Returns: `String`
   *
   * @param key The key of the value to check.
   *
   * @see <a href="http://code.google.com/p/redis/wiki/TypeCommand">Redis Command Reference</a>
   */
  case class getType(key: Any)(implicit format: Format) extends Command(Status) {
    override def name = "TYPE"
  }

  /**
   * Set a timeout of the specified key. After the timeout the key will be
   * automatically deleted. Returns 'true' if the expire command is successful.
   *
   * Returns: `Boolean`
   *
   * @param key     The key to expire.
   * @param seconds The timeout in seconds.
   *
   * @see <a href="http://code.google.com/p/redis/wiki/ExpireCommand">Redis Command Reference</a>
   */
  case class expire(key: Any, seconds: Int)(implicit format: Format) extends Command(IntAsBoolean)

  /**
   * Set a timeout of the specified key. After the timeout the key will be
   * automatically deleted. Returns 'true' if the expire command is successful.
   *
   * Returns: `Boolean`
   *
   * @param key      The key to expire.
   * @param unixtime The timeout in the form of a UNIX timestamp.
   *
   * @see <a href="http://code.google.com/p/redis/wiki/ExpireCommand">Redis Command Reference</a>
   */
  case class expireat(key: Any, unixtime: Int)(implicit format: Format) extends Command(IntAsBoolean)

  /**
   * Select a DB with the supplied zero-based index.
   *
   * Returns: `Unit`
   *
   * @param index Zero-based index of database.
   *
   * @see <a href="http://code.google.com/p/redis/wiki/SelectCommand">Redis Command Reference</a>
   */
  case class select(index: Int = 0)(implicit format: Format) extends Command(OkStatus)

  /**
   * Delete all keys in the currently selected database.
   *
   * Returns: `Unit`
   *
   * @see <a href="http://code.google.com/p/redis/wiki/FlushdbCommand">Redis Command Reference</a>
   */
  case object flushdb extends Command(OkStatus)

  /**
   * Delete all keys in all databases.
   *
   * Returns: `Unit`
   *
   * @see <a href="http://code.google.com/p/redis/wiki/FlushallCommand">Redis Command Reference</a>
   */
  case object flushall extends Command(OkStatus)

  /**
   * Move `key` from the currently selected database to the database at index `db`.
   * Returns `true` if successful.
   *
   * Returns: `Boolean`
   *
   * @param key The key to move.
   * @param db  The zero-based index of the destination database.
   *
   * @see <a href="http://code.google.com/p/redis/wiki/MoveCommand">Redis Command Reference</a>
   */
  case class move(key: Any, db: Int = 0)(implicit format: Format) extends Command(IntAsBoolean)

  /**
   * Asks the server to close the connection.
   *
   * Returns: `Unit`
   *
   * @see <a href="http://code.google.com/p/redis/wiki/QuitCommand">Redis Command Reference</a>
   */
  case object quit extends Command(NoHandler)

  /**
   * Supply a password if required to send commands.
   *
   * Returns: `Unit`
   *
   * @param secret Server authentication password.
   *
   * @see <a href="http://code.google.com/p/redis/wiki/AuthCommand">Redis Command Reference</a>
   */
  case class auth(secret: Any)(implicit format: Format) extends Command(OkStatus)

  /**
   * Sorts the elements contained in a List, Set, or Sorted Set value at `key`.
   *
   * Returns: `Result[Stream[Option[A]]]`
   *
   * @param key   The key of the List, Set, or Sorted Set to sort.
   * @param by    Optional pattern used to generate the key names of the weights used for sorting.
   *              Use "nosort" if no sorting is required, which can be useful if using
   *              the `get` parameter to return other values.
   * @param limit Optional zero-based start-index and count of items to return.
   * @param get   List of patterns used to generate key names of values to return.
   *              Use "#" to include the elements of the sorted list as well.
   *              If different parsers are required for different values, see `SortTupleCommands`.
   * @param order Optional `SortOrder`
   * @param alpha If `true`, sort lexicalgraphically.
   *
   * @see <a href="http://code.google.com/p/redis/wiki/SortCommand">Redis Command Reference</a>
   */
  case class sort[A](key: Any,
                     by: Option[Any] = None,
                     limit: Option[(Int, Int)] = None,
                     get: Seq[Any] = Nil,
                     order: Option[SortOrder] = None,
                     alpha: Boolean = false)(implicit
                                             format: Format,
                                             parse: Parse[A]) extends Command(MultiBulk[A]) {
    override def args = arg1(key) ++ argN1("BY", by) ++ argN2("LIMIT", limit) ++ argN1("GET", get) ++
                        argN1(order) ++ argN1(if (alpha) (Some("ALPHA")) else (None))
  }

  /**
   * Executes a list of commands atomically. Returns a list of each command's response. If an
   * error is found while reading the responses the exception will be returned in the list.
   *
   * Returns: `Result[Seq[Any]]`
   *
   * @param commands List of commands to send.
   *
   * @see <a href="http://code.google.com/p/redis/wiki/MultiExecCommand">Redis Command Reference</a>
   */
/*  case class multiexec(commands: Seq[Command[_]]) extends Command(MultiExec(commands.map(_.handler))) {
    override def toBytes = {
      val b = new ArrayBuilder.ofByte
      b ++= create(Seq("MULTI".getBytes))
      commands.foreach(b ++= _.toBytes)
      b ++= create(Seq("EXEC".getBytes))
      b.result
    }
  }*/
}

trait SortTupled {
  self: Command[_] =>
  val key: Any
  val by: Option[Any]
  val limit: Option[(Int, Int)]
  val get: Product
  val order: Option[SortOrder]
  val alpha: Boolean
  override def name = "SORT"
  override def args = arg1(key) ++ argN1("BY", by) ++ argN2("LIMIT", limit) ++ argN1("GET", get.productIterator.toStream) ++ argN1(order) ++ argN1(if (alpha) (Some("ALPHA")) else (None))
}
