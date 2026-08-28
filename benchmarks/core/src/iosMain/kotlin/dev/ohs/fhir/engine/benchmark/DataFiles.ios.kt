/*
 * Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ohs.fhir.engine.benchmark

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength
import platform.posix.getenv
import platform.posix.memcpy

/**
 * Read straight off the host filesystem: a simulator shares it, so the packaged Synthea directory
 * needs no bundling. Set by the Gradle test task; a device would need the data in its own bundle.
 */
@OptIn(ExperimentalForeignApi::class)
private fun dataDirectory(): String? =
  getenv("BENCHMARK_DATA_DIR")?.toKString()?.takeIf { it.isNotBlank() }

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun listDataFiles(): List<String> {
  val directory = dataDirectory() ?: return emptyList()

  @Suppress("UNCHECKED_CAST")
  val names =
    NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null) as? List<String>
  return names.orEmpty().sorted()
}

/** 64 KiB: large enough that a multi-hundred-megabyte file is not read in millions of syscalls. */
private const val CHUNK_BYTES = 64 * 1024

private const val NEWLINE = '\n'.code.toByte()

/**
 * Read in chunks and split on newlines, rather than through `stringWithContentsOfFile`, which would
 * hold a whole Synthea file in memory to hand back one string.
 *
 * Split on bytes rather than on decoded text: a chunk boundary lands mid-character often enough at
 * this size, and decoding each chunk separately would corrupt those characters.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun dataFileLines(relativePath: String): Flow<String> = flow {
  val directory = dataDirectory() ?: return@flow
  val handle = NSFileHandle.fileHandleForReadingAtPath("$directory/$relativePath") ?: return@flow
  try {
    var pending = ByteArray(0)
    while (true) {
      val chunk = handle.readDataOfLength(CHUNK_BYTES.toULong()).toByteArray()
      if (chunk.isEmpty()) break
      pending += chunk
      var start = 0
      for (index in pending.indices) {
        if (pending[index] == NEWLINE) {
          emit(pending.decodeToString(start, index))
          start = index + 1
        }
      }
      pending = pending.copyOfRange(start, pending.size)
    }
    // A last line with no trailing newline.
    if (pending.isNotEmpty()) emit(pending.decodeToString())
  } finally {
    handle.closeFile()
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
  val size = length.toInt()
  if (size == 0) return ByteArray(0)
  val bytes = ByteArray(size)
  bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, length) }
  return bytes
}
