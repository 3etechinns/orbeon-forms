/**
 * Copyright (C) 2012 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.util

import org.joda.time.format.{DateTimeFormatter, DateTimeFormat}
import org.joda.time.DateTimeZone
import org.orbeon.saxon.expr.XPathContext
import org.orbeon.saxon.value.{CalendarValue, DateValue, DateTimeValue}
import org.orbeon.saxon.`type`.ValidationFailure
import java.util.{Properties, Date, Locale}
import javax.xml.transform.Result

object DateUtils {

    // ISO 8601 formats without timezones
    // From the doc: "DateTimeFormat is thread-safe and immutable, and the formatters it returns are as well."
    val XsDateTimeLong = withLocaleTZ(DateTimeFormat forPattern "yyyy-MM-dd'T'HH:mm:ss.SSS")
    val XsDateTime     = withLocaleTZ(DateTimeFormat forPattern "yyyy-MM-dd'T'HH:mm:ss")
    val XsDate         = withLocaleTZ(DateTimeFormat forPattern "yyyy-MM-dd")

    // RFC 1123 format
    val RFC1123Date    = withLocaleTZ(DateTimeFormat forPattern "EEE, dd MMM yyyy HH:mm:ss 'GMT'")

    private def withLocaleTZ(format: DateTimeFormatter) = format withLocale Locale.US withZone DateTimeZone.UTC

    // Epoch dateTime/date
    private val EpochDateTime = new DateTimeValue(1970, 1, 1, 0, 0, 0, 0, 0)
    private val EpochDate = new DateValue(1970, 1, 1, 0)

    // Default timezone offset in minutes
    // This is obtained once at the time the current object initializes
    val DefaultOffsetMinutes = {
        val currentInstant = (new Date).getTime
        DateTimeZone.getDefault.getOffset(currentInstant) / 1000 / 60
    }

    // Parse a date in XML Schema-compatible ISO format:
    //
    // - Format for a dateTime: [-]yyyy-mm-ddThh:mm:ss[.fff*][([+|-]hh:mm | Z)]
    // - Format for a date:     [-]yyyy-mm-dd[([+|-]hh:mm | Z)]
    //
    // Throws IllegalArgumentException if the date format is incorrect.
    def parse(date: String): Long = {
        val valueOrFailure =
            if (date.length >= 11 && date.charAt(10) == 'T')
                DateTimeValue.makeDateTimeValue(date)
            else
                DateValue.makeDateValue(date)

        valueOrFailure match {
                case value: CalendarValue ⇒
                    value.subtract(if (value.isInstanceOf[DateTimeValue]) EpochDateTime else EpochDate, TZXPathContext).getLengthInMilliseconds
                case failure: ValidationFailure ⇒
                    throw new IllegalArgumentException(failure.getMessage)
            }
    }

    // Parse an RFC 1123 dateTime
    def parseRFC1123(date: String): Long = RFC1123Date.parseDateTime(date).getMillis

    // Format the given instant
    def format(instant: Long, format: DateTimeFormatter): String = format.print(instant)
}

// Mock XPathContext
// We tried using Mockito, but then that's yet another runtime dependency
object TZXPathContext extends XPathContext {

    import org.orbeon.saxon.expr.XPathContext
    import org.orbeon.saxon.event.SequenceReceiver
    import org.orbeon.saxon.`type`.SchemaType
    import org.orbeon.saxon.trace.InstructionInfo
    import org.orbeon.saxon.instruct.LocalParam
    import org.orbeon.saxon.om.{ValueRepresentation, StructuredQName, SequenceIterator}

    // Return the default timezone offset
    def getImplicitTimezone = DateUtils.DefaultOffsetMinutes

    // None of these methods are called by Saxon upon subtract()
    def newContext() = Illegal
    def newCleanContext() = Illegal
    def newMinorContext() = Illegal
    def getLocalParameters = Illegal
    def getTunnelParameters = Illegal
    def setOrigin(expr: InstructionInfo) = Illegal
    def setOriginatingConstructType(loc: Int) = Illegal
    def getOrigin = Illegal
    def getOriginatingConstructType = Illegal
    def getController = Illegal
    def getConfiguration = Illegal
    def getNamePool = Illegal
    def setCaller(caller: XPathContext) = Illegal
    def getCaller = Illegal
    def setCurrentIterator(iter: SequenceIterator) = Illegal
    def getCurrentIterator = Illegal
    def getContextPosition = Illegal
    def getContextItem = Illegal
    def getLast = Illegal
    def isAtLast = Illegal
    def getCollation(name: String) = Illegal
    def getDefaultCollation = Illegal
    def useLocalParameter(qName: StructuredQName, binding: LocalParam, isTunnel: Boolean) = Illegal
    def getStackFrame = Illegal
    def evaluateLocalVariable(slotnumber: Int) = Illegal
    def setLocalVariable(slotnumber: Int, value: ValueRepresentation) = Illegal
    def changeOutputDestination(props: Properties, result: Result, isFinal: Boolean, hostLanguage: Int, validation: Int, schemaType: SchemaType) = Illegal
    def setTemporaryReceiver(out: SequenceReceiver) = Illegal
    def setReceiver(receiver: SequenceReceiver) = Illegal
    def getReceiver = Illegal
    def getCurrentMode = Illegal
    def getCurrentTemplateRule = Illegal
    def getCurrentGroupIterator = Illegal
    def getCurrentRegexIterator = Illegal
    def getCurrentDateTime = Illegal
    def iterateStackFrames() = Illegal

    private def Illegal = throw new IllegalStateException
}