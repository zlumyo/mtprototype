package com.mtprototype.main

object ClientState extends Enumeration {
  type ClientState = Value
  val WaitDHParams, Closed, Error = Value
}