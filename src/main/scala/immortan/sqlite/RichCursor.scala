package immortan.sqlite

import scodec.bits.{BitVector, ByteVector}
import immortan.crypto.Tools.Bytes
import scala.util.Try


trait RichCursor extends Iterable[RichCursor] {
  def iterable[T](transform: RichCursor => T): Iterable[T]

  def set[T](transform: RichCursor => T): Set[T]

  def headTry[T](fun: RichCursor => T): Try[T]

  def byteVec(byteKey: String): ByteVector

  def bitVec(bitKey: String): BitVector

  def bytes(byteKey: String): Bytes

  def string(stringKey: String): String

  def long(longKey: String): Long

  def long(pos: Int): Long

  def int(intKey: String): Int
}
