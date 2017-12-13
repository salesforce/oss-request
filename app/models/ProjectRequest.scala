/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package models

import java.time.ZonedDateTime

case class ProjectRequest(id: Int, name: String, slug: String, createDate: ZonedDateTime, creatorEmail: String, state: State.State)
