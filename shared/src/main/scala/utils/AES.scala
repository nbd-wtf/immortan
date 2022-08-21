package immortan.utils

import javax.crypto.Cipher
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import scodec.bits.ByteVector

object AES {
  final val ivLength = 16

  def cipher(key: Array[Byte], initVector: Array[Byte], mode: Int): Cipher = {
    val aesCipher: Cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    val ivParameterSpec: IvParameterSpec = new IvParameterSpec(initVector)
    aesCipher.init(mode, new SecretKeySpec(key, "AES"), ivParameterSpec)
    aesCipher
  }

  def decode(
      data: Array[Byte],
      key: Array[Byte],
      initVector: Array[Byte]
  ): ByteVector =
    ByteVector.view(cipher(key, initVector, Cipher.DECRYPT_MODE) doFinal data)

  def encode(
      data: Array[Byte],
      key: Array[Byte],
      initVector: Array[Byte]
  ): ByteVector =
    ByteVector.view(cipher(key, initVector, Cipher.ENCRYPT_MODE) doFinal data)
}
