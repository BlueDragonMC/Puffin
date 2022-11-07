package com.bluedragonmc.puffin.services

import org.slf4j.Logger
import org.slf4j.LoggerFactory

abstract class Service(val app: ServiceHolder) {

    protected val logger: Logger = LoggerFactory.getLogger(this::class.java)

    open fun initialize() { }
    open fun close() { }

    open fun onServiceInit(other: Service) {}
}