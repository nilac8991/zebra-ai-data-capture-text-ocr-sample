package com.example.zebra.zaidcsdktextocrsample.data

import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> ListenableFuture<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addListener(
            {
                try {
                    cont.resume(get())
                } catch (e: CancellationException) {
                    cont.cancel(e)
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            },
            // Runs listener inline; fine for a simple bridge
            Runnable::run
        )
    }