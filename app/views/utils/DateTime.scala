/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package views.utils

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object DateTime {
  def date(zonedDateTime: ZonedDateTime): String = {
    zonedDateTime.format(DateTimeFormatter.ofPattern("MMM dd, yyyy"))
  }
}
