package com.example.zebra.zaidcsdktextocrsample.data

import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

suspend fun <T> CompletableFuture<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        whenComplete { value, error ->
            if (error != null) cont.resumeWithException(error)
            else cont.resume(value)
        }
        cont.invokeOnCancellation { cancel(true) }
    }