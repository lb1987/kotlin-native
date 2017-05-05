/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import kotlinx.cinterop.*
import sockets.*
import kotlin.coroutines.experimental.*
import kotlin.coroutines.experimental.intrinsics.*

fun main(args: Array<String>) {
    if (args.size < 1) {
        println("Usage: ./echo_server <port>")
        return
    }

    val port = args[0].toShort()

    memScoped {

        val serverAddr = alloc<sockaddr_in>()

        val listenFd = socket(AF_INET, SOCK_STREAM, 0)
                .ensureUnixCallResult { it >= 0 }

        with(serverAddr) {
            memset(this.ptr, 0, sockaddr_in.size)
            sin_family = AF_INET.narrow()
            sin_addr.s_addr = htons(0).toInt()
            sin_port = htons(port)
        }

        bind(listenFd, serverAddr.ptr.reinterpret(), sockaddr_in.size.toInt())
                .ensureUnixCallResult { it == 0 }

        fcntl(listenFd, F_SETFL, O_NONBLOCK)
                .ensureUnixCallResult { it == 0 }

        listen(listenFd, 10)
                .ensureUnixCallResult { it == 0 }

        var connectionId = 0
        acceptClientsAndRun(listenFd) {
            val ourConnectionId = ++connectionId
            memScoped {
                val bufferLength = 100L
                val buffer = allocArray<ByteVar>(bufferLength)
                val connectionIdString = "#$ourConnectionId: ".cstr
                val connectionIdBytes = connectionIdString.getPointer(this)

                while (true) {
                    val length = read(buffer, bufferLength)

                    if (length == 0L)
                        break

                    write(connectionIdBytes, connectionIdString.size.toLong())
                    write(buffer, length)
                }
            }
        }
    }
}

abstract class Client() {
    abstract suspend fun read(data: CArrayPointer<ByteVar>, dataLength: Long): Long

    abstract suspend fun write(data: CArrayPointer<ByteVar>, length: Long)
}

open class EmptyContinuation(override val context: CoroutineContext = EmptyCoroutineContext) : Continuation<Any?> {
    companion object : EmptyContinuation()
    override fun resume(value: Any?) {}
    override fun resumeWithException(exception: Throwable) { throw exception }
}

fun acceptClientsAndRun(serverFd: Int, block: suspend Client.() -> Unit) {
    class ClientState(val data: CArrayPointer<ByteVar>, val length: Long, val continuation: Continuation<*>)
    memScoped {
        val readWaitingList = mutableMapOf<Int, ClientState>()
        val writeWaitingList = mutableMapOf<Int, ClientState>()
        val readfds = alloc<fd_set>()
        val writefds = alloc<fd_set>()
        var maxfd = serverFd
        while (true) {
            FD_ZERO(readfds)
            FD_SET(serverFd, readfds)
            readWaitingList.keys.forEach { FD_SET(it, readfds) }
            FD_ZERO(writefds)
            writeWaitingList.keys.forEach { FD_SET(it, writefds) }
            var nready = pselect(maxfd + 1, readfds.ptr, writefds.ptr, null, null, null)
                    .ensureUnixCallResult { it >= 0 }
            loop@for (socketFd in 0..maxfd) {
                if (nready == 0) break
                val readyForReading = FD_ISSET(socketFd, readfds)
                val readyForWriting = FD_ISSET(socketFd, writefds)
                if (readyForReading || readyForWriting)
                    --nready
                when {
                    readyForReading && socketFd == serverFd -> {
                        // Accept new client.
                        val clientFd = accept(serverFd, null, null)
                        if (clientFd < 0) {
                            if (errno == EWOULDBLOCK) {
                                break@loop
                            }
                            throwUnixError()
                        }
                        fcntl(clientFd, F_SETFL, O_NONBLOCK)
                                .ensureUnixCallResult { it == 0 }
                        if (maxfd < clientFd)
                            maxfd = clientFd
                        block.startCoroutine(object : Client() {
                            override suspend fun read(data: CArrayPointer<ByteVar>, dataLength: Long): Long {
                                val length = read(clientFd, data, dataLength)
                                if (length >= 0)
                                    return length
                                if (errno != EWOULDBLOCK)
                                    throwUnixError()
                                // Save continuation and suspend.
                                return suspendCoroutineOrReturn { continuation ->
                                    readWaitingList.put(clientFd, ClientState(data, dataLength, continuation))
                                    COROUTINE_SUSPENDED
                                }
                            }

                            override suspend fun write(data: CArrayPointer<ByteVar>, length: Long) {
                                val written = write(clientFd, data, length)
                                if (written >= 0)
                                    return
                                if (errno != EWOULDBLOCK)
                                    throwUnixError()
                                // Save continuation and suspend.
                                return suspendCoroutineOrReturn { continuation ->
                                    writeWaitingList.put(clientFd, ClientState(data, length, continuation))
                                    COROUTINE_SUSPENDED
                                }
                            }
                        }, EmptyContinuation)
                    }
                    readyForReading -> {
                        // Resume reading operation.
                        val state = readWaitingList[socketFd]!!
                        readWaitingList.remove(socketFd)
                        val length = read(socketFd, state.data, state.length)
                                .ensureUnixCallResult { it >= 0 }
                        (state.continuation as Continuation<Long>).resume(length)
                    }
                    readyForWriting -> {
                        // Resume writing operation.
                        val state = writeWaitingList[socketFd]!!
                        writeWaitingList.remove(socketFd)
                        write(socketFd, state.data, state.length)
                                .ensureUnixCallResult { it >= 0 }
                        (state.continuation as Continuation<Unit>).resume(Unit)
                    }
                }
            }
        }
    }
}

val errno: Int
    get() = __error()!!.pointed.value

fun FD_ZERO(set: fd_set) {
    memset(set.fds_bits, 0, sizeOf<fd_set>())
}

fun FD_SET(bit: Int, set: fd_set) {
    set.fds_bits[bit / 32] = set.fds_bits[bit / 32] or (1 shl (bit % 32))
}

fun FD_ISSET(bit: Int, set: fd_set): Boolean {
    return set.fds_bits[bit / 32] and (1 shl (bit % 32)) != 0
}

// Not available through interop because declared as macro:
fun htons(value: Short) = ((value.toInt() ushr 8) or (value.toInt() shl 8)).toShort()

fun throwUnixError(): Nothing {
    perror(null) // TODO: store error message to exception instead.
    throw Error("UNIX call failed")
}

inline fun Int.ensureUnixCallResult(predicate: (Int) -> Boolean): Int {
    if (!predicate(this)) {
        throwUnixError()
    }
    return this
}

inline fun Long.ensureUnixCallResult(predicate: (Long) -> Boolean): Long {
    if (!predicate(this)) {
        throwUnixError()
    }
    return this
}