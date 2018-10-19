package com.mtprototype.main

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.Random

object PrimeUtils {
  private val randomEngine = new Random()
  private val MIN_PRIME_INDEX = 100000
  private val MAX_PRIME_INDEX = 664578

  def primesIterative(end: Int): List[Long] = {
    val primeIndices = mutable.ArrayBuffer.fill((end + 1) / 2)(1L)

    val intSqrt = Math.sqrt(end).toInt
    for (i <- 3 to end by 2 if i <= intSqrt) {
      for (nonPrime <- i * i to end by 2 * i) {
        primeIndices.update(nonPrime / 2, 0L)
      }
    }

    (for (i <- primeIndices.indices if primeIndices(i) == 1L) yield 2L * i + 1L).tail.toList
  }

  /**
    * Generates pair of prime numbers, where 1st is less than 2nd.
    */
  def getBigPair: (Long, Long) = {
    val primes = primesIterative(10000000)
    val (index1, index2) = (
      randomEngine.nextInt((MAX_PRIME_INDEX - MIN_PRIME_INDEX) + 1) + MIN_PRIME_INDEX,
      randomEngine.nextInt((MAX_PRIME_INDEX - MIN_PRIME_INDEX) + 1) + MIN_PRIME_INDEX
    )

    val (number1, number2) = (primes(index1), primes(index2))

    (Math.min(number1, number2), Math.max(number1, number2))
  }

  def factorize(x: Long): List[Long] = {
    @tailrec
    def foo(x: Long, a: Long = 2, list: List[Long] = Nil): List[Long] = a*a > x match {
      case false if x % a == 0 => foo(x / a, a    , a :: list)
      case false               => foo(x    , a + 1, list)
      case true                => x :: list
    }
    foo(x)
  }
}
