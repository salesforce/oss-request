/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package models

trait Task {
  val id: Int
}

case class Approval(id: Int) extends Task

case class Action(id: Int) extends Task

case class InputNeeded(id: Int) extends Task
