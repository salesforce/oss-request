/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package models

import java.time.ZonedDateTime

case class Comment(id: Int, creatorEmail: String, createDate: ZonedDateTime, contents: String, taskId: Int)
