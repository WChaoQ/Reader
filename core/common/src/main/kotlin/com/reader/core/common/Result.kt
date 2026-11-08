package com.reader.core.common

sealed interface Result<out T> {
    data class Success<T>(val data: T) : Result<T>
    data class Error(val exception: Throwable, val message: String? = null) : Result<Nothing>
}

inline fun <T> Result<T>.getOrNull(): T? = (this as? Result.Success)?.data
