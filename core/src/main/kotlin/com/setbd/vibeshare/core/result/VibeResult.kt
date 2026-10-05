package com.setbd.vibeshare.core.result
import com.setbd.vibeshare.core.error.AppError


/**
 * Functional result type used across all VibeShare layers.
 * Never leaks raw exceptions into the UI: errors are always [AppError]s.
 */
sealed interface VibeResult<out T> {
    data class Success<T>(val data: T) : VibeResult<T>
    data class Failure(val error: AppError) : VibeResult<Nothing>

    fun getOrNull(): T? = (this as? Success)?.data

    fun errorOrNull(): AppError? = (this as? Failure)?.error

    companion object {
        fun <T> success(data: T): VibeResult<T> = Success(data)
        fun failure(error: AppError): VibeResult<Nothing> = Failure(error)
    }
}

inline fun <T, R> VibeResult<T>.map(transform: (T) -> R): VibeResult<R> = when (this) {
    is VibeResult.Success -> VibeResult.Success(transform(data))
    is VibeResult.Failure -> this
}

inline fun <T> VibeResult<T>.onSuccess(block: (T) -> Unit): VibeResult<T> {
    if (this is VibeResult.Success) block(data)
    return this
}

inline fun <T> VibeResult<T>.onFailure(block: (AppError) -> Unit): VibeResult<T> {
    if (this is VibeResult.Failure) block(error)
    return this
}
