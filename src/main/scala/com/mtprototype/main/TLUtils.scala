package com.mtprototype.main

import scodec.bits._
import scodec.codecs.ascii32

object TLUtils {
  val REQ_PQ_CONSTRUCTOR = hex"60469778"
  val RES_PQ_CONSTRUCTOR = hex"05162463"
  val REQ_DH_PARAMS_CONSTRUCTOR = hex"d712e4be"
  val VECTOR_LONG_CONSTRUCTOR = hex"1cb5c415"

  def serializeString(str: String): Option[ByteVector] = {
    ascii32.encode(str)
      .map(_.toByteVector)
      .toOption
  }

  def deserializeString(bv: ByteVector): Option[(String, ByteVector)] = {
    ascii32.decode(bv.toBitVector)
      .map(x => x.value -> x.remainder.toByteVector)
      .toOption
  }
}
