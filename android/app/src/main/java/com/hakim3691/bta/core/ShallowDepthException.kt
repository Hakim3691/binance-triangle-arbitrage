package com.hakim3691.bta.core

/** Thrown when the order book is too shallow to fill the requested amount. */
class ShallowDepthException(message: String) : Exception(message)
