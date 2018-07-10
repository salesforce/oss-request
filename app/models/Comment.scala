/*
 * Copyright (c) Salesforce.com, inc. 2018
 */

package models

import java.time.ZonedDateTime

case class Comment(id: Int, creatorEmail: String, createDate: ZonedDateTime, contents: String, taskId: Int)
