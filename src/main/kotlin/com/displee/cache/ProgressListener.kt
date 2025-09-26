package com.displee.cache

fun interface ProgressListener {
    fun notify(progress: Double, message: String?)
}