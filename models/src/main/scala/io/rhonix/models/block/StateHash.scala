package io.rhonix.models.block

import com.google.protobuf.ByteString

object StateHash {
  type StateHash = ByteString

  val Length = 32
}
