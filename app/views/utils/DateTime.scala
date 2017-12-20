/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package views.utils

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object DateTime {
  def date(zonedDateTime: ZonedDateTime): String = {
    zonedDateTime.format(DateTimeFormatter.ofPattern("MMM dd, yyyy"))
  }
}
