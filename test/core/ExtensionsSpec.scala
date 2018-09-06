/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package core

import core.Extensions._
import org.scalatest.{MustMatchers, WordSpec}

class ExtensionsSpec extends WordSpec with MustMatchers {

  "markdown" must {
    "convert markdown to html" in {
      "[asdf](http://asdf.com)".markdown must equal ("""<p><a href="http://asdf.com">asdf</a></p>""")
    }
  }

}


