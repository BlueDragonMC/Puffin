package com.bluedragonmc.puffin.services

import org.slf4j.Logger
import org.slf4j.LoggerFactory

abstract class Service {

    protected val logger: Logger = LoggerFactory.getLogger(this::class.java)

    open fun close() { }
}